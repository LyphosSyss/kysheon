package epheis.lyphossyss.kysheon.link.sync

import epheis.lyphossyss.kysheon.link.protocol.Message
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClockSyncTest {

    /** Builds the sample of one exchange with known one-way delays and a known true offset (remote − local). */
    private fun exchange(t0: Long, trueOffset: Long, forward: Long, back: Long, processing: Long = 0): SyncSample {
        val t1 = t0 + forward + trueOffset
        val t2 = t1 + processing
        val t3 = t2 - trueOffset + back
        return SyncSample(t0, t1, t2, t3)
    }

    @Test
    fun symmetricDelaysGiveTheExactOffset() {
        val sample = exchange(t0 = 1_000, trueOffset = 5_000, forward = 40, back = 40, processing = 10)
        assertEquals(80, sample.rttMicros)
        assertEquals(5_000, sample.offsetMicros)
    }

    @Test
    fun asymmetricDelaysStayWithinHalfTheRtt() {
        val sample = exchange(t0 = 1_000, trueOffset = 5_000, forward = 70, back = 10)
        assertEquals(80, sample.rttMicros)
        assertEquals(5_030, sample.offsetMicros)
        assertTrue(abs(sample.offsetMicros - 5_000) <= sample.rttMicros / 2)
    }

    @Test
    fun handlesHugeAndNegativeOffsets() {
        val offset = -3_000_000_000_000L
        val sample = exchange(t0 = 7_000_000_000L, trueOffset = offset, forward = 15_000, back = 15_000)
        assertEquals(offset, sample.offsetMicros)
    }

    @Test
    fun estimatorKeepsTheSmallestRttAndIgnoresInconsistentSamples() {
        val slow = exchange(0, 1_000, forward = 50_000, back = 10_000)
        val fast = exchange(0, 1_000, forward = 9_000, back = 11_000)
        val broken = SyncSample(t0 = 100, t1 = 0, t2 = 0, t3 = 0)
        assertEquals(fast, OffsetEstimator.best(listOf(slow, broken, fast)))
        assertNull(OffsetEstimator.best(listOf(broken)))
        assertNull(OffsetEstimator.best(emptyList()))
    }

    @Test
    fun burstOfEightUnderJitterBoundsTheError() {
        val random = Random(2026)
        val trueOffset = 123_456_789L
        val latency = 50_000L
        val jitter = 20_000L
        var worst = 0L
        repeat(1_000) { burstIndex ->
            val burst = List(8) { i ->
                exchange(
                    t0 = burstIndex * 5_000_000L + i * 100_000L,
                    trueOffset = trueOffset,
                    forward = latency + random.nextLong(jitter + 1),
                    back = latency + random.nextLong(jitter + 1),
                    processing = random.nextLong(500),
                )
            }
            val best = OffsetEstimator.best(burst)!!
            val error = abs(best.offsetMicros - trueOffset)
            assertTrue(error <= best.rttMicros / 2, "error $error above bound ${best.rttMicros / 2}")
            worst = maxOf(worst, error)
        }
        // Delays differ by at most the jitter, so the error never exceeds jitter / 2.
        assertTrue(worst <= jitter / 2, "worst error $worst")
    }

    @Test
    fun trackerMatchesPongsBySequenceAndComputesTheSample() {
        var now = 1_000L
        val tracker = PingTracker(MonotonicClock { now })
        val ping = tracker.nextPing()
        assertEquals(0, ping.seq)
        assertEquals(1_000, ping.t0Micros)

        val sample = tracker.onPong(Message.Pong(seq = 0, t1Micros = 9_020, t2Micros = 9_030), t3 = 1_090)!!
        assertEquals(SyncSample(1_000, 9_020, 9_030, 1_090), sample)
        assertEquals(80, sample.rttMicros)
        now = 2_000
        assertNull(tracker.onPong(Message.Pong(seq = 0, t1Micros = 0, t2Micros = 0)), "duplicate")
        assertNull(tracker.onPong(Message.Pong(seq = 5, t1Micros = 0, t2Micros = 0)), "never sent")
    }

    @Test
    fun trackerCountsTimeoutsAndUnansweredSequenceReuse() {
        var now = 0L
        val tracker = PingTracker(MonotonicClock { now }, timeoutMicros = 1_000)
        tracker.nextPing()
        now = 5_000
        assertNull(tracker.onPong(Message.Pong(seq = 0, t1Micros = 10, t2Micros = 10)))
        assertEquals(1, tracker.lost)

        repeat(256) { tracker.nextPing() }
        assertEquals(1, tracker.lost, "first lap: seq 1..255 then 0 again, all slots were free")
        tracker.nextPing()
        assertEquals(2, tracker.lost, "seq 1 reused while still unanswered")
    }

    @Test
    fun sequenceNumbersWrapAround() {
        val tracker = PingTracker(MonotonicClock { 0 })
        val seqs = List(300) { tracker.nextPing().seq }
        assertEquals(0, seqs[256])
        assertEquals(43, seqs[299])
    }
}
