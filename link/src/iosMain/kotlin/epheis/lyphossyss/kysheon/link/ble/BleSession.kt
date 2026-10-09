package epheis.lyphossyss.kysheon.link.ble

import epheis.lyphossyss.kysheon.link.LinkDiagnostics
import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreBluetooth.CBManagerStatePoweredOff
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.Foundation.NSError
import kotlin.time.Duration

/** Tunables of [IosBleLink], validated once in its constructor. */
internal class BleTunables(
    val scanWindow: Duration,
    val scanTimeout: Duration,
    val connectTimeout: Duration,
    val adapterTimeout: Duration,
    val maxQueuedFrames: Int,
)

/** What a role session may do to the [IosBleLink] that owns it. Main thread only. */
internal interface LinkSink {
    val state: LinkState
    fun setState(state: LinkState)
    fun deliver(frame: ByteArray)
    fun updateDiagnostics(update: (LinkDiagnostics) -> LinkDiagnostics)
}

/**
 * One use of the radio in one role, from `host()`/`join()` to `close()` or the first failure. Never reused:
 * [IosBleLink] creates a new session each time, so a late callback from an old manager can only reach a released
 * session, which ignores it.
 *
 * Threading: every CoreBluetooth delegate is bound to the main queue and every coroutine launched here runs on
 * [Dispatchers.Main] (the main queue too), so all mutable state below is confined to the main thread without locks.
 * Delegate callbacks hand data to the coroutine world with non-suspending `trySend`/`complete` only.
 */
internal abstract class BleSession(
    protected val sink: LinkSink,
    private val scope: CoroutineScope,
    protected val tunables: BleTunables,
) {
    /** Completed with true when `host()`/`join()` should return success, false on failure or release. */
    val outcome: CompletableDeferred<Boolean> = CompletableDeferred()

    var isReleased: Boolean = false
        private set

    private val jobs = ArrayList<Job>()
    private val outbox = Channel<ByteArray>(tunables.maxQueuedFrames)
    private var queued = 0
    private var knownPayload: Int? = null

    /**
     * Signalled by `peripheralIsReadyToSendWriteWithoutResponse:` (guest) or
     * `peripheralManagerIsReadyToUpdateSubscribers:` (host). Conflated: a stale signal only causes a re-check.
     */
    protected val writeReady: Channel<Unit> = Channel(Channel.CONFLATED)

    /** True once frames may flow. */
    protected abstract val isConnected: Boolean

    /** Creates the manager. Must not suspend: the outcome arrives through delegate callbacks. */
    abstract fun start()

    /** Stops scanning/advertising, drops the connection, detaches delegates. Must tolerate any phase. */
    protected abstract fun releaseRadio()

    /**
     * Hands one frame to CoreBluetooth, suspending on [writeReady] while its transmit queue is full.
     * Returns false when the frame had to be dropped (too long for the negotiated payload).
     */
    protected abstract suspend fun writeFrame(frame: ByteArray): Boolean

    fun send(frame: ByteArray) {
        if (isReleased || !isConnected) {
            sink.updateDiagnostics { it.copy(framesDropped = it.framesDropped + 1) }
            return
        }
        if (outbox.trySend(frame.copyOf()).isSuccess) {
            queued++
            val depth = queued
            sink.updateDiagnostics { if (depth > it.maxQueueDepth) it.copy(maxQueueDepth = depth) else it }
        } else {
            // Full queue: the peer cannot keep up. Dropping keeps send() non-blocking for the session loop.
            sink.updateDiagnostics { it.copy(framesDropped = it.framesDropped + 1) }
        }
    }

    /** Idempotent. Does not touch [LinkState]: the caller decides between Idle (close) and Lost (failure). */
    fun release() {
        if (isReleased) return
        isReleased = true
        jobs.forEach { it.cancel() }
        jobs.clear()
        outbox.close()
        try {
            releaseRadio()
        } catch (t: Throwable) {
            // Best effort: the managers are dropped right after anyway.
            noteInternalError(t)
        }
        outcome.complete(false)
    }

    /** Reports [reason] through [LinkState.Lost] and frees the radio. No-op once released. */
    fun fail(reason: LostReason, error: NSError? = null) {
        if (isReleased) return
        val code = error?.platformCode
        if (code != null) sink.updateDiagnostics { it.copy(lastPlatformCode = code) }
        sink.setState(LinkState.Lost(reason, code))
        release()
    }

    /** A bug on our side (an exception in a callback). Never propagated to Objective-C, which would abort. */
    fun onInternalError(t: Throwable) {
        try {
            noteInternalError(t)
            fail(if (isConnected) LostReason.PeerDisconnected else LostReason.ConnectionFailed)
        } catch (_: Throwable) {
            // Nothing sensible left to do; swallowing is better than crashing inside a CoreBluetooth callback.
        }
    }

    /** Records an error that does not end the session (e.g. a failed notification). */
    protected fun noteError(error: NSError) {
        val code = error.platformCode
        sink.updateDiagnostics { it.copy(lastPlatformCode = code) }
    }

    /** Maps the adapter states that end a session; null for `poweredOn`, `unknown` and `resetting`. */
    protected fun adapterFailure(state: Long): LostReason? = when (state) {
        CBManagerStatePoweredOff -> LostReason.BluetoothOff
        CBManagerStateUnauthorized -> LostReason.PermissionDenied
        CBManagerStateUnsupported -> LostReason.Unsupported
        else -> null
    }

    /**
     * Fails with BluetoothOff if the adapter is still not `poweredOn` after [BleTunables.adapterTimeout], except
     * while the system permission prompt is on screen (authorization still undetermined): the user may take longer.
     */
    protected fun armAdapterTimer(isWaiting: () -> Boolean) {
        launchOnMain {
            delay(tunables.adapterTimeout)
            while (isWaiting() && CBManager.authorization() == CBManagerAuthorizationNotDetermined) {
                delay(tunables.adapterTimeout)
            }
            if (isWaiting()) fail(LostReason.BluetoothOff)
        }
    }

    /** Runs [block] after [after] unless the session is released first. */
    protected fun schedule(after: Duration, block: () -> Unit): Job = launchOnMain {
        delay(after)
        if (!isReleased) block()
    }

    protected fun startWritePump() {
        launchOnMain {
            for (frame in outbox) {
                val written = writeFrame(frame)
                queued--
                sink.updateDiagnostics {
                    if (written) it.copy(framesSent = it.framesSent + 1) else it.copy(framesDropped = it.framesDropped + 1)
                }
            }
        }
    }

    /**
     * Effective ATT payload for one frame (what CoreBluetooth reports). Published as `mtu = payload + 3` because
     * [LinkDiagnostics.mtu] follows the Android notion of ATT MTU; iOS never exposes the MTU itself.
     */
    protected fun publishPayload(bytes: Int) {
        if (bytes == knownPayload) return
        knownPayload = bytes
        sink.updateDiagnostics { it.copy(mtu = bytes + ATT_HEADER_BYTES) }
    }

    private fun noteInternalError(t: Throwable) {
        val name = t::class.simpleName ?: "Throwable"
        sink.updateDiagnostics { it.copy(platformInfo = "${it.platformInfo} · internal error: $name") }
    }

    private fun launchOnMain(block: suspend () -> Unit): Job {
        val job = scope.launch(Dispatchers.Main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                onInternalError(t)
            }
        }
        jobs += job
        return job
    }
}

/** Runs a delegate callback body; any exception becomes a session failure instead of crossing into Objective-C. */
internal inline fun BleSession.guarded(block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        onInternalError(t)
    }
}
