package epheis.lyphossyss.kysheon.link.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.util.Log
import epheis.lyphossyss.kysheon.link.DiagnosableLink
import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.LinkDiagnostics
import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Timeouts and limits of [AndroidBleLink]. Defaults are meant for two phones lying side by side.
 *
 * @property advertiseStartTimeout `host()` gives up if advertising has not started by then.
 * @property scanTimeout `join()` reports `Lost(PeerNotFound)` if no host was seen by then.
 * @property scanCollectWindow after the first scan result, results are collected this long and the strongest RSSI
 *   wins (the intended peer lies right next to this phone).
 * @property connectTimeout per attempt, from `connectGatt` to the GATT connection.
 * @property setupTimeout per attempt, from the GATT connection to notifications enabled (priority, MTU, discovery,
 *   CCCD write).
 * @property retryDelay pause before the single retry of a failed connection attempt (status 133 and friends).
 * @property writeConfirmTimeout a write or notification not confirmed by then releases the queue (counted as dropped).
 * @property maxQueuedFrames frames waiting in the write queue beyond which new frames are dropped.
 */
data class AndroidBleTimings(
    val advertiseStartTimeout: Duration = 5.seconds,
    val scanTimeout: Duration = 15.seconds,
    val scanCollectWindow: Duration = 1500.milliseconds,
    val connectTimeout: Duration = 10.seconds,
    val setupTimeout: Duration = 10.seconds,
    val retryDelay: Duration = 600.milliseconds,
    val writeConfirmTimeout: Duration = SerialSender.DEFAULT_CONFIRM_TIMEOUT,
    val maxQueuedFrames: Int = SerialSender.DEFAULT_CAPACITY,
)

/**
 * [Link] over Bluetooth Low Energy GATT, written from the official Android documentation.
 *
 * The same instance becomes the host (peripheral: GATT server + advertiser) through [host], or the guest (central:
 * scanner + GATT client) through [join]. After [close] it can be used again, in either role.
 *
 * Threading: every Bluetooth callback (binder threads, main thread for broadcasts) is turned into an event posted to
 * a channel, consumed by one coroutine on a serial view of [dispatcher]. All session state is only touched there,
 * so transitions are consistent without locks. Public functions may be called from any thread; [send] does not
 * switch threads (it only enqueues), [host], [join] and [close] hop onto the serial dispatcher.
 *
 * Failures never escape as exceptions: they end the session with [LinkState.Lost] and release every radio resource.
 * The caller must request [AndroidBluetooth.requiredPermissions] before [host] / [join], and call [close] when the
 * link is no longer needed. [scope] must outlive the link; if it is cancelled, resources are released anyway.
 */
