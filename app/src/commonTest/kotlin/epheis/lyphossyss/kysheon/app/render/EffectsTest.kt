package epheis.lyphossyss.kysheon.app.render

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectsTest {

    @Test
    fun particlesAgeOutAndKeepADenseRange() {
        val field = ParticleField(capacity = 64)
        field.burst(0.5f, 0.5f, n = 20, tone = 1, speedMin = 0.1f, speedMax = 0.2f, lifeMin = 0.1f, lifeMax = 0.1f, sizeMin = 1f, sizeMax = 1f)
        field.burst(0.5f, 0.5f, n = 10, tone = 2, speedMin = 0.1f, speedMax = 0.2f, lifeMin = 1f, lifeMax = 1f, sizeMin = 1f, sizeMax = 1f)
        assertEquals(30, field.count)

        field.update(0.2f)
        assertEquals(10, field.count, "short-lived particles are gone")
        for (i in 0 until field.count) assertEquals(2, field.tone[i], "survivors compacted to the front")

        field.update(1f)
        assertEquals(0, field.count)
    }

    @Test
    fun fullFieldDropsInsteadOfGrowing() {
        val field = ParticleField(capacity = 8)
        field.burst(0f, 0f, n = 12, tone = 0, speedMin = 0f, speedMax = 0f, lifeMin = 1f, lifeMax = 1f, sizeMin = 1f, sizeMax = 1f)
        assertEquals(8, field.count)
        assertEquals(4L, field.dropped)
    }

    @Test
    fun particlesMoveAndSlowDown() {
        val field = ParticleField(capacity = 1)
        field.burst(0f, 0f, n = 1, tone = 0, speedMin = 1f, speedMax = 1f, lifeMin = 10f, lifeMax = 10f, sizeMin = 1f, sizeMax = 1f, direction = 0f, spread = 0f)
        field.update(0.1f)
        val first = field.x[0]
        field.update(0.1f)
        val second = field.x[0] - first
        assertTrue(first > 0f && second in 0f..first, "moved $first then $second")
        assertTrue(abs(field.y[0]) < 1e-6f, "straight along x")
    }

    @Test
    fun emissionIsDeterministicForASeed() {
        val a = ParticleField(capacity = 16, seed = 42)
        val b = ParticleField(capacity = 16, seed = 42)
        for (field in listOf(a, b)) {
            field.burst(0.3f, 0.7f, n = 16, tone = 0, speedMin = 0.1f, speedMax = 0.5f, lifeMin = 1f, lifeMax = 2f, sizeMin = 1f, sizeMax = 3f)
            field.update(0.05f)
        }
        for (i in 0 until 16) {
            assertEquals(a.x[i], b.x[i])
            assertEquals(a.size[i], b.size[i])
        }
    }

    @Test
    fun shakeGrowsWithTraumaAndSettles() {
        val shake = Shake(maxOffset = 1f, decayPerSecond = 1f)
        shake.add(0.5f)
        shake.add(0.8f)
        assertEquals(1f, shake.trauma, "capped")
        var peak = 0f
        repeat(30) {
            shake.update(1f / 60)
            peak = maxOf(peak, abs(shake.offsetX), abs(shake.offsetY))
        }
        assertTrue(peak > 0.1f, "peak $peak")
        repeat(120) { shake.update(1f / 60) }
        assertEquals(0f, shake.trauma)
        assertEquals(0f, shake.offsetX)
    }

    @Test
    fun ripplesFadeAway() {
        val ripples = Ripples(capacity = 2, lifetime = 0.5f)
        ripples.add(1f)
        ripples.update(0.1f)
        var any = 0f
        for (k in 0..20) any = maxOf(any, abs(ripples.displacement(1f + k * 0.01f, 0.1f)))
        assertTrue(any > 0f)
        ripples.update(1f)
        assertEquals(0f, ripples.displacement(1.05f, 1.1f))
    }
}
