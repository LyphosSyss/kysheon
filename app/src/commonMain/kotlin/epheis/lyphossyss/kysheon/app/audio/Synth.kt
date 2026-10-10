package epheis.lyphossyss.kysheon.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Procedural synthesizer of every [Sfx] (see KYS-0040): bells, chimes and gongs built by additive synthesis of
 * inharmonic partials with exponential decays, plus a little band-passed noise for the strikes.
 *
 * Pure Kotlin and deterministic: the same [Sfx] always gives the same samples (seeded xorshift noise, no clock, no
 * platform API). Output is mono 16-bit PCM at [SAMPLE_RATE].
 *
 * Every buffer fades in over 2 ms from an exact zero, fades out to an exact zero on its last sample (no click), and is
 * peak-normalized to its own target level, at most 0.78 of full scale, so that relative loudness is chosen here and
 * not by the source mix (the projectile entering is the quietest, the hit taken the loudest).
 *
 * | Sound | Length | Peak |
 * |---|---|---|
 * | [Sfx.Fire] | 150 ms | 0.50 |
 * | [Sfx.ProjectileEnter] | 250 ms | 0.28 |
 * | [Sfx.HitDealt] | 400 ms | 0.70 |
 * | [Sfx.HitTaken] | 500 ms | 0.78 |
 * | [Sfx.Countdown] | 80 ms | 0.45 |
 * | [Sfx.Start] | 900 ms | 0.75 |
 * | [Sfx.Victory] | 1400 ms | 0.75 |
 * | [Sfx.Defeat] | 1450 ms | 0.70 |
 * | [Sfx.Draw] | 1150 ms | 0.65 |
 *
 * Total: 6.28 s, that is 276 948 samples (about 554 KB of 16-bit PCM). Oscillators are recursive (one multiply-add per
 * sample and partial, no `sin` in the inner loop) and stop once their envelope falls below -100 dB.
 */
object Synth {
    const val SAMPLE_RATE = 44_100

    fun render(sfx: Sfx): ShortArray = when (sfx) {
        Sfx.Fire -> fire()
        Sfx.ProjectileEnter -> projectileEnter()
        Sfx.HitDealt -> hitDealt()
        Sfx.HitTaken -> hitTaken()
        Sfx.Countdown -> countdown()
        Sfx.Start -> start()
        Sfx.Victory -> victory()
        Sfx.Defeat -> defeat()
        Sfx.Draw -> draw()
    }

    // Short bright chime pluck: a free bar (partials 1, 2.756, 5.404) struck with a tiny noise click.
    private fun fire(): ShortArray = Mix(seconds = 0.150, seed = 0x51F1).run {
        partials(1318.51, amp = 1.0, decay = 0.045, set = CHIME)
        noise(center = 3500.0, q = 1.2, amp = 0.25, decay = 0.004)
        finish(peak = 0.50)
    }

    // Soft airy shimmer: band-passed breath with two high beating partials, swelling in then out. The quietest sound.
    private fun projectileEnter(): ShortArray = Mix(seconds = 0.250, seed = 0x0A1E).run {
        noise(center = 5000.0, q = 1.5, amp = 1.0, decay = 0.060, rise = 0.050)
        tone(2637.02, amp = 0.25, decay = 0.070, rise = 0.040)
        tone(2637.02 * 1.004, amp = 0.20, decay = 0.070, rise = 0.040)
        tone(3951.07, amp = 0.15, decay = 0.060, rise = 0.050)
        finish(peak = 0.28)
    }

    // Clear high bell, struck.
    private fun hitDealt(): ShortArray = Mix(seconds = 0.400, seed = 0x4D17).run {
        partials(1567.98, amp = 1.0, decay = 0.120, set = HAND_BELL, beat = 1.0015)
        noise(center = 4500.0, q = 1.5, amp = 0.30, decay = 0.003)
        finish(peak = 0.70)
    }

    // Low gong thud: inharmonic gong partials from C3 (phone speakers render the upper ones), a dull noise thump and
    // a short crack. The loudest sound.
    private fun hitTaken(): ShortArray = Mix(seconds = 0.500, seed = 0x7A4E).run {
        partials(130.81, amp = 1.0, decay = 0.220, set = GONG, beat = 1.003)
        noise(center = 400.0, q = 0.9, amp = 1.2, decay = 0.025)
        noise(center = 2200.0, q = 1.2, amp = 0.30, decay = 0.005)
        finish(peak = 0.78)
    }

    // Soft woodblock tick: two fast-decaying modes and a click.
    private fun countdown(): ShortArray = Mix(seconds = 0.080, seed = 0x71C4).run {
        tone(880.0, amp = 1.0, decay = 0.018)
        tone(880.0 * 2.75, amp = 0.35, decay = 0.008)
        noise(center = 1800.0, q = 2.0, amp = 0.40, decay = 0.003)
        finish(peak = 0.45)
    }

