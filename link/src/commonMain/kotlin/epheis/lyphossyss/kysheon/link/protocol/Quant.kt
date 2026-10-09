package epheis.lyphossyss.kysheon.link.protocol

import kotlin.math.roundToLong

/** Fixed-point conversions used by HANDOFF and HELLO. Decoding is exact: what is sent is what is received. */
object Quant {
    const val Y_SCALE: Int = 0xFFFF
    const val VELOCITY_SCALE: Double = 1000.0
    const val ASPECT_SCALE: Double = 1000.0

    /** Normalized height in [0, 1] → u16. Values outside are clamped. */
    fun encodeY(y: Double): Int {
        require(y.isFinite()) { "y must be finite: $y" }
        return (y.coerceIn(0.0, 1.0) * Y_SCALE).roundToLong().toInt()
    }

    fun decodeY(q: Int): Double = q.toDouble() / Y_SCALE

    /** Logical widths per second → i16 thousandths, clamped to ±32.767. */
    fun encodeVelocity(v: Double): Int {
        require(v.isFinite()) { "velocity must be finite: $v" }
        return (v * VELOCITY_SCALE).roundToLong()
            .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
            .toInt()
    }

    fun decodeVelocity(q: Int): Double = q / VELOCITY_SCALE

    /** Height / width ratio → u16 thousandths, clamped to [0.001, 65.535]. */
    fun encodeAspect(aspect: Double): Int {
        require(aspect.isFinite()) { "aspect must be finite: $aspect" }
        return (aspect * ASPECT_SCALE).roundToLong().coerceIn(1L, 0xFFFFL).toInt()
    }

    fun decodeAspect(q: Int): Double = q / ASPECT_SCALE
}