class AndroidBleLink(
    context: Context,
    scope: CoroutineScope,
    internal val timings: AndroidBleTimings = AndroidBleTimings(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Link, DiagnosableLink {

    internal val appContext: Context = context.applicationContext

    private val serial: CoroutineDispatcher = dispatcher.limitedParallelism(1)
    private val supervisor = SupervisorJob(scope.coroutineContext[Job])
    internal val worker = CoroutineScope(
        scope.coroutineContext + supervisor + serial +
            CoroutineExceptionHandler { _, failure -> Log.e(TAG, "Unexpected failure in the BLE worker", failure) },
    )

    /** Callbacks become events; one consumer runs them in order on [serial]. */
    private val events = Channel<() -> Unit>(Channel.UNLIMITED)
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<LinkState>(LinkState.Idle)
    private val mutableDiagnostics = MutableStateFlow(LinkDiagnostics())

    /** Active session, written on [serial] only; read from other threads by [send]. */
    @Volatile
    private var current: BleSession? = null

    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()
    override val state: StateFlow<LinkState> = mutableState.asStateFlow()
    override val diagnostics: StateFlow<LinkDiagnostics> = mutableDiagnostics.asStateFlow()

    init {
        worker.launch {
            for (event in events) {
                try {
                    if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "event ${event.javaClass.name}")
                    event()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    // Events run through runGuarded already; this is a last resort.
                    Log.e(TAG, "Unhandled event failure", failure)
                }
            }
        }
        // The owner's scope was cancelled without close(): release the radio anyway (nothing else runs any more).
        supervisor.invokeOnCompletion {
            val session = current ?: return@invokeOnCompletion
            current = null
            session.release()
            mutableState.value = LinkState.Idle
        }
    }

    override suspend fun host() {
        val session = withContext(serial) { startSession { HostSession(this@AndroidBleLink) } } ?: return
        awaitOrAbort(session, session.started)
    }

    override suspend fun join(): Boolean {
        val session = withContext(serial) { startSession { GuestSession(this@AndroidBleLink) } }
            ?: return mutableState.value == LinkState.Connected
        return awaitOrAbort(session, session.joined)
    }

    override suspend fun send(frame: ByteArray) {
        val sender = current?.sender
        if (sender == null || mutableState.value != LinkState.Connected) {
            mutableDiagnostics.update { it.copy(framesDropped = it.framesDropped + 1) }
            return
        }
        // Dropped frames (queue full or closed) are counted by onWriteOutcome.
        if (sender.offer(frame.copyOf())) {
            val depth = sender.maxDepth.value
            if (depth > mutableDiagnostics.value.maxQueueDepth) {
                mutableDiagnostics.update { if (depth > it.maxQueueDepth) it.copy(maxQueueDepth = depth) else it }
            }
        }
    }

    override suspend fun close() {
        withContext(NonCancellable + serial) {
            val session = current
            if (session != null) teardown(session, LinkState.Idle) else mutableState.value = LinkState.Idle
        }
    }

    // ---- Session plumbing (called by HostSession / GuestSession, always on the serial dispatcher) ----

    /** Posts [block] to the event loop; it runs only if [session] is still the active one. Thread-safe. */
    internal fun post(session: BleSession, block: () -> Unit) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, "post ${block.javaClass.name}")
        events.trySend { if (current === session) runGuarded(session, block) }
    }

    /** Runs [block] after [delay] through the event loop, unless the session ended meanwhile. */
    internal fun after(session: BleSession, delay: Duration, block: () -> Unit): Job =
        session.scope.launch {
            delay(delay)
            post(session, block)
        }

    internal fun setState(session: BleSession, state: LinkState) {
        if (current === session) mutableState.value = state
    }

    /** The session can carry frames both ways. */
    internal fun setConnected(session: BleSession) {
        if (current !== session) return
        mutableState.value = LinkState.Connected
        session.joined.complete(true)
        session.started.complete(Unit)
        describe(session)
    }

    internal fun deliver(session: BleSession, frame: ByteArray) {
        if (current !== session || mutableState.value != LinkState.Connected) return
        // Oversized frames are delivered too: rejecting them is the protocol layer's job.
        inbox.trySend(frame)
        mutableDiagnostics.update { it.copy(framesReceived = it.framesReceived + 1) }
    }

    internal fun recordMtu(mtu: Int) {
        mutableDiagnostics.update { it.copy(mtu = mtu) }
    }

    internal fun recordPlatformCode(code: Int?) {
        if (code != null) mutableDiagnostics.update { it.copy(lastPlatformCode = code) }
    }

    internal fun describe(session: BleSession) {
        val peer = session.peerDevice()?.let { " · peer #${addressHash(it)}" } ?: ""
        val info = "role=${session.role}$peer · ${Build.MANUFACTURER} ${Build.MODEL} · API ${Build.VERSION.SDK_INT}"
        mutableDiagnostics.update { it.copy(platformInfo = info) }
    }

    internal fun newSender(session: BleSession, writer: PlatformWriter): SerialSender =
        SerialSender(
            scope = session.scope,
            writer = writer,
            confirmTimeout = timings.writeConfirmTimeout,
            capacity = timings.maxQueuedFrames,
            onOutcome = { onWriteOutcome(session, it) },
        )

    /** Ends [session] with `Lost`. When the adapter is going down, the reason becomes BluetoothOff whatever it was. */
    internal fun fail(session: BleSession, reason: LostReason, code: Int? = null) {
        if (current !== session) return
        val effective = when (reason) {
            LostReason.PermissionDenied, LostReason.Unsupported -> reason
            else -> if (adapterIsOff()) LostReason.BluetoothOff else reason
        }
        Log.w(TAG, "${session.role} session lost: $effective (code $code)")
        recordPlatformCode(code)
        teardown(session, LinkState.Lost(effective, code))
    }

    /** Runs [block] for [session]; any exception ends the session with the matching `Lost` reason. */
    internal fun runGuarded(session: BleSession, block: () -> Unit) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: LinkFailure) {
            fail(session, failure.reason, failure.code)
        } catch (_: SecurityException) {
            fail(session, LostReason.PermissionDenied)
        } catch (failure: Throwable) {
            // IllegalStateException when the adapter is off, DeadObjectException wrappers, vendor quirks…
            Log.w(TAG, "Bluetooth call failed", failure)
            fail(session, LostReason.ConnectionFailed)
        }
    }

    // ---- Internals ----

    private fun startSession(factory: () -> BleSession): BleSession? {
        if (current != null || !supervisor.isActive) return null
        val session = factory()
        current = session
        // Frames of a previous session must not leak into this one.
        while (inbox.tryReceive().isSuccess) continue
        mutableDiagnostics.value = LinkDiagnostics()
        describe(session)
        runGuarded(session) { session.start() }
        return session
    }

    private suspend fun <T> awaitOrAbort(session: BleSession, result: Deferred<T>): T =
        try {
            result.await()
        } catch (cancellation: CancellationException) {
            // The caller gave up (screen left…): do not leave the radio scanning or advertising.
            withContext(NonCancellable + serial) { teardown(session, LinkState.Idle) }
            throw cancellation
        }

    private fun teardown(session: BleSession, final: LinkState) {
        if (current !== session) return
        current = null
        session.release()
        mutableState.value = final
        session.started.complete(Unit)
        session.joined.complete(false)
    }

    private fun onWriteOutcome(session: BleSession, outcome: WriteOutcome) {
        when (outcome) {
            WriteOutcome.Sent -> mutableDiagnostics.update { it.copy(framesSent = it.framesSent + 1) }
            WriteOutcome.Dropped, WriteOutcome.TimedOut ->
                mutableDiagnostics.update { it.copy(framesDropped = it.framesDropped + 1) }
            is WriteOutcome.Failed -> {
                mutableDiagnostics.update {
                    it.copy(framesDropped = it.framesDropped + 1, lastPlatformCode = outcome.platformCode ?: it.lastPlatformCode)
                }
                when {
                    outcome.cause is SecurityException -> post(session) { fail(session, LostReason.PermissionDenied) }
                    outcome.cause != null && adapterIsOff() -> post(session) { fail(session, LostReason.BluetoothOff) }
                }
            }
        }
    }

    private fun adapterIsOff(): Boolean = try {
        AndroidBluetooth.adapterOrNull(appContext)?.state != BluetoothAdapter.STATE_ON
    } catch (_: SecurityException) {
        false
    }

    internal companion object {
        const val TAG = "KysheonBle"

        /** Short, non-reversible tag of a device address for the debug overlay: never the full MAC. */
        fun addressHash(device: BluetoothDevice): String = try {
            val digest = MessageDigest.getInstance("SHA-256").digest(device.address.toByteArray())
            digest.take(3).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        } catch (_: Exception) {
            "?"
        }
    }
}