    // Gong swell: a gong rubbed rather than struck (slow rise), with a faint high halo.
    private fun start(): ShortArray = Mix(seconds = 0.900, seed = 0x57A7).run {
        partials(146.83, amp = 1.0, decay = 0.300, set = GONG, rise = 0.120, beat = 1.003)
        tone(1174.66, amp = 0.12, decay = 0.250, rise = 0.200)
        tone(1760.0, amp = 0.08, decay = 0.250, rise = 0.200)
        noise(center = 350.0, q = 1.0, amp = 0.30, decay = 0.150, rise = 0.100)
        finish(peak = 0.75)
    }

    // Ascending bell arpeggio on C major pentatonic (C5 D5 E5 G5 A5), resolving on C6.
    private fun victory(): ShortArray = Mix(seconds = 1.400, seed = 0x1C70).run {
        val notes = doubleArrayOf(523.25, 587.33, 659.26, 783.99, 880.0)
        for (i in notes.indices) {
            val at = i * 0.100
            partials(notes[i], amp = 0.7, decay = 0.220, set = BRIGHT_BELL, at = at)
            noise(center = 4000.0, q = 1.5, amp = 0.08, decay = 0.003, at = at)
        }
        partials(1046.50, amp = 1.0, decay = 0.300, set = BRIGHT_BELL, at = 0.500, beat = 1.0015)
        noise(center = 4000.0, q = 1.5, amp = 0.10, decay = 0.003, at = 0.500)
        finish(peak = 0.75)
    }

    // Descending A minor bells (E5 C5 A4), slower, with a prominent minor tierce, the last one ringing longer.
    private fun defeat(): ShortArray = Mix(seconds = 1.450, seed = 0xDEFE).run {
        partials(659.26, amp = 0.8, decay = 0.250, set = MINOR_BELL)
        partials(523.25, amp = 0.8, decay = 0.250, set = MINOR_BELL, at = 0.240)
        partials(440.0, amp = 1.0, decay = 0.380, set = MINOR_BELL, at = 0.480, beat = 1.002)
        finish(peak = 0.70)
    }

    // Suspended chord, unresolved: D5 and G5 (a fourth) on soft bells, lightly strummed.
    private fun draw(): ShortArray = Mix(seconds = 1.150, seed = 0xD4A3).run {
        partials(587.33, amp = 1.0, decay = 0.330, set = SOFT_BELL, beat = 1.002)
        partials(783.99, amp = 0.9, decay = 0.330, set = SOFT_BELL, at = 0.030, beat = 1.002)
        finish(peak = 0.65)
    }

    /** One partial: frequency ratio to the fundamental, relative amplitude, decay time relative to the fundamental's. */
    private class Partial(val ratio: Double, val amp: Double, val decay: Double)

    /** Free-free bar (glockenspiel, chime): very inharmonic, upper modes die fast. */
    private val CHIME = arrayOf(Partial(1.0, 1.0, 1.0), Partial(2.756, 0.35, 0.45), Partial(5.404, 0.10, 0.25))

    /** Small church bell: hum, prime, minor tierce, quint, nominal and two upper partials. */
    private val HAND_BELL = arrayOf(
        Partial(0.5, 0.15, 1.3), Partial(1.0, 1.0, 1.0), Partial(1.19, 0.15, 0.7), Partial(1.5, 0.20, 0.6),
        Partial(2.0, 0.50, 0.55), Partial(2.74, 0.18, 0.35), Partial(3.76, 0.08, 0.25),
    )

    /** Bell without tierce, for major melodies. */
    private val BRIGHT_BELL = arrayOf(
        Partial(0.5, 0.12, 1.3), Partial(1.0, 1.0, 1.0), Partial(2.0, 0.45, 0.6), Partial(2.76, 0.20, 0.4),
        Partial(4.07, 0.08, 0.25), Partial(5.4, 0.04, 0.15),
    )

    /** Darker bell: strong hum and minor tierce, few upper partials. */
    private val MINOR_BELL = arrayOf(
        Partial(0.5, 0.30, 1.2), Partial(1.0, 1.0, 1.0), Partial(1.19, 0.40, 0.8), Partial(1.5, 0.15, 0.6),
        Partial(2.0, 0.25, 0.5), Partial(2.98, 0.08, 0.3),
    )

    /** Mellow bell, nearly harmonic. */
    private val SOFT_BELL = arrayOf(
        Partial(0.5, 0.15, 1.2), Partial(1.0, 1.0, 1.0), Partial(2.0, 0.30, 0.6), Partial(3.0, 0.10, 0.4),
    )

    /** Flat gong: dense inharmonic spectrum, upper partials decaying first. */
    private val GONG = arrayOf(
        Partial(1.0, 1.0, 1.0), Partial(1.47, 0.70, 0.8), Partial(2.09, 0.60, 0.7), Partial(2.56, 0.45, 0.55),
        Partial(3.37, 0.35, 0.45), Partial(4.53, 0.20, 0.35), Partial(5.8, 0.12, 0.25),
    )

    /** Rise time of a struck partial: short enough to sound like a strike, long enough to never click. */
    private const val STRIKE_RISE = 0.0006

