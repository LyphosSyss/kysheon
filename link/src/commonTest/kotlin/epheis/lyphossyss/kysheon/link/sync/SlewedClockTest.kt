package epheis.lyphossyss.kysheon.link.sync

import epheis.lyphossyss.kysheon.link.time.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlewedClockTest {

    private var local = 0L
    private val clock = SlewedClock(MonotonicClock { local }, maxSlewRate = 0.05)

    @Test
    fun snapAppliesTheOffsetAtOnce() {
        clock.snapTo(1_000_000)
        assertEquals(1_000_000, clock.nowMicros())
        local = 10
        assertEquals(1_000_010, clock.nowMicros())
    }

    @Test
    fun targetIsReachedAtTheMaximumSlewRate() {
        clock.snapTo(0)
        clock.setTarget(10_000)
        local = 100_000
        assertEquals(105_000, clock.nowMicros(), "5 % of 100 ms")
        local = 200_000
        assertEquals(210_000, clock.nowMicros(), "target reached after 200 ms")
        local = 300_000
        assertEquals(310_000, clock.nowMicros(), "then runs at rate 1")
    }

    @Test
    fun neverJumpsNorGoesBackwardsEvenForALargeNegativeCorrection() {
        clock.snapTo(0)
        clock.setTarget(-50_000)
        var previous = clock.nowMicros()
        repeat(2_000) {
            local += 1_000
            val now = clock.nowMicros()
            val step = now - previous
            assertTrue(step in 950..1_050, "step $step µs for 1 000 µs of local time")
            previous = now
        }
        assertEquals(-50_000, clock.appliedOffsetMicros)
    }
}
