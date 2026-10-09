package epheis.lyphossyss.kysheon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HalfSimulationTest {

    private val aspect = 2.0

    @Test
    fun fighterStandsNearOuterEdge() {
        assertEquals(0.10, HalfSimulation(Side.Left, aspect).fighterX)
        assertEquals(0.90, HalfSimulation(Side.Right, aspect).fighterX)
        assertEquals(1.0, HalfSimulation(Side.Left, aspect).fighterY)
    }

    @Test
    fun fighterMovesAtBoundedSpeedThenStopsOnTarget() {
        val sim = HalfSimulation(Side.Left, aspect)
        val perStep = sim.rules.fighterSpeed * sim.rules.stepSeconds
        sim.setFighterTarget(1.5)
        sim.step()
        assertEquals(1.0 + perStep, sim.fighterY, 1e-12)
        assertEquals(1.0, sim.fighterPrevY)
        assertEquals(1.0 + perStep * 0.5, sim.fighterInterpolatedY(0.5), 1e-12)
        repeat(100) { sim.step() }
        assertEquals(1.5, sim.fighterY)
        // Moving down is bounded too.
        sim.setFighterTarget(0.5)
        sim.step()
        assertEquals(1.5 - perStep, sim.fighterY, 1e-12)
    }

    @Test
    fun fighterTargetIsClampedInsideTheHalf() {
        val sim = HalfSimulation(Side.Right, aspect)
        val r = sim.rules.fighterRadius
        sim.setFighterTarget(10.0)
        assertEquals(aspect - r, sim.fighterTargetY)
        repeat(200) { sim.step() }
        assertEquals(aspect - r, sim.fighterY)
        sim.setFighterTarget(-3.0)
        repeat(200) { sim.step() }
        assertEquals(r, sim.fighterY)
        sim.setFighterTarget(Double.NaN)
        assertEquals(r, sim.fighterTargetY)
    }

    @Test
    fun fireRespectsCooldown() {
        val sim = HalfSimulation(Side.Left, aspect, Rules(fireCooldownSteps = 15))
        assertTrue(sim.fire())
        assertFalse(sim.fire())
        repeat(14) {
            sim.step()
            assertFalse(sim.fire(), "fire accepted after ${it + 1} steps")
        }
        sim.step()
        assertTrue(sim.fire())
        assertEquals(2, sim.localProjectiles.activeCount)
    }

    @Test
    fun fireRespectsCapacityAndFreesSlotsOnExit() {
        val sim = HalfSimulation(Side.Left, aspect, Rules(fireCooldownSteps = 0, maxLocalProjectiles = 3))
        assertTrue(sim.fire())
        assertTrue(sim.fire())
        assertTrue(sim.fire())
        assertFalse(sim.fire())
        assertEquals(3, sim.localProjectiles.activeCount)
        while (sim.events.count == 0) sim.step()
        assertEquals(0, sim.localProjectiles.activeCount)
        assertTrue(sim.fire())
    }

    @Test
    fun fireRejectsNonFiniteVerticalSpeed() {
        val sim = HalfSimulation(Side.Left, aspect)
        assertFalse(sim.fire(vy = Double.POSITIVE_INFINITY))
        assertTrue(sim.fire(vy = 0.0))
    }

    @Test
    fun projectileIdsWrapAroundU16() {
        val sim = HalfSimulation(Side.Left, aspect, Rules(fireCooldownSteps = 0, projectileSpeed = 30.0))
        repeat(65_536) {
            assertTrue(sim.fire())
            sim.step()
            sim.step()
            sim.events.clear()
        }
        assertEquals(65_535, sim.lastFiredId)
        assertTrue(sim.fire())
        assertEquals(0, sim.lastFiredId)
    }

    @Test
    fun leftProjectileExitsAtBorderWithNormalizedYAndVelocity() {
        val sim = HalfSimulation(Side.Left, aspect)
        sim.setFighterTarget(0.5)
        repeat(60) { sim.step() }
        assertTrue(sim.fire())
        val slot = 0
        assertEquals(sim.fighterX + sim.rules.fighterRadius, sim.localProjectiles.x(slot))
        var steps = 0
        while (sim.events.count == 0) {
            sim.step()
            steps++
            if (sim.localProjectiles.isActive(slot)) assertTrue(sim.localProjectiles.x(slot) < 1.0)
        }
        // From x = 0.16 to x = 1 at 1.2 widths/s: 0.7 s = 42 steps.
        assertEquals(42, steps)
        assertEquals(
            listOf(GameEvent.ProjectileExited(projectileId = 0, yNormalized = 0.25, vx = 1.2, vy = 0.0)),
            sim.events.toList(),
        )
        assertEquals(0, sim.localProjectiles.activeCount)
    }

    @Test
    fun rightProjectileExitsAtBorderWithNegativeVx() {
        val sim = HalfSimulation(Side.Right, aspect)
        sim.setFighterTarget(1.6)
        repeat(60) { sim.step() }
        assertTrue(sim.fire())
        while (sim.events.count == 0) {
            sim.step()
            if (sim.localProjectiles.isActive(0)) assertTrue(sim.localProjectiles.x(0) > 0.0)
        }
        val event = sim.events.toList().single() as GameEvent.ProjectileExited
        assertEquals(0, event.projectileId)
        assertEquals(0.8, event.yNormalized, 1e-12)
        assertEquals(-1.2, event.vx)
        assertEquals(0.0, event.vy)
    }

    @Test
    fun projectileBouncesOnWallsWithVerticalSpeed() {
        val a = 1.0
        val sim = HalfSimulation(Side.Left, a)
        val r = sim.rules.projectileRadius
        val vy = 2.0
        assertTrue(sim.fire(vy = vy))
        val x0 = sim.localProjectiles.x(0)
        val y0 = sim.localProjectiles.y(0)
        var sawDown = false
        while (sim.events.count == 0) {
            sim.step()
            if (!sim.localProjectiles.isActive(0)) break
            val y = sim.localProjectiles.y(0)
            assertTrue(y >= r && y <= a - r, "y out of walls: $y")
            if (sim.localProjectiles.vy(0) < 0.0) sawDown = true
        }
        assertTrue(sawDown, "never bounced")
        // Expected exit point: unfold the straight path, then reflect it back into [r, a − r].
        val t = (1.0 - x0) / sim.rules.projectileSpeed
        val (expectedY, expectedVy) = fold(y0 + vy * t, vy, r, a - r)
        val event = sim.events.toList().single() as GameEvent.ProjectileExited
        assertEquals(expectedY / a, event.yNormalized, 1e-9)
        assertEquals(expectedVy, event.vy)
        assertEquals(-2.0, event.vy)
    }

    @Test
    fun steepProjectileBouncesManyTimesInsideOneStep() {
        val a = 0.5
        val sim = HalfSimulation(Side.Right, a)
        val r = sim.rules.projectileRadius
        val vy = -30.0 // ~1 width per step against a 0.46-high corridor: 2 bounces per step
        assertTrue(sim.fire(vy = vy))
        val x0 = sim.localProjectiles.x(0)
        val y0 = sim.localProjectiles.y(0)
        while (sim.events.count == 0) {
            sim.step()
            if (sim.localProjectiles.isActive(0)) {
                val y = sim.localProjectiles.y(0)
                assertTrue(y >= r && y <= a - r, "y out of walls: $y")
            }
        }
        val t = (0.0 - x0) / -sim.rules.projectileSpeed
        val (expectedY, expectedVy) = fold(y0 + vy * t, vy, r, a - r)
        val event = sim.events.toList().single() as GameEvent.ProjectileExited
        assertEquals(expectedY / a, event.yNormalized, 1e-9)
        assertEquals(expectedVy, event.vy)
    }

    @Test
    fun opponentHpIsTrackedAndNeverNegative() {
        val sim = HalfSimulation(Side.Left, aspect)
        assertEquals(2, sim.onOpponentHit())
        assertEquals(1, sim.onOpponentHit())
        assertEquals(0, sim.onOpponentHit())
        assertEquals(0, sim.onOpponentHit())
        assertEquals(0, sim.opponentHp)
    }

    @Test
    fun invalidAspectIsRejected() {
        assertFailsWith<IllegalArgumentException> { HalfSimulation(Side.Left, 0.1) }
        assertFailsWith<IllegalArgumentException> { HalfSimulation(Side.Left, Double.NaN) }
    }

    @Test
    fun eventBufferGrowsWhenNotDrainedAndClears() {
        val buffer = EventBuffer(initialCapacity = 2)
        repeat(5) { buffer.push(EventKind.ProjectileMissed, it) }
        assertEquals(5, buffer.count)
        assertTrue(buffer.capacity >= 5)
        assertEquals((0 until 5).map { GameEvent.ProjectileMissed(it) }, buffer.toList())
        buffer.clear()
        assertEquals(0, buffer.count)
        assertFailsWith<IndexOutOfBoundsException> { buffer.kind(0) }
    }

    /** Reflects the unfolded coordinate [y] into [min, max]; returns the folded y and the matching vy. */
    private fun fold(y: Double, vy: Double, min: Double, max: Double): Pair<Double, Double> {
        val l = max - min
        var u = (y - min) % (2 * l)
        if (u < 0) u += 2 * l
        return if (u <= l) Pair(min + u, vy) else Pair(min + 2 * l - u, -vy)
    }
}
