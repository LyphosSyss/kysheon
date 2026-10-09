package epheis.lyphossyss.kysheon.link.ble

import epheis.lyphossyss.kysheon.link.DiagnosableLink
import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.LinkDiagnostics
import epheis.lyphossyss.kysheon.link.LinkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [Link] over CoreBluetooth. The same instance becomes host with [host] (peripheral role, `CBPeripheralManager`) or
 * guest with [join] (central role, `CBCentralManager`), and can be used again after [close] or a
 * [LinkState.Lost], in either role.
 *
 * Threading: every public method hops to the main thread ([Dispatchers.Main], i.e. the main dispatch queue), where
 * all CoreBluetooth delegates are also bound. Calling from Main (Compose, the session) costs no extra dispatch.
 * [scope] hosts the timers and the write pump; cancelling it stops them.
 *
 * Failures never throw: they end up in [state] as [LinkState.Lost] with the `NSError` code when there is one.
 *
 * The app must declare `NSBluetoothAlwaysUsageDescription` in its Info.plist, otherwise iOS terminates it the first
 * time a manager is created. The first [host]/[join] shows the system permission prompt if it was never answered.
 *
 * Measured details (payload, counters) are in [diagnostics]: [LinkDiagnostics.mtu] is the effective payload
 * reported by iOS plus 3 bytes of ATT header, since iOS exposes the payload, never the MTU.
 *
 * @param scanWindow from the first scan result, how long to keep listening before connecting to the strongest RSSI.
 * @param scanTimeout without any result for that long after the adapter is ready → `Lost(PeerNotFound)`.
 * @param connectTimeout connection, discovery and subscription must finish within it → `Lost(ConnectionFailed)`.
 * @param adapterTimeout adapter still not `poweredOn` after that long (and no permission prompt on screen)
 *   → `Lost(BluetoothOff)`; also bounds the service registration on the host.
 * @param maxQueuedFrames frames waiting for the radio beyond that are dropped (counted in diagnostics).
 */
class IosBleLink(
    private val scope: CoroutineScope,
    scanWindow: Duration = 1500.milliseconds,
    scanTimeout: Duration = 10.seconds,
    connectTimeout: Duration = 10.seconds,
    adapterTimeout: Duration = 5.seconds,
    maxQueuedFrames: Int = 64,
) : Link, DiagnosableLink {

    init {
        require(scanWindow.isPositive() && scanTimeout.isPositive()) { "scan durations must be positive" }
        require(connectTimeout.isPositive() && adapterTimeout.isPositive()) { "timeouts must be positive" }
        require(maxQueuedFrames > 0) { "maxQueuedFrames must be positive: $maxQueuedFrames" }
    }

    private val tunables = BleTunables(scanWindow, scanTimeout, connectTimeout, adapterTimeout, maxQueuedFrames)

    private val mutableState = MutableStateFlow<LinkState>(LinkState.Idle)
    override val state: StateFlow<LinkState> = mutableState.asStateFlow()

    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    private val mutableDiagnostics = MutableStateFlow(LinkDiagnostics())
    override val diagnostics: StateFlow<LinkDiagnostics> = mutableDiagnostics.asStateFlow()

    /** Current role session; main thread only. A released session stays here until the next start or close. */
    private var session: BleSession? = null

    private val sink = object : LinkSink {
        override val state: LinkState get() = mutableState.value

        override fun setState(state: LinkState) {
            mutableState.value = state
        }

        override fun deliver(frame: ByteArray) {
            if (mutableState.value != LinkState.Connected) return
            inbox.trySend(frame)
            mutableDiagnostics.update { it.copy(framesReceived = it.framesReceived + 1) }
        }

        override fun updateDiagnostics(update: (LinkDiagnostics) -> LinkDiagnostics) {
            mutableDiagnostics.update(update)
        }
    }

    /** Returns once advertising has started (state Advertising) or failed (state Lost). No-op while a role is active. */
    override suspend fun host() {
        withContext(Dispatchers.Main.immediate) {
            startAndAwait { PeripheralHost(sink, scope, tunables) }
        }
    }

    /** Scans, connects to the strongest host and subscribes. True once Connected; false on Lost or [close]. */
    override suspend fun join(): Boolean = withContext(Dispatchers.Main.immediate) {
        startAndAwait { CentralGuest(sink, scope, tunables) }
    }

    /** Non-blocking: the frame is queued for the serialized write pump, or dropped (and counted) if not Connected. */
    override suspend fun send(frame: ByteArray) {
        withContext(Dispatchers.Main.immediate) {
            val current = session
            if (current == null) {
                mutableDiagnostics.update { it.copy(framesDropped = it.framesDropped + 1) }
            } else {
                current.send(frame)
            }
        }
    }

    /** Idempotent, and runs even from a cancelled coroutine (e.g. a `finally` block). */
    override suspend fun close() {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            session?.release()
            session = null
            mutableState.value = LinkState.Idle
        }
    }

    private suspend fun startAndAwait(create: () -> BleSession): Boolean {
        val active = session?.takeUnless { it.isReleased }
        if (active != null) return mutableState.value == LinkState.Connected
        // Frames left over from a previous session must not leak into the new one.
        while (inbox.tryReceive().isSuccess) {
            // Discard stale frames.
        }
        mutableDiagnostics.value = LinkDiagnostics()
        mutableState.value = LinkState.Idle
        val created = create()
        session = created
        try {
            created.start()
            return created.outcome.await()
        } catch (e: CancellationException) {
            // The caller gave up (screen left, timeout): do not leave the radio scanning or advertising behind.
            if (!created.outcome.isCompleted && session === created) {
                created.release()
                session = null
                mutableState.value = LinkState.Idle
            }
            throw e
        } catch (t: Throwable) {
            created.onInternalError(t)
            return false
        }
    }
}
