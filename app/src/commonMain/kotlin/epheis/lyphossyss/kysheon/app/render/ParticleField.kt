package epheis.lyphossyss.kysheon.app.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual-only particles (golden dust, sparks, bursts of light), in logical units of the local half.
 *
 * Struct-of-arrays with a dense active range: a dead particle is replaced by the last live one (swap-remove), so
 * update and draw walk `0 until count` without holes and nothing is allocated after construction. When full, new
 * particles are dropped (an effect missing a few motes is invisible; a frame hitch is not).
 *
 * Not part of the simulation: it may use sin/cos and its own random sequence freely.
 */
class ParticleField(val capacity: Int = 1024, seed: Int = 0x5eed) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private val age = FloatArray(capacity)
    private val life = FloatArray(capacity)
    val size = FloatArray(capacity)

    /** Index into the renderer's palette. */
    val tone = IntArray(capacity)

    var count: Int = 0
        private set

    /** Particles not emitted because the field was full. */
    var dropped: Long = 0
        private set

    private var state = if (seed == 0) 1 else seed

    /** Remaining life in [0, 1]: 1 when born, 0 when gone. */
    fun fade(i: Int): Float = 1f - age[i] / life[i]

    /** [n] particles from ([px], [py]) in all directions, or within [spread] radians around [direction]. */
    fun burst(
        px: Float,
        py: Float,
        n: Int,
        tone: Int,
        speedMin: Float,
        speedMax: Float,
        lifeMin: Float,
        lifeMax: Float,
        sizeMin: Float,
        sizeMax: Float,
        direction: Float = 0f,
        spread: Float = (2 * PI).toFloat(),
    ) {
        repeat(n) {
            val angle = direction + (next() - 0.5f) * spread
            val speed = lerp(speedMin, speedMax, next())
            emit(
                px, py,
                cos(angle) * speed, sin(angle) * speed,
                lerp(lifeMin, lifeMax, next()), lerp(sizeMin, sizeMax, next()), tone,
            )
        }
    }

    /** One slow mote drifting near ([px], [py]): trails and ambient dust. */
    fun mote(px: Float, py: Float, tone: Int, jitter: Float, life: Float, size: Float) {
        emit(
            px + (next() - 0.5f) * jitter, py + (next() - 0.5f) * jitter,
            (next() - 0.5f) * jitter, (next() - 0.5f) * jitter,
            life * lerp(0.7f, 1.3f, next()), size * lerp(0.6f, 1.2f, next()), tone,
        )
    }

    /** Advances by [dt] seconds; [drag] is the fraction of velocity kept per second. */
    fun update(dt: Float, drag: Float = 0.25f) {
        val keep = 1f - (1f - drag) * dt.coerceAtMost(1f)
        var i = 0
        while (i < count) {
            age[i] += dt
            if (age[i] >= life[i]) {
                removeAt(i)
                continue
            }
            vx[i] *= keep
            vy[i] *= keep
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            i++
        }
    }

    fun clear() {
        count = 0
    }

    private fun emit(px: Float, py: Float, pvx: Float, pvy: Float, plife: Float, psize: Float, ptone: Int) {
        if (count == capacity) {
            dropped++
            return
        }
        val i = count++
        x[i] = px
        y[i] = py
        vx[i] = pvx
        vy[i] = pvy
        age[i] = 0f
        life[i] = plife.coerceAtLeast(0.01f)
        size[i] = psize
        tone[i] = ptone
    }

    private fun removeAt(i: Int) {
        val last = --count
        if (i == last) return
        x[i] = x[last]
        y[i] = y[last]
        vx[i] = vx[last]
        vy[i] = vy[last]
        age[i] = age[last]
        life[i] = life[last]
        size[i] = size[last]
        tone[i] = tone[last]
    }

    /** Xorshift32 in [0, 1). */
    private fun next(): Float {
        var s = state
        s = s xor (s shl 13)
        s = s xor (s ushr 17)
        s = s xor (s shl 5)
        state = s
        return (s ushr 8) / 16_777_216f
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
