package epheis.lyphossyss.kysheon.link.probe

import epheis.lyphossyss.kysheon.link.memory.LinkConditions
import epheis.lyphossyss.kysheon.link.memory.MemoryLinkPair
import epheis.lyphossyss.kysheon.link.protocol.Role
import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class LatencyProbeTest {

    private val trueOffset = 123_456_789L

    private fun TestScope.trueMicros() = testScheduler.currentTime * 1_000

    /** Starts both probes over a connected MemoryLink; the host clock is shifted by [trueOffset] and may drift. */
    private suspend fun TestScope.startProbes(
        conditions: LinkConditions,
        hostDriftPpm: Double = 0.0,
    ): Pair<LatencyProbe, LatencyProbe> {
        val pair = MemoryLinkPair(backgroundScope, MonotonicClock { trueMicros() }, conditions, Random(11))
        pair.host.host()
        assertTrue(pair.guest.join())
        val hostClock = MonotonicClock { (trueMicros() * (1.0 + hostDriftPpm / 1_000_000.0)).toLong() + trueOffset }
        val guestClock = MonotonicClock { trueMicros() }
        val hostProbe = LatencyProbe(pair.host, hostClock, Role.Host)
        val guestProbe = LatencyProbe(pair.guest, guestClock, Role.Guest)
        backgroundScope.launch { hostProbe.run() }
        backgroundScope.launch { guestProbe.run() }
        runCurrent()
        return hostProbe to guestProbe
    }

    @Test
    fun measuresRttAndOffsetUnderLatencyAndJitter() = runTest {
        val (hostProbe, guestProbe) = startProbes(LinkConditions(latency = 50.milliseconds, jitter = 20.milliseconds))
        advanceTimeBy(40_000)
        runCurrent()

        val guest = guestProbe.stats.value
        assertEquals(300, guest.samples, "rolling window full")
        assertEquals(0, guest.lostPings)
        assertEquals(1, guest.peerVersion)
        assertFalse(guest.incompatiblePeer)
        val rttMin = assertNotNull(guest.rttMinMicros)
        val rttMax = assertNotNull(guest.rttMaxMicros)
        assertTrue(rttMin >= 100_000 && rttMax <= 142_000, "rtt in [$rttMin, $rttMax]")
        assertTrue(assertNotNull(guest.rttP50Micros) in rttMin..rttMax)
        assertTrue(assertNotNull(guest.jitterMicros) in 1..40_000)

        val offset = assertNotNull(guest.offsetMicros)
        val bound = assertNotNull(guest.offsetBoundMicros)
        assertTrue(abs(offset - trueOffset) <= bound, "guest offset error ${offset - trueOffset} above $bound")
        assertTrue(abs(offset - trueOffset) <= 10_000, "guest offset error ${offset - trueOffset} above jitter / 2")

        val hostOffset = assertNotNull(hostProbe.stats.value.offsetMicros)
        assertTrue(abs(hostOffset + trueOffset) <= 10_000, "host sees the opposite offset, got $hostOffset")
    }

    @Test
    fun measuresClockDrift() = runTest {
        val (_, guestProbe) = startProbes(LinkConditions(latency = 20.milliseconds), hostDriftPpm = 50.0)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(null, guestProbe.stats.value.driftPpm, "not reported before 30 s")

        advanceTimeBy(20_000)
        runCurrent()
        val stats = guestProbe.stats.value
        val drift = assertNotNull(stats.driftPpm)
        val bound = assertNotNull(stats.driftBoundPpm)
        assertTrue(abs(drift - 50.0) < 2.0, "drift $drift ppm")
        // Worst case: two half-RTTs (20 ms each) over ~39 s, about 1 000 ppm. Honest, and very pessimistic here.
        assertTrue(bound > 500.0 && abs(drift - 50.0) <= bound, "bound $bound ppm")
    }

    @Test
    fun countsLostPings() = runTest {
        val (_, guestProbe) = startProbes(LinkConditions(latency = 10.milliseconds, lossRate = 0.1))
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(guestProbe.stats.value.lostPings > 0)
    }

    @Test
    fun flagsAnIncompatiblePeer() = runTest {
        val pair = MemoryLinkPair(backgroundScope, MonotonicClock { trueMicros() })
        pair.host.host()
        assertTrue(pair.guest.join())
        val probe = LatencyProbe(pair.guest, MonotonicClock { trueMicros() }, Role.Guest)
        backgroundScope.launch { probe.run() }
        runCurrent()

        pair.host.send(byteArrayOf(1, 2, 0, 0x07, 0xD0.toByte(), 42))
        advanceTimeBy(100)
        runCurrent()

        assertTrue(probe.stats.value.incompatiblePeer)
        assertEquals(2, probe.stats.value.peerVersion)
    }
}
