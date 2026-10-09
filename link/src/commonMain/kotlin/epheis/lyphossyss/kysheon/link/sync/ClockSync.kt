package epheis.lyphossyss.kysheon.link.sync

import epheis.lyphossyss.kysheon.link.protocol.Message
import epheis.lyphossyss.kysheon.link.time.MonotonicClock

/**
 * One PING/PONG exchange, in microseconds. [t0] and [t3] are on the local clock (ping sent, pong received),
 * [t1] and [t2] on the remote clock (ping received, pong sent).
 */
data class SyncSample(val t0: Long, val t1: Long, val t2: Long, val t3: Long) {

    /** Round trip minus the remote processing time. */
    val rttMicros: Long get() = (t3 - t0) - (t2 - t1)

    /**
     * Estimated `remote − local`. Exact when both one-way delays are equal; otherwise the error is bounded
     * by [rttMicros] / 2, which is why the sample with the smallest RTT is the most trustworthy.
     */
    val offsetMicros: Long get() = ((t1 - t0) + (t2 - t3)) / 2
}

/** Keeps the smallest-RTT sample: the one whose offset error bound is the tightest. */
object OffsetEstimator {
    fun best(samples: Iterable<SyncSample>): SyncSample? =
        samples.filter { it.rttMicros >= 0 }.minByOrNull { it.rttMicros }
}

/**
 * Pinger side bookkeeping. PONG does not echo `t0` (frame size limit), so `t0` is kept here, indexed by the
 * 8-bit sequence number. A ping left unanswered when its sequence number comes back, or answered after
 * [timeoutMicros], counts as lost.
 */
class PingTracker(
    private val clock: MonotonicClock,
    private val timeoutMicros: Long = 2_000_000,
) {
    private val sentAt = LongArray(SEQ_SPACE) { NONE }
    private var nextSeq = 0

    var lost: Long = 0
        private set

    fun nextPing(): Message.Ping {
        val seq = nextSeq
        nextSeq = (nextSeq + 1) % SEQ_SPACE
        if (sentAt[seq] != NONE) lost++
        val t0 = clock.nowMicros()
        sentAt[seq] = t0
        return Message.Ping(seq, t0)
    }

    /** Returns the completed sample, or null for an unknown, duplicate, late or inconsistent PONG. */
    fun onPong(pong: Message.Pong, t3: Long = clock.nowMicros()): SyncSample? {
        val t0 = sentAt[pong.seq]
        if (t0 == NONE) return null
        sentAt[pong.seq] = NONE
        if (t3 - t0 > timeoutMicros) {
            lost++
            return null
        }
        val sample = SyncSample(t0, pong.t1Micros, pong.t2Micros, t3)
        return if (sample.rttMicros < 0) null else sample
    }

    private companion object {
        const val SEQ_SPACE = 256
        const val NONE = Long.MIN_VALUE
    }
}
