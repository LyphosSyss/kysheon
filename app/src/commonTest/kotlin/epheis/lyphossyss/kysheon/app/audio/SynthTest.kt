package epheis.lyphossyss.kysheon.app.audio

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class SynthTest {

    /** Upper bounds from the sound design brief (KYS-0040). */
    private val maxSeconds = mapOf(
        Sfx.Fire to 0.20,
        Sfx.ProjectileEnter to 0.30,
        Sfx.HitDealt to 0.45,
        Sfx.HitTaken to 0.55,
        Sfx.Countdown to 0.10,
        Sfx.Start to 1.00,
        Sfx.Victory to 1.50,
        Sfx.Defeat to 1.50,
        Sfx.Draw to 1.20,
    )

    private val pcm: Map<Sfx, ShortArray> = Sfx.entries.associateWith(Synth::render)

    @Test
    fun everySoundRendersWithinItsBound() {
        for (sfx in Sfx.entries) {
            val samples = pcm.getValue(sfx).size
            val bound = (maxSeconds.getValue(sfx) * Synth.SAMPLE_RATE).toInt()
            assertTrue(samples in 1..bound, "$sfx: $samples samples, bound $bound")
        }
    }

    @Test
    fun renderingIsDeterministic() {
        for (sfx in Sfx.entries) assertContentEquals(pcm.getValue(sfx), Synth.render(sfx), "$sfx")
    }

    @Test
    fun startsAndEndsAtZero() {
        for (sfx in Sfx.entries) {
            val s = pcm.getValue(sfx)
            assertEquals(0, s.first().toInt(), "$sfx first sample")
            assertEquals(0, s.last().toInt(), "$sfx last sample")
            // Neighbours of the edges stay tiny: the fades are ramps, not steps.
            assertTrue(abs(s[1].toInt()) < 1_000, "$sfx second sample ${s[1]}")
            assertTrue(abs(s[s.size - 2].toInt()) < 1_000, "$sfx penultimate sample ${s[s.size - 2]}")
        }
    }

    @Test
    fun peakBelowLimitAndNotSilent() {
        for (sfx in Sfx.entries) {
            val s = pcm.getValue(sfx)
            val peak = s.maxOf { abs(it.toInt()) }
            assertTrue(peak <= 0.8 * Short.MAX_VALUE, "$sfx peak $peak")
            assertTrue(rms(s) > 0.02 * Short.MAX_VALUE, "$sfx rms ${rms(s)}")
        }
    }

    @Test
    fun projectileEnterQuieterThanHitTaken() {
        val enter = rms(pcm.getValue(Sfx.ProjectileEnter))
        val taken = rms(pcm.getValue(Sfx.HitTaken))
        assertTrue(enter < taken, "enter $enter, hit taken $taken")
    }

    @Test
    fun totalLengthAndRenderTime() {
        val mark = TimeSource.Monotonic.markNow()
        val total = Sfx.entries.sumOf { Synth.render(it).size }
        val elapsed = mark.elapsedNow()
        // Documented in Synth's KDoc and in KYS-0040; the render time is printed for the record, not asserted.
        assertEquals(276_948, total)
        println("Synth: $total samples (${total.toDouble() / Synth.SAMPLE_RATE} s) rendered in $elapsed")
    }

    private fun rms(s: ShortArray): Double {
        var sum = 0.0
        for (v in s) sum += v.toDouble() * v
        return sqrt(sum / s.size)
    }
}
