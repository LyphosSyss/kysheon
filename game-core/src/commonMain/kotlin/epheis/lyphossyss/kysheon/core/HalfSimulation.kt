package epheis.lyphossyss.kysheon.core

/**
 * Simulation of one half of the map, advanced by fixed steps of [Rules.stepMicros].
 *
 * Space: x in [0, 1] (one width), y in [0, [aspect]]. No mirroring: x grows left to right on both devices.
 * [Side.Left] (host) has its border at x = 1, [Side.Right] (guest) at x = 0.
 *
 * Contents: one fighter near the outer edge, our own projectiles flying toward the border ([localProjectiles]),
 * and projectiles received from the other half flying toward our outer edge ([incomingProjectiles]).
 * Only incoming projectiles can hit our fighter: the receiving half decides every impact.
 *
 * Determinism: pure function of the call sequence (no clock, no randomness), `Double` arithmetic limited to
 * `+ - * /` and `sqrt`, fixed iteration orders (pool slot order, local pool before incoming pool).
 * [step] does not allocate. Not thread-safe: drive it from one thread.
 *
 * Events are appended to [events]; the caller reads then clears them.
 */
class HalfSimulation(
    val side: Side,
    /** Height / width of a half, agreed by both devices before the match. */
    val aspect: Double,
    val rules: Rules = Rules(),
) {
    init {
        require(aspect.isFinite() && aspect > 2.0 * rules.fighterRadius && aspect > 2.0 * rules.projectileRadius) {
            "aspect too small or invalid: $aspect"
        }
    }

    val events: EventBuffer = EventBuffer(4 * (rules.maxLocalProjectiles + rules.maxIncomingProjectiles) + 8)
    val localProjectiles: ProjectilePool = ProjectilePool(rules.maxLocalProjectiles)
    val incomingProjectiles: ProjectilePool = ProjectilePool(rules.maxIncomingProjectiles)

    /** Fixed x of the fighter center. */
    val fighterX: Double = side.outerX + side.towardBorder * rules.fighterInset

    private val dt = rules.stepSeconds
    private val fighterMinY = rules.fighterRadius
    private val fighterMaxY = aspect - rules.fighterRadius
    private val projectileMinY = rules.projectileRadius
    private val projectileMaxY = aspect - rules.projectileRadius
    private val hitRadius = rules.fighterRadius + rules.projectileRadius
    private val fighterStep = rules.fighterSpeed * dt

    var fighterY: Double = aspect * 0.5
        private set

    /** Fighter y before the last step, for render interpolation. */
    var fighterPrevY: Double = fighterY
        private set

    /** Where the fighter is heading, already clamped to [fighterRadius, aspect − fighterRadius]. */
    var fighterTargetY: Double = fighterY
        private set

    var hp: Int = rules.maxHp
        private set

    /** Opponent hit points as reported by the peer (HUD only). */
    var opponentHp: Int = rules.maxHp
        private set

    val isDown: Boolean get() = hp == 0

    /** Steps run since creation. */
    var stepCount: Long = 0L
        private set

    /** Steps left before [fire] is accepted again. */
    var cooldownSteps: Int = 0
        private set

    /** Id of the last projectile fired, or -1. */
    var lastFiredId: Int = -1
        private set

    private var nextProjectileId = 0

    fun fighterInterpolatedY(alpha: Double): Double = fighterPrevY + (fighterY - fighterPrevY) * alpha

    /** Sets the finger target height (logical units). Clamped; non-finite values are ignored. */
    fun setFighterTarget(y: Double) {
        if (!y.isFinite()) return
        fighterTargetY = clamp(y, fighterMinY, fighterMaxY)
    }

    /**
     * Fires a projectile from the fighter toward the border, at [Rules.projectileSpeed] horizontally and [vy]
     * vertically (0 in V1). Returns false if refused: cooldown running, pool full, fighter down or [vy] not finite.
     * Ids are u16 and wrap around.
     */
    fun fire(vy: Double = 0.0): Boolean {
        if (hp == 0 || cooldownSteps > 0 || !vy.isFinite()) return false
        val slot = localProjectiles.freeSlot()
        if (slot < 0) return false
        val id = nextProjectileId
        nextProjectileId = (nextProjectileId + 1) and 0xFFFF
        localProjectiles.activate(
            slot = slot,
            id = id,
            x = fighterX + side.towardBorder * rules.fighterRadius,
            y = fighterY,
            vx = side.towardBorder * rules.projectileSpeed,
            vy = vy,
        )
        cooldownSteps = rules.fireCooldownSteps
        lastFiredId = id
        return true
    }

    /**
     * Enters a projectile coming from the other half (HANDOFF).
     *
     * It appears on our border at `y = yNormalized × aspect` (clamped between the walls), then is advanced by
     * [elapsedMicros] clamped to [0, [Rules.maxCatchUpMicros]]: `elapsed / stepMicros` full steps, then one
     * partial step for the remainder, each with the same swept collisions as [step]. The fighter is held still
     * at its current position during that catch-up (its past positions are not kept). The projectile can
     * therefore hit, or miss, right away: those events are appended to [events] immediately.
     *
     * [elapsedMicros] is the time between the exit instant on the peer and the current simulated instant of this
     * half (the end of its last step). The result depends only on the arguments and the current state, never on
     * how the caller batches frames afterwards.
     *
     * Returns false if refused: [vx] not heading into the half (or zero), non-finite values, or incoming pool full.
     * Returns true otherwise, even if the projectile already hit or missed during the catch-up.
     */
    fun receiveProjectile(id: Int, yNormalized: Double, vx: Double, vy: Double, elapsedMicros: Long): Boolean {
        require(id in 0..0xFFFF) { "projectile id out of u16: $id" }
        if (!yNormalized.isFinite() || !vx.isFinite() || !vy.isFinite()) return false
        if (vx * side.towardBorder >= 0.0) return false
        val pool = incomingProjectiles
        val slot = pool.freeSlot()
        if (slot < 0) return false
        pool.activate(slot, id, side.borderX, clamp(yNormalized * aspect, projectileMinY, projectileMaxY), vx, vy)

        val elapsed = when {
            elapsedMicros < 0L -> 0L
            elapsedMicros > rules.maxCatchUpMicros -> rules.maxCatchUpMicros
            else -> elapsedMicros
        }
        val fullSteps = elapsed / rules.stepMicros
        val remainderMicros = elapsed - fullSteps * rules.stepMicros
        val f = fighterY
        var alive = true
        var k = 0L
        while (alive && k < fullSteps) {
            alive = advance(pool, slot, incoming = true, duration = dt, f0 = f, f1 = f)
            k++
        }
        if (alive && remainderMicros > 0L) {
            val duration = remainderMicros.toDouble() / 1_000_000.0
            alive = advance(pool, slot, incoming = true, duration = duration, f0 = f, f1 = f)
        }
        if (alive) {
            pool.prevXs[slot] = pool.xs[slot]
            pool.prevYs[slot] = pool.ys[slot]
        }
        return true
    }

    /** The peer reported a HIT on one of our projectiles. Returns the new [opponentHp] (never below 0). */
    fun onOpponentHit(): Int {
        if (opponentHp > 0) opponentHp--
        return opponentHp
    }

    /** Advances the half by one fixed step. No allocation. */
    fun step() {
        stepCount++
        if (cooldownSteps > 0) cooldownSteps--

        val f0 = fighterY
        fighterPrevY = f0
        val dy = fighterTargetY - f0
        fighterY = when {
            dy > fighterStep -> f0 + fighterStep
            dy < -fighterStep -> f0 - fighterStep
            else -> fighterTargetY
        }
        val f1 = fighterY

        val local = localProjectiles
        for (slot in 0 until local.capacity) {
            if (!local.active[slot]) continue
            local.prevXs[slot] = local.xs[slot]
            local.prevYs[slot] = local.ys[slot]
            advance(local, slot, incoming = false, duration = dt, f0 = f0, f1 = f1)
        }
        val incoming = incomingProjectiles
        for (slot in 0 until incoming.capacity) {
            if (!incoming.active[slot]) continue
            incoming.prevXs[slot] = incoming.xs[slot]
            incoming.prevYs[slot] = incoming.ys[slot]
            advance(incoming, slot, incoming = true, duration = dt, f0 = f0, f1 = f1)
        }
    }

    /**
     * Moves one projectile by [duration] seconds, bouncing on the top and bottom walls, and resolves the first
     * event on its path: border crossing (local), outer edge crossing (incoming) or fighter impact (incoming, swept
     * against the fighter moving linearly from [f0] to [f1] over [duration]). Returns false if it was removed.
     */
    private fun advance(
        pool: ProjectilePool,
        slot: Int,
        incoming: Boolean,
        duration: Double,
        f0: Double,
        f1: Double,
    ): Boolean {
        var x = pool.xs[slot]
        var y = pool.ys[slot]
        var vy = pool.vys[slot]
        val vx = pool.vxs[slot]
        val edgeX = if (incoming) side.outerX else side.borderX
        val edgeDir = if (incoming) -side.towardBorder else side.towardBorder
        val checkHit = incoming && hp > 0
        val fighterDy = f1 - f0

        var elapsed = 0.0
        var remaining = duration
        var bounces = 0
        while (true) {
            // Sub-segment up to the next wall bounce (or the end of the step).
            var seg = remaining
            var wall = 0
            if (bounces < MAX_BOUNCES_PER_ADVANCE) {
                if (vy > 0.0) {
                    val tw = (projectileMaxY - y) / vy
                    if (tw < seg) {
                        seg = if (tw > 0.0) tw else 0.0
                        wall = 1
                    }
                } else if (vy < 0.0) {
                    val tw = (projectileMinY - y) / vy
                    if (tw < seg) {
                        seg = if (tw > 0.0) tw else 0.0
                        wall = -1
                    }
                }
            }
            val x1 = x + vx * seg
            val y1 = y + vy * seg

            var u = 2.0
            var outcome = ALIVE
            if ((x1 - edgeX) * edgeDir >= 0.0) {
                val span = x1 - x
                u = if (span != 0.0) clamp((edgeX - x) / span, 0.0, 1.0) else 0.0
                outcome = if (incoming) MISSED else EXITED
            }
            if (checkHit) {
                val fa = f0 + fighterDy * (elapsed / duration)
                val fb = f0 + fighterDy * ((elapsed + seg) / duration)
                val t = Collision.sweptCircleTime(
                    x - fighterX, y - fa,
                    x1 - fighterX, y1 - fb,
                    0.0, 0.0,
                    hitRadius,
                )
                if (t != Collision.NO_HIT && t <= u) {
                    u = t
                    outcome = HIT
                }
            }
            if (outcome != ALIVE) {
                val ex = x + (x1 - x) * u
                val ey = y + (y1 - y) * u
                pool.xs[slot] = ex
                pool.ys[slot] = ey
                pool.vys[slot] = vy
                val id = pool.ids[slot]
                pool.release(slot)
                when (outcome) {
                    EXITED -> events.push(EventKind.ProjectileExited, id, clamp(ey / aspect, 0.0, 1.0), vx, vy)
                    MISSED -> events.push(EventKind.ProjectileMissed, id)
                    else -> {
                        events.push(EventKind.FighterHit, id)
                        hp--
                        if (hp == 0) events.push(EventKind.FighterDown, -1)
                    }
                }
                return false
            }

            x = x1
            y = y1
            elapsed += seg
            remaining -= seg
            if (wall == 0) break
            vy = -vy
            y = if (wall > 0) projectileMaxY else projectileMinY
            bounces++
        }
        pool.xs[slot] = x
        pool.ys[slot] = clamp(y, projectileMinY, projectileMaxY)
        pool.vys[slot] = vy
        return true
    }

    private companion object {
        const val ALIVE = 0
        const val EXITED = 1
        const val MISSED = 2
        const val HIT = 3

        /** Bounds the work per step for absurd vertical speeds; past it the projectile is clamped to the wall. */
        const val MAX_BOUNCES_PER_ADVANCE = 8

        fun clamp(v: Double, min: Double, max: Double): Double = if (v < min) min else if (v > max) max else v
    }
}
