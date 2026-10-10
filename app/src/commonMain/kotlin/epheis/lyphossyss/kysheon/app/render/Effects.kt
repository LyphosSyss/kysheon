package epheis.lyphossyss.kysheon.app.render

import kotlin.math.sin

/**
 * Screen shake driven by "trauma" in [0, 1]: hits add trauma, it decays linearly, and the offset grows with its
 * square (small hits barely move, big ones do). The motion is a sum of sines, smooth and allocation-free.
 */
class Shake(private val maxOffset: Float = 0.025f, private val decayPerSecond: Float = 1.6f) {
    var trauma: Float = 0f
        private set
    private var time = 0f

    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    fun add(amount: Float) {
        trauma = (trauma + amount).coerceAtMost(1f)
    }

    fun update(dt: Float) {
        time += dt
        trauma = (trauma - decayPerSecond * dt).coerceAtLeast(0f)
        if (trauma == 0f) {
            offsetX = 0f
            offsetY = 0f
            return
        }
        val strength = maxOffset * trauma * trauma
        offsetX = strength * (sin(time * 47f) * 0.6f + sin(time * 83f) * 0.4f)
        offsetY = strength * (sin(time * 59f + 1.3f) * 0.6f + sin(time * 97f + 0.7f) * 0.4f)
    }
}

/**
 * Ripples on the celestial veil at the seam, one per projectile crossing it. Fixed capacity, oldest overwritten.
 * y in logical units, age in seconds.
 */
class Ripples(val capacity: Int = 8, val lifetime: Float = 0.9f) {
    val y = FloatArray(capacity)
    val age = FloatArray(capacity) { lifetime }
    private var next = 0

    fun add(py: Float) {
        y[next] = py
        age[next] = 0f
        next = (next + 1) % capacity
    }

    fun update(dt: Float) {
        for (i in 0 until capacity) if (age[i] < lifetime) age[i] += dt
    }

    /** Horizontal displacement of the veil at [py], in logical units, from every live ripple. */
    fun displacement(py: Float, time: Float): Float {
        var d = 0f
        for (i in 0 until capacity) {
            val a = age[i]
            if (a >= lifetime) continue
            val distance = py - y[i]
            val front = a * RIPPLE_SPEED
            val envelope = (1f - a / lifetime) * gaussian(distance, front)
            d += envelope * sin(distance * 60f - time * 20f) * RIPPLE_AMPLITUDE
        }
        return d
    }

    private fun gaussian(distance: Float, front: Float): Float {
        val k = (kotlin.math.abs(distance) - front) / RIPPLE_WIDTH
        return 1f / (1f + k * k)
    }

    private companion object {
        const val RIPPLE_SPEED = 0.8f
        const val RIPPLE_WIDTH = 0.08f
        const val RIPPLE_AMPLITUDE = 0.02f
    }
}
