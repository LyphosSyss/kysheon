package epheis.lyphossyss.kysheon.link.memory

import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.LinkState
import epheis.lyphossyss.kysheon.link.LostReason
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Network conditions applied to every frame, in both directions.
 *
 * @property latency fixed one-way delay.
 * @property jitter extra one-way delay drawn uniformly in `[0, jitter]`.
 * @property lossRate probability that a frame is dropped. Real BLE does not lose frames inside a connection
 *   (link-layer retransmission), so this exists to test robustness, not to model BLE.
 * @property preserveOrder BLE delivers in order: a frame never overtakes a previous one. Disable to stress the protocol.
 */
data class LinkConditions(
    val latency: Duration = Duration.ZERO,
    val jitter: Duration = Duration.ZERO,
    val lossRate: Double = 0.0,
    val preserveOrder: Boolean = true,
) {
    init {
        require(!latency.isNegative() && !jitter.isNegative()) { "latency and jitter must be positive" }
        require(lossRate in 0.0..1.0) { "lossRate must be in [0, 1]: $lossRate" }
    }
}

/**
 * Two [Link]s connected in memory: [host] and [guest]. Time is whatever drives [scope] and [clock]:
 * in tests, pass `backgroundScope` and a clock reading the test scheduler, and everything runs on virtual time.
 *
 * Fault injection: [conditions] (latency, jitter, loss), [silenced] (connected but mute, to test the heartbeat)
 * and [cut] (Bluetooth switched off on one side).
 */
class MemoryLinkPair(
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    conditions: LinkConditions = LinkConditions(),
    private val random: Random = Random(0),
    private val scanTimeout: Duration = 5.seconds,
) {
    var conditions: LinkConditions = conditions

    /** While true, every frame is dropped and both sides stay Connected. */
    var silenced: Boolean = false

    val host: MemoryLink = MemoryLink(Role.Host)
    val guest: MemoryLink = MemoryLink(Role.Guest)

    fun link(role: Role): MemoryLink = if (role == Role.Host) host else guest

    /**
     * Switches Bluetooth off on [side]: it becomes `Lost(BluetoothOff)` at once. The peer notices after
     * [peerNoticesAfter] (a BLE supervision timeout), or never when null: then only the heartbeat can tell.
     */
    fun cut(side: Role, peerNoticesAfter: Duration? = null) {
        val local = link(side)
        val peer = link(side.opposite)
        local.mutableState.value = LinkState.Lost(LostReason.BluetoothOff)
        if (peerNoticesAfter != null) {
            scope.launch {
                delay(peerNoticesAfter)
                if (peer.mutableState.value == LinkState.Connected) {
                    peer.mutableState.value = LinkState.Lost(LostReason.PeerDisconnected)
                }
            }
        }
    }

    inner class MemoryLink internal constructor(val role: Role) : Link {
        internal val mutableState = MutableStateFlow<LinkState>(LinkState.Idle)
        private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
        private val outbox = Channel<Pending>(Channel.UNLIMITED)

        /** Frames dropped by this side's sender (not connected, silenced or lost). */
        var droppedFrames: Int = 0
            private set

        override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()
        override val state: StateFlow<LinkState> = mutableState.asStateFlow()

        private val peer: MemoryLink get() = link(role.opposite)

        init {
            // Ordered delivery: one consumer per direction, so a frame never overtakes the previous one.
            scope.launch {
                for (pending in outbox) {
                    val wait = pending.deliverAtMicros - clock.nowMicros()
                    if (wait > 0) delay(wait.microseconds)
                    peer.deliver(pending.frame)
                }
            }
        }

        override suspend fun host() {
            check(role == Role.Host) { "host() called on the guest side" }
            if (canStart()) mutableState.value = LinkState.Advertising
        }

        override suspend fun join(): Boolean {
            check(role == Role.Guest) { "join() called on the host side" }
            if (!canStart()) return mutableState.value == LinkState.Connected
            mutableState.value = LinkState.Scanning
            val found = withTimeoutOrNull(scanTimeout) {
                peer.state.first { it == LinkState.Advertising }
            }
            if (found == null) {
                mutableState.value = LinkState.Lost(LostReason.PeerNotFound)
                return false
            }
            mutableState.value = LinkState.Connecting
            delay(conditions.latency)
            if (peer.mutableState.value != LinkState.Advertising || mutableState.value != LinkState.Connecting) {
                if (mutableState.value == LinkState.Connecting) {
                    mutableState.value = LinkState.Lost(LostReason.ConnectionFailed)
                }
                return false
            }
            peer.mutableState.value = LinkState.Connected
            mutableState.value = LinkState.Connected
            return true
        }

        override suspend fun send(frame: ByteArray) {
            val current = conditions
            val dropped = mutableState.value != LinkState.Connected ||
                silenced ||
                (current.lossRate > 0.0 && random.nextDouble() < current.lossRate)
            if (dropped) {
                droppedFrames++
                return
            }
            val jitterMicros = current.jitter.inWholeMicroseconds
            val delayMicros = current.latency.inWholeMicroseconds +
                if (jitterMicros > 0) random.nextLong(jitterMicros + 1) else 0L
            val pending = Pending(frame.copyOf(), clock.nowMicros() + delayMicros)
            if (current.preserveOrder) {
                outbox.send(pending)
            } else {
                scope.launch {
                    delay(delayMicros.microseconds)
                    peer.deliver(pending.frame)
                }
            }
        }

        override suspend fun close() {
            val wasConnected = mutableState.value == LinkState.Connected
            mutableState.value = LinkState.Idle
            if (wasConnected && peer.mutableState.value == LinkState.Connected) {
                peer.mutableState.value = LinkState.Lost(LostReason.PeerDisconnected)
            }
        }

        private fun canStart(): Boolean = when (mutableState.value) {
            LinkState.Idle, is LinkState.Lost -> true
            else -> false
        }

        private fun deliver(frame: ByteArray) {
            if (mutableState.value == LinkState.Connected) inbox.trySend(frame)
        }
    }

    private class Pending(val frame: ByteArray, val deliverAtMicros: Long)
}
