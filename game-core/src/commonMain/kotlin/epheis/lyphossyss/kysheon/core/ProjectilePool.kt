package epheis.lyphossyss.kysheon.core

/**
 * Fixed-capacity projectile storage as a struct of arrays, preallocated once: no allocation while playing.
 *
 * Slots are addressed by index in `0 until capacity`; only slots where [isActive] is true hold a projectile.
 * A new projectile takes the lowest free slot (deterministic). The renderer reads [prevX]/[prevY] and [x]/[y]
 * and interpolates with the loop alpha ([interpolatedX], [interpolatedY]).
 */
class ProjectilePool(val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be > 0: $capacity" }
    }

    internal val active = BooleanArray(capacity)
    internal val ids = IntArray(capacity)
    internal val xs = DoubleArray(capacity)
    internal val ys = DoubleArray(capacity)
    internal val prevXs = DoubleArray(capacity)
    internal val prevYs = DoubleArray(capacity)
    internal val vxs = DoubleArray(capacity)
    internal val vys = DoubleArray(capacity)

    /** Number of active projectiles. */
    var activeCount: Int = 0
        private set

    fun isActive(slot: Int): Boolean = active[slot]
    fun id(slot: Int): Int = ids[slot]
    fun x(slot: Int): Double = xs[slot]
    fun y(slot: Int): Double = ys[slot]
    fun prevX(slot: Int): Double = prevXs[slot]
    fun prevY(slot: Int): Double = prevYs[slot]
    fun vx(slot: Int): Double = vxs[slot]
    fun vy(slot: Int): Double = vys[slot]

    fun interpolatedX(slot: Int, alpha: Double): Double = prevXs[slot] + (xs[slot] - prevXs[slot]) * alpha
    fun interpolatedY(slot: Int, alpha: Double): Double = prevYs[slot] + (ys[slot] - prevYs[slot]) * alpha

    /** Lowest free slot, or -1 if full. */
    internal fun freeSlot(): Int {
        if (activeCount == capacity) return -1
        for (i in 0 until capacity) if (!active[i]) return i
        return -1
    }

    internal fun activate(slot: Int, id: Int, x: Double, y: Double, vx: Double, vy: Double) {
        active[slot] = true
        ids[slot] = id
        xs[slot] = x
        ys[slot] = y
        prevXs[slot] = x
        prevYs[slot] = y
        vxs[slot] = vx
        vys[slot] = vy
        activeCount++
    }

    internal fun release(slot: Int) {
        active[slot] = false
        activeCount--
    }
}
