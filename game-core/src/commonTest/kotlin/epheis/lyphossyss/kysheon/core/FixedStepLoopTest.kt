package epheis.lyphossyss.kysheon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixedStepLoopTest {

    @Test
    fun firstCallOnlyStarts() {
        val loop = FixedStepLoop()
        assertEquals(0, loop.advanceTo(1_000_000L) { error("no step expected") })
        assertEquals(0.0, loop.alpha)
    }

    @Test
    fun runsStepsForElapsedTime() {
        val loop = FixedStepLoop(stepMicros = 16_667L)
        loop.reset(0L)
        var ran = 0
        assertEquals(0, loop.advanceTo(16_666L) { ran++ })
        assertEquals(1, loop.advanceTo(16_667L) { ran++ })
        assertEquals(2, loop.advanceTo(50_001L) { ran++ })
        assertEquals(3, ran)
        assertEquals(3L, loop.stepCount)
        assertEquals(0.0, loop.alpha)
    }

    @Test
    fun catchUpIsCappedAndExcessIsCounted() {
        val loop = FixedStepLoop(stepMicros = 10_000L, maxStepsPerAdvance = 5)
        loop.reset(0L)
        var ran = 0
        // 1 s stall = 100 steps worth of time, plus 2.5 ms remainder.
        assertEquals(5, loop.advanceTo(1_002_500L) { ran++ })
        assertEquals(5, ran)
        assertEquals(95L, loop.droppedSteps)
        assertEquals(0.25, loop.alpha)
        // The loop resumes normally afterwards (no spiral).
        assertEquals(1, loop.advanceTo(1_012_500L) { ran++ })
        assertEquals(0L, loop.droppedSteps - 95L)
    }

    @Test
    fun alphaStaysInUnitRange() {
        val loop = FixedStepLoop(stepMicros = 16_667L)
        loop.reset(0L)
        var now = 0L
        repeat(5_000) { i ->
            now += 1_000L + (i * 7_919L) % 23_000L
            loop.advanceTo(now) {}
            val a = loop.alpha
            assertTrue(a >= 0.0 && a < 1.0, "alpha out of range: $a")
        }
    }

    @Test
    fun clockGoingBackwardsAddsNoTime() {
        val loop = FixedStepLoop(stepMicros = 10_000L)
        loop.reset(100_000L)
        assertEquals(0, loop.advanceTo(50_000L) {})
        assertEquals(1, loop.advanceTo(110_000L) {})
    }

    @Test
    fun noDriftOver10000Steps() {
        val loop = FixedStepLoop(stepMicros = 16_667L, maxStepsPerAdvance = 5)
        val start = 123_456_789L
        loop.reset(start)
        var ran = 0L
        var now = start
        // Irregular frame times (8 to 31 ms), never more than 5 steps at once.
        var i = 0
        while (ran < 10_000L) {
            now += 8_000L + (i * 4_513L) % 23_000L
            loop.advanceTo(now) { ran++ }
            i++
        }
        assertEquals(0L, loop.droppedSteps)
        assertEquals(ran, loop.stepCount)
        val elapsed = now - start
        assertEquals(elapsed / 16_667L, ran)
        assertEquals((elapsed % 16_667L).toDouble() / 16_667.0, loop.alpha)
    }
}
