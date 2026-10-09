package epheis.lyphossyss.kysheon.link.probe

import epheis.lyphossyss.kysheon.link.Link
import epheis.lyphossyss.kysheon.link.protocol.DecodeResult
import epheis.lyphossyss.kysheon.link.protocol.Message
import epheis.lyphossyss.kysheon.link.protocol.MessageCodec
import epheis.lyphossyss.kysheon.link.protocol.PROTOCOL_VERSION
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.sync.OffsetEstimator
import epheis.lyphossyss.kysheon.link.sync.PingTracker
import epheis.lyphossyss.kysheon.link.sync.SyncSample
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Measurements of a link, all in microseconds. Null fields mean "not enough samples yet".
 *
 * - [jitterMicros]: mean absolute difference between consecutive RTTs (RFC 3550 spirit, applied to the RTT).
 * - [offsetMicros]: estimated `peer clock − local clock`, from the smallest-RTT sample of the last burst;
 *   its error is at most [offsetBoundMicros].
 * - [driftPpm]: how fast the offset moves (crystal drift between the two phones), from the first burst to now.
 */
data class ProbeStats(
    val elapsedMicros: Long = 0,
    val samples: Int = 0,
    val lostPings: Long = 0,
    val rttMinMicros: Long? = null,
    val rttP50Micros: Long? = null,
    val rttP95Micros: Long? = null,
    val rttMaxMicros: Long? = null,
    val jitterMicros: Long? = null,
    val offsetMicros: Long? = null,
    val offsetBoundMicros: Long? = null,
    val driftPpm: Double? = null,
    val peerVersion: Int? = null,
    val incompatiblePeer: Boolean = false,
    val malformedFrames: Long = 0,
)

/**
 * Latency probe: pings the peer at a steady rate, answers its pings, and publishes [stats].
 * Runs the real protocol (HELLO, PING/PONG, HEARTBEAT) over any [Link]; used on phones to measure BLE
 * before the game exists, and in tests over a MemoryLink.
 */
class LatencyProbe(
    private val link: Link,
    private val clock: MonotonicClock,
    private val role: Role,
    private val aspectMilli: Int = 2000,
    private val pingInterval: Duration = 100.milliseconds,
    private val windowSize: Int = 300,
    private val burstSize: Int = 8,
) {
    private val tracker = PingTracker(clock)
    private val rtts = ArrayDeque<Long>()
    private val burst = ArrayDeque<SyncSample>()
    private var firstBurstBest: SyncSample? = null
    private var previousRtt: Long? = null
    private var jitterSum = 0L
    private var jitterCount = 0
    private var startedAt = 0L

    private val mutableStats = MutableStateFlow(ProbeStats())
    val stats: StateFlow<ProbeStats> = mutableStats.asStateFlow()

    /**
     * Runs until cancelled. The link must already be Connected. Not thread-safe: run it on a single-threaded
     * dispatcher (Main on device, the test dispatcher in tests).
     */
    suspend fun run() = coroutineScope {
        startedAt = clock.nowMicros()
        launch { link.incoming.collect(::onFrame) }
        link.send(MessageCodec.encode(Message.Hello(PROTOCOL_VERSION, role, aspectMilli)))
        launch {
            var heartbeatSeq = 0
            while (true) {
                delay(1.seconds)
                link.send(MessageCodec.encode(Message.Heartbeat(heartbeatSeq)))
                heartbeatSeq = (heartbeatSeq + 1) % 256
            }
        }
        while (true) {
            link.send(MessageCodec.encode(tracker.nextPing()))
            publish()
            delay(pingInterval)
        }
    }

    private suspend fun onFrame(frame: ByteArray) {
        val receivedAt = clock.nowMicros()
        when (val decoded = MessageCodec.decode(frame)) {
            is DecodeResult.Ok -> when (val message = decoded.message) {
                is Message.Ping -> {
                    val pong = Message.Pong(message.seq, t1Micros = receivedAt, t2Micros = clock.nowMicros())
                    link.send(MessageCodec.encode(pong))
                }
                is Message.Pong -> tracker.onPong(message, receivedAt)?.let(::record)
                is Message.Hello -> mutableStats.value = mutableStats.value.copy(peerVersion = message.protocolVersion)
                else -> Unit
            }
            is DecodeResult.IncompatibleVersion -> mutableStats.value =
                mutableStats.value.copy(peerVersion = decoded.remoteVersion, incompatiblePeer = true)
            is DecodeResult.Malformed -> mutableStats.value =
                mutableStats.value.copy(malformedFrames = mutableStats.value.malformedFrames + 1)
        }
    }

    private fun record(sample: SyncSample) {
        val rtt = sample.rttMicros
        rtts.addLast(rtt)
        if (rtts.size > windowSize) rtts.removeFirst()
        previousRtt?.let {
            jitterSum += abs(rtt - it)
            jitterCount++
        }
        previousRtt = rtt
        burst.addLast(sample)
        if (burst.size > burstSize) burst.removeFirst()
        if (firstBurstBest == null && burst.size == burstSize) firstBurstBest = OffsetEstimator.best(burst)
        publish()
    }

    private fun publish() {
        val now = clock.nowMicros()
        val sorted = rtts.sorted()
        val best = OffsetEstimator.best(burst)
        val first = firstBurstBest
        val driftPpm = if (first != null && best != null && best.t0 - first.t0 >= DRIFT_MIN_SPAN_MICROS) {
            (best.offsetMicros - first.offsetMicros).toDouble() / (best.t0 - first.t0) * 1_000_000.0
        } else {
            null
        }
        mutableStats.value = mutableStats.value.copy(
            elapsedMicros = now - startedAt,
            samples = rtts.size,
            lostPings = tracker.lost,
            rttMinMicros = sorted.firstOrNull(),
            rttP50Micros = sorted.percentile(0.50),
            rttP95Micros = sorted.percentile(0.95),
            rttMaxMicros = sorted.lastOrNull(),
            jitterMicros = if (jitterCount > 0) jitterSum / jitterCount else null,
            offsetMicros = best?.offsetMicros,
            offsetBoundMicros = best?.let { it.rttMicros / 2 },
            driftPpm = driftPpm,
        )
    }

    private fun List<Long>.percentile(p: Double): Long? =
        if (isEmpty()) null else this[((size - 1) * p).toInt()]

    private companion object {
        const val DRIFT_MIN_SPAN_MICROS = 5_000_000L
    }
}