/** Aborts the current event: the session ends with `Lost(reason, code)`. */
internal class LinkFailure(val reason: LostReason, val code: Int? = null) : Exception("$reason ($code)")

/**
 * One use of the radio, from `host()` / `join()` to `close()` or `Lost`. Created and driven on the link's serial
 * dispatcher. Callbacks of a session that is no longer the active one are ignored by [AndroidBleLink.post].
 */
internal abstract class BleSession(val link: AndroidBleLink, val role: String) {

    /** Watchdogs and the write queue consumer; cancelled when the session ends. */
    val scope: CoroutineScope = CoroutineScope(link.worker.coroutineContext + SupervisorJob(link.worker.coroutineContext[Job]))

    /** Created once the peer can receive frames; read by `send()` from any thread. */
    @Volatile
    var sender: SerialSender? = null

    /** Host: advertising started (or the session ended). */
    val started = CompletableDeferred<Unit>()

    /** Guest: true once Connected, false if the session ended before. */
    val joined = CompletableDeferred<Boolean>()

    private val adapterReceiver = AdapterStateReceiver(link.appContext) {
        link.post(this) { link.fail(this, LostReason.BluetoothOff) }
    }

    /** Checks hardware, [permissions] and adapter state, then starts listening to adapter changes. */
    protected fun requireReady(permissions: List<String>): BluetoothAdapter {
        val adapter = AndroidBluetooth.adapterOrNull(link.appContext) ?: throw LinkFailure(LostReason.Unsupported)
        if (AndroidBluetooth.missingPermissions(link.appContext, permissions).isNotEmpty()) {
            throw LinkFailure(LostReason.PermissionDenied)
        }
        if (!adapter.isEnabled) throw LinkFailure(LostReason.BluetoothOff)
        adapterReceiver.register()
        return adapter
    }

    /** Starts the radio work. May throw: the caller maps exceptions to `Lost`. */
    abstract fun start()

    /** The connected (or connecting) peer, for diagnostics. */
    abstract fun peerDevice(): BluetoothDevice?

    /** Stops scanning / advertising, disconnects and closes GATT objects. Must not throw. */
    protected abstract fun releaseRadio()

    /** Idempotent, never throws. */
    fun release() {
        sender?.close()
        scope.coroutineContext[Job]?.cancel()
        try {
            releaseRadio()
        } catch (failure: Throwable) {
            Log.w(AndroidBleLink.TAG, "Failure while releasing $role resources", failure)
        }
        adapterReceiver.unregister()
    }

    /** Runs a cleanup call, ignoring failures (adapter already off, permission revoked…). */
    protected inline fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (failure: Exception) {
            Log.d(AndroidBleLink.TAG, "$what failed during cleanup: $failure")
        }
    }
}
