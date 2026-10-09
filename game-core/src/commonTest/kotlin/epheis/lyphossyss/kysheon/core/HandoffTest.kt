package epheis.lyphossyss.kysheon.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HandoffTest {

    private val aspect = 2.0
    private val stepMicros = Rules().stepMicros

    @Test
    fun incomingProjectileAppearsOnTheBorder() {
        val left = HalfSimulation(Side.Left, aspect)
        assertTrue(left.receiveProjectile(7, 0.25, -1.2, 0.0, 0L))
        assertEquals(1.0, left.incomingProjectiles.x(0))
        assertEquals(0.5, left.incomingProjectiles.y(0))
        assertEquals(1.0, left.incomingProjectiles.prevX(0))
        assertEquals(7, left.incomingProjectiles.id(0))

        val right = HalfSimulation(Side.Right, aspect)
        assertTrue(right.receiveProjectile(8, 0.75, 1.2, 0.0, 0L))
        assertEquals(0.0, right.incomingProjectiles.x(0))
        assertEquals(1.5, right.incomingProjectiles.y(0))
        assertEquals(0, right.events.count)
    }

    @Test
    fun incomingProjectileIsRefusedWhenInvalidOrPoolFull() {
        val sim = HalfSimulation(Side.Left, aspect, Rules(maxIncomingProjectiles = 2))
        assertFalse(sim.receiveProjectile(1, 0.5, 1.2, 0.0, 0L), "heading out of the half")
        assertFalse(sim.receiveProjectile(1, 0.5, 0.0, 0.0, 0L), "no horizontal speed")
        assertFalse(sim.receiveProjectile(1, Double.NaN, -1.2, 0.0, 0L))
        assertFailsWith<IllegalArgumentException> { sim.receiveProjectile(0x10000, 0.5, -1.2, 0.0, 0L) }
        assertTrue(sim.receiveProjectile(1, 0.1, -1.2, 0.0, 0L))
        assertTrue(sim.receiveProjectile(2, 0.9, -1.2, 0.0, 0L))
        assertFalse(sim.receiveProjectile(3, 0.5, -1.2, 0.0, 0L), "pool full")
    }

    @Test
    fun alignedProjectileHitsFighterAndLosesOneHp() {
        val sim = HalfSimulation(Side.Left, aspect)
        assertTrue(sim.receiveProjectile(42, 0.5, -1.2, 0.0, 0L))
        while (sim.events.count == 0) sim.step()
        assertEquals(listOf(GameEvent.FighterHit(42)), sim.events.toList())
        assertEquals(2, sim.hp)
        assertFalse(sim.isDown)
        assertEquals(0, sim.incomingProjectiles.activeCount)
        // Impact where the circles touch: x = fighterX + fighterRadius + projectileRadius.
        assertEquals(0.18, sim.incomingProjectiles.x(0), 1e-12)
    }

    @Test
    fun threeHitsBringTheFighterDownOnce() {
        val sim = HalfSimulation(Side.Right, aspect)
        val seen = mutableListOf<GameEvent>()
        for (id in 0 until 3) {
            assertTrue(sim.receiveProjectile(id, 0.5, 1.2, 0.0, 0L))
            repeat(60) { sim.step() }
            seen += sim.events.toList()
            sim.events.clear()
        }
        assertEquals(
            listOf(GameEvent.FighterHit(0), GameEvent.FighterHit(1), GameEvent.FighterHit(2), GameEvent.FighterDown),
            seen,
        )
        assertEquals(0, sim.hp)
        assertTrue(sim.isDown)
        assertFalse(sim.fire(), "a downed fighter cannot fire")
        // A downed fighter is not hit again: the projectile goes through and expires.
        assertTrue(sim.receiveProjectile(3, 0.5, 1.2, 0.0, 0L))
        repeat(60) { sim.step() }
        assertEquals(listOf(GameEvent.ProjectileMissed(3)), sim.events.toList())
    }

    @Test
    fun missedProjectileExpiresAtOuterEdge() {
        val sim = HalfSimulation(Side.Left, aspect)
        assertTrue(sim.receiveProjectile(9, 0.1, -1.2, 0.0, 0L))
        var steps = 0
        while (sim.events.count == 0) {
            sim.step()
            steps++
            if (sim.incomingProjectiles.isActive(0)) assertTrue(sim.incomingProjectiles.x(0) > 0.0)
        }
        assertEquals(listOf(GameEvent.ProjectileMissed(9)), sim.events.toList())
        assertEquals(3, sim.hp)
        assertEquals(0, sim.incomingProjectiles.activeCount)
        // 1 width at 1.2 widths/s ≈ 0.833 s = 50 steps.
        assertEquals(50, steps)
    }

    @Test
    fun fastProjectileCannotTunnelThroughFighter() {
        val sim = HalfSimulation(Side.Left, aspect)
        // 30 widths/s = 0.5 width per step, far more than the 0.16 hit diameter.
        assertTrue(sim.receiveProjectile(1, 0.5, -30.0, 0.0, 0L))
        sim.step()
        sim.step()
        assertEquals(listOf(GameEvent.FighterHit(1)), sim.events.toList())
    }

    @Test
    fun catchUpResolvesHitImmediatelyWithoutTunnelling() {
        val sim = HalfSimulation(Side.Right, aspect)
        // 200 ms at 30 widths/s = 6 widths of travel inside the catch-up: it must stop on the fighter.
        assertTrue(sim.receiveProjectile(5, 0.5, 30.0, 0.0, 200_000L))
        assertEquals(listOf(GameEvent.FighterHit(5)), sim.events.toList())
        assertEquals(2, sim.hp)
        assertEquals(0, sim.incomingProjectiles.activeCount)
    }

    @Test
    fun catchUpAdvancesByElapsedIncludingRemainder() {
        val sim = HalfSimulation(Side.Left, aspect)
        assertTrue(sim.receiveProjectile(1, 0.1, -1.2, 0.0, 25_000L))
        assertEquals(1.0 - 1.2 * 0.025, sim.incomingProjectiles.x(0), 1e-12)
        assertEquals(sim.incomingProjectiles.x(0), sim.incomingProjectiles.prevX(0))
    }

    @Test
    fun elapsedIsClamped() {
        fun stateAfter(elapsed: Long): String {
            val sim = HalfSimulation(Side.Left, aspect)
            assertTrue(sim.receiveProjectile(1, 0.1, -1.2, 0.7, elapsed))
            return snapshot(sim)
        }
        val max = Rules().maxCatchUpMicros
        assertEquals(stateAfter(max), stateAfter(10_000_000L))
        assertEquals(stateAfter(0L), stateAfter(-50_000L))
        assertNotEquals(stateAfter(0L), stateAfter(max))
        val x = HalfSimulation(Side.Left, aspect).apply { receiveProjectile(1, 0.1, -1.2, 0.0, 10_000_000L) }
            .incomingProjectiles.x(0)
        assertEquals(1.0 - 1.2 * 0.2, x, 1e-12)
    }

    @Test
    fun incomingProjectileBouncesOnWalls() {
        val a = 1.0
        val sim = HalfSimulation(Side.Right, a)
        val r = sim.rules.projectileRadius
        assertTrue(sim.receiveProjectile(3, 0.9, 1.2, 3.0, 0L))
        var bounced = false
        while (sim.events.count == 0) {
            sim.step()
            if (!sim.incomingProjectiles.isActive(0)) break
            val y = sim.incomingProjectiles.y(0)
            assertTrue(y >= r && y <= a - r, "y out of walls: $y")
            if (sim.incomingProjectiles.vy(0) < 0.0) bounced = true
        }
        assertTrue(bounced)
    }

    @Test
    fun catchUpAtOnceEqualsSteppingWithAStillFighter() {
        val a = HalfSimulation(Side.Left, aspect)
        val b = HalfSimulation(Side.Left, aspect)
        assertTrue(a.receiveProjectile(11, 0.37, -1.3, 0.9, 7 * stepMicros))
        assertTrue(b.receiveProjectile(11, 0.37, -1.3, 0.9, 0L))
        repeat(7) { b.step() }
        repeat(20) {
            a.step()
            b.step()
        }
        assertEquals(projectiles(a), projectiles(b))
        assertEquals(a.events.toList(), b.events.toList())
    }

    @Test
    fun handoffScenarioIsIndependentOfFrameBatching() {
        val frameSchedules = listOf(
            LongArray(400) { 16_667L }, // one step per frame
            LongArray(400) { if (it % 2 == 0) 5_000L else 28_334L }, // 0 or 2 steps
            LongArray(400) { (it * 7_919L) % 70_000L + 1_000L }, // irregular, up to 4 steps
            LongArray(200) { 66_668L }, // 4 steps per frame
        )
        val traces = frameSchedules.map { runScenario(it, steps = 300) }
        for (trace in traces.drop(1)) assertEquals(traces[0], trace)
        // The scenario really exercises hits, misses, exits and the end of the fight.
        val events = traces[0].events
        assertTrue(events.any { it is GameEvent.FighterHit })
        assertTrue(events.any { it is GameEvent.ProjectileMissed })
        assertTrue(events.any { it is GameEvent.ProjectileExited })
    }

    private data class Trace(val events: List<GameEvent>, val states: List<String>)

    /**
     * Scripted inputs keyed by step index, applied right before that step, driven by a [FixedStepLoop]
     * fed with the given frame durations. Records every event and the full state after each step.
     */
    private fun runScenario(frames: LongArray, steps: Int): Trace {
        val sim = HalfSimulation(Side.Left, aspect)
        val loop = FixedStepLoop(stepMicros = stepMicros, maxStepsPerAdvance = 5)
        val events = mutableListOf<GameEvent>()
        val states = mutableListOf<String>()
        var now = 1_000_000L
        loop.reset(now)
        var index = 0
        var frame = 0
        while (index < steps) {
            now += frames[frame % frames.size]
            frame++
            loop.advanceTo(now) {
                if (index < steps) {
                    applyInputs(sim, index)
                    sim.step()
                    events += sim.events.toList()
                    sim.events.clear()
                    states += snapshot(sim)
                    index++
                }
            }
        }
        assertEquals(0L, loop.droppedSteps)
        return Trace(events, states)
    }

    private fun applyInputs(sim: HalfSimulation, step: Int) {
        if (step % 40 == 0) sim.setFighterTarget(0.3 + (step % 120) * 0.012)
        if (step % 25 == 3) sim.fire(vy = if (step % 50 == 3) 0.0 else 0.8)
        when (step) {
            10 -> sim.receiveProjectile(100, 0.5, -1.2, 0.0, 40_000L)
            30 -> sim.receiveProjectile(101, 0.05, -1.5, 0.4, 123_456L)
            60 -> sim.receiveProjectile(102, 0.62, -2.0, -0.9, 90_000L)
            90 -> sim.receiveProjectile(103, 0.99, -1.0, 1.7, 500_000L)
            150 -> sim.receiveProjectile(104, 0.15, -25.0, 0.0, 16_000L) // fast, fighter still at y = 0.3
            200 -> sim.receiveProjectile(105, 0.58, -1.2, 0.0, 0L) // meets the fighter while it moves
        }
    }

    private fun projectiles(sim: HalfSimulation): String = buildString {
        for (pool in listOf(sim.localProjectiles, sim.incomingProjectiles)) {
            for (i in 0 until pool.capacity) {
                if (!pool.isActive(i)) continue
                append("[$i id=${pool.id(i)} x=${pool.x(i)} y=${pool.y(i)} px=${pool.prevX(i)} py=${pool.prevY(i)}")
                append(" vx=${pool.vx(i)} vy=${pool.vy(i)}]")
            }
            append('|')
        }
    }

    private fun snapshot(sim: HalfSimulation): String =
        "fy=${sim.fighterY} fpy=${sim.fighterPrevY} ft=${sim.fighterTargetY} hp=${sim.hp} " +
            "cd=${sim.cooldownSteps} n=${sim.stepCount} ${projectiles(sim)} ev=${sim.events.toList()}"
}
