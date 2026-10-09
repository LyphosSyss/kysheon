package epheis.lyphossyss.kysheon.core

import epheis.lyphossyss.kysheon.core.Collision.NO_HIT
import epheis.lyphossyss.kysheon.core.Collision.sweptCircleTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollisionTest {

    @Test
    fun headOnHitReturnsEntryFraction() {
        // From x = -2 to x = 2 through a unit circle at the origin: enters at x = -1, i.e. t = 0.25.
        assertEquals(0.25, sweptCircleTime(-2.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun nearMissIsNotAHit() {
        assertEquals(NO_HIT, sweptCircleTime(-2.0, 1.0001, 2.0, 1.0001, 0.0, 0.0, 1.0))
    }

    @Test
    fun tangentCountsAsHit() {
        // Discriminant is exactly 0 in floating point: touches the circle at (0, 1), t = 0.5.
        assertEquals(0.5, sweptCircleTime(-2.0, 1.0, 2.0, 1.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun segmentEndingBeforeCircleIsNotAHit() {
        assertEquals(NO_HIT, sweptCircleTime(-3.0, 0.0, -1.5, 0.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun segmentEndingExactlyOnCircleIsAHit() {
        assertEquals(1.0, sweptCircleTime(-3.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun movingAwayFromCircleIsNotAHit() {
        assertEquals(NO_HIT, sweptCircleTime(-2.0, 0.0, -4.0, 0.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun startInsideReturnsZero() {
        assertEquals(0.0, sweptCircleTime(0.5, 0.0, 5.0, 0.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun highSpeedSegmentCannotTunnel() {
        // One step covers 1000 radii: both endpoints are far outside, the segment still crosses the circle.
        val t = sweptCircleTime(-500.0, 0.0, 500.0, 0.0, 0.0, 0.0, 0.5)
        assertTrue(t in 0.0..1.0)
        assertEquals(0.4995, t, 1e-12)
        // Same check at game scale: a projectile jumping over a tiny fighter in one step.
        assertTrue(Collision.segmentHitsCircle(0.9, 0.3, 0.0, 0.3, 0.1, 0.31, 0.02))
    }

    @Test
    fun zeroLengthSegmentIsPointTest() {
        assertEquals(0.0, sweptCircleTime(0.2, 0.2, 0.2, 0.2, 0.0, 0.0, 1.0))
        assertEquals(NO_HIT, sweptCircleTime(2.0, 0.0, 2.0, 0.0, 0.0, 0.0, 1.0))
        assertFalse(Collision.segmentHitsCircle(1.0001, 0.0, 1.0001, 0.0, 0.0, 0.0, 1.0))
    }

    @Test
    fun diagonalHitIsSymmetric() {
        val a = sweptCircleTime(-2.0, -2.0, 2.0, 2.0, 0.0, 0.0, 1.0)
        val b = sweptCircleTime(2.0, 2.0, -2.0, -2.0, 0.0, 0.0, 1.0)
        assertEquals(a, b)
        assertTrue(a > 0.0 && a < 0.5)
    }
}