    /** Envelope level (before normalization) under which an oscillator stops: -100 dB. */
    private const val SILENCE = 1e-5

    private const val FADE_IN_SECONDS = 0.002
    private const val FADE_OUT_MIN_SECONDS = 0.005
    private const val FADE_OUT_MAX_SECONDS = 0.060

    /** Highest partial rendered: above it the recursion is still exact, but nothing useful is left on a phone. */
    private const val MAX_FREQUENCY = 16_000.0

    /** Accumulation buffer for one sound. */
    private class Mix(seconds: Double, seed: Int) {
        val out = DoubleArray((seconds * SAMPLE_RATE).roundToInt())
        private var noiseState = if (seed == 0) 1 else seed

        /**
         * Adds a sine of [freq] Hz with the envelope `amp × (1 − e^(−t/rise)) × e^(−t/decay)`, starting [at] seconds.
         * The envelope is zero at its start, so a partial never clicks in.
         */
        fun tone(freq: Double, amp: Double, decay: Double, at: Double = 0.0, rise: Double = STRIKE_RISE) {
            if (freq <= 0.0 || freq >= MAX_FREQUENCY) return
            val start = (at * SAMPLE_RATE).roundToInt()
            val w = 2.0 * PI * freq / SAMPLE_RATE
            val halfSin = sin(w / 2.0)
            val twoCos = 2.0 - 4.0 * halfSin * halfSin // 2 cos(w), exact identity, only sin used
            // Recursive oscillator: y[n+1] = 2 cos(w) y[n] − y[n−1], with y[0] = sin(0) and y[−1] = sin(−w).
            var previous = -sin(w)
            var current = 0.0
            val decayStep = exp(-1.0 / (decay * SAMPLE_RATE))
            val riseStep = exp(-1.0 / (rise * SAMPLE_RATE))
            var level = amp
            var riseLeft = 1.0
            for (i in start until out.size) {
                out[i] += current * level * (1.0 - riseLeft)
                val next = twoCos * current - previous
                previous = current
                current = next
                level *= decayStep
                riseLeft *= riseStep
                if (level < SILENCE) break
            }
        }

        /** Adds a struck or swelling set of [set] partials over the fundamental [freq]; [beat] adds a detuned twin. */
        fun partials(
            freq: Double,
            amp: Double,
            decay: Double,
            set: Array<Partial>,
            at: Double = 0.0,
            rise: Double = STRIKE_RISE,
            beat: Double = 0.0,
        ) {
            for (p in set) {
                tone(freq * p.ratio, amp * p.amp, decay * p.decay, at, rise)
                // A slightly detuned twin gives the slow beating of a real bell or gong (asymmetric casting).
                if (beat > 0.0) tone(freq * p.ratio * beat, amp * p.amp * 0.5, decay * p.decay, at, rise)
            }
        }

        /** Adds deterministic white noise through a band-pass (Chamberlin state-variable filter) around [center] Hz. */
        fun noise(center: Double, q: Double, amp: Double, decay: Double, at: Double = 0.0, rise: Double = STRIKE_RISE) {
            val start = (at * SAMPLE_RATE).roundToInt()
            val f = 2.0 * sin(PI * center / SAMPLE_RATE)
            val damping = 1.0 / q
            check(f < 2.0 - damping) { "unstable noise filter: center=$center q=$q" }
            val decayStep = exp(-1.0 / (decay * SAMPLE_RATE))
            val riseStep = exp(-1.0 / (rise * SAMPLE_RATE))
            var level = amp
            var riseLeft = 1.0
            var low = 0.0
            var band = 0.0
            for (i in start until out.size) {
                low += f * band
                val high = nextNoise() - low - damping * band
                band += f * high
                out[i] += band * level * (1.0 - riseLeft)
                level *= decayStep
                riseLeft *= riseStep
                if (level < SILENCE) break
            }
        }

        /** Xorshift32: uniform in [−1, 1], same sequence on every platform. */
        private fun nextNoise(): Double {
            var x = noiseState
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            noiseState = x
            return x.toDouble() / Int.MAX_VALUE
        }

        /** Normalizes to [peak] of full scale, applies the fades and quantizes to 16 bits. */
        fun finish(peak: Double): ShortArray {
            var max = 0.0
            for (v in out) max = maxOf(max, abs(v))
            val gain = if (max > 0.0) peak / max * Short.MAX_VALUE else 0.0
            val fadeIn = (FADE_IN_SECONDS * SAMPLE_RATE).roundToInt()
            val fadeOut = (out.size / 10).coerceIn(
                (FADE_OUT_MIN_SECONDS * SAMPLE_RATE).roundToInt(),
                (FADE_OUT_MAX_SECONDS * SAMPLE_RATE).roundToInt(),
            )
            val last = out.size - 1
            return ShortArray(out.size) { i ->
                var g = gain
                if (i < fadeIn) g *= i.toDouble() / fadeIn
                val fromEnd = last - i
                if (fromEnd < fadeOut) g *= fromEnd.toDouble() / fadeOut
                (out[i] * g).roundToInt().toShort()
            }
        }
    }
}
