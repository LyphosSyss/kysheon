package epheis.lyphossyss.kysheon.core

/**
 * All gameplay tunables. Units: lengths in logical widths of one half (a half is 1 wide and `aspect` high),
 * speeds in widths per second, durations in microseconds or in fixed steps.
 *
 * Both devices must use the same rules: they are not sent over the link.
 */
data class Rules(
    /** Fighter hit points at start. */
    val maxHp: Int = 3,
    /** Fixed step duration. 16 667 µs ≈ 60 Hz. Must match the [FixedStepLoop] driving the simulation. */
    val stepMicros: Long = 16_667L,
    /** Fighter collision circle radius, widths. */
    val fighterRadius: Double = 0.06,
    /** Distance between the fighter center and the half's outer edge, widths. */
    val fighterInset: Double = 0.10,
    /** Maximum vertical fighter speed toward the finger target, widths per second. */
    val fighterSpeed: Double = 3.0,
    /** Projectile collision circle radius, widths. */
    val projectileRadius: Double = 0.02,
    /** Horizontal projectile speed at fire, widths per second (must stay below 32.767, the HANDOFF i16 range). */
    val projectileSpeed: Double = 1.2,
    /** Steps to wait between two shots (15 steps = 250 ms). */
    val fireCooldownSteps: Int = 15,
    /** Capacity of the pool of our own projectiles still in our half. [HalfSimulation.fire] fails when full. */
    val maxLocalProjectiles: Int = 8,
    /** Capacity of the pool of incoming projectiles. [HalfSimulation.receiveProjectile] fails when full. */
    val maxIncomingProjectiles: Int = 16,
    /** Upper bound of the catch-up applied to an incoming projectile (late HANDOFF), microseconds. */
    val maxCatchUpMicros: Long = 200_000L,
) {
    init {
        require(maxHp > 0) { "maxHp must be > 0: $maxHp" }
        require(stepMicros > 0) { "stepMicros must be > 0: $stepMicros" }
        require(fighterRadius > 0.0) { "fighterRadius must be > 0: $fighterRadius" }
        require(fighterInset > 0.0 && fighterInset < 1.0) { "fighterInset must be in (0, 1): $fighterInset" }
        require(fighterSpeed > 0.0) { "fighterSpeed must be > 0: $fighterSpeed" }
        require(projectileRadius > 0.0) { "projectileRadius must be > 0: $projectileRadius" }
        require(projectileSpeed > 0.0 && projectileSpeed < 32.767) { "projectileSpeed out of range: $projectileSpeed" }
        require(fireCooldownSteps >= 0) { "fireCooldownSteps must be >= 0: $fireCooldownSteps" }
        require(maxLocalProjectiles > 0) { "maxLocalProjectiles must be > 0: $maxLocalProjectiles" }
        require(maxIncomingProjectiles > 0) { "maxIncomingProjectiles must be > 0: $maxIncomingProjectiles" }
        require(maxCatchUpMicros >= 0) { "maxCatchUpMicros must be >= 0: $maxCatchUpMicros" }
    }

    /** [stepMicros] in seconds, the `dt` of one step. */
    val stepSeconds: Double get() = stepMicros.toDouble() / 1_000_000.0
}

/** Which half this device simulates. Host = [Left] (border at x = 1), guest = [Right] (border at x = 0). */
enum class Side {
    Left,
    Right;

    /** x of the border shared with the other half. */
    val borderX: Double get() = if (this == Left) 1.0 else 0.0

    /** x of the outer edge, where the fighter stands. */
    val outerX: Double get() = if (this == Left) 0.0 else 1.0

    /** +1 if the border is toward increasing x. */
    val towardBorder: Double get() = if (this == Left) 1.0 else -1.0
}
