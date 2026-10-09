package epheis.lyphossyss.kysheon.core

import kotlin.math.sqrt

/**
 * Swept collision primitives on raw `Double` coordinates (no vector objects, so no allocation).
 * Only `+ - * /` and [sqrt] are used: both are IEEE 754 correctly rounded, so results are bit-identical
 * on the JVM and Kotlin/Native.
 */
object Collision {

    /** Returned by [sweptCircleTime] when the segment never touches the circle. */
    const val NO_HIT: Double = -1.0

    /**
     * Earliest fraction `t` in [0, 1] at which the point moving from (x0, y0) to (x1, y1) is at distance
     * ≤ [radius] from (cx, cy), or [NO_HIT].
     *
     * Moving circles are handled by the caller: pass the motion of one circle relative to the other and the sum
     * of both radii. A start point already inside the circle returns 0. Tangency (distance exactly [radius])
     * counts as a hit. A zero-length segment is a plain point-in-circle test.
     */
    fun sweptCircleTime(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        cx: Double, cy: Double,
        radius: Double,
    ): Double {
        val fx = x0 - cx
        val fy = y0 - cy
        val c = fx * fx + fy * fy - radius * radius
        if (c <= 0.0) return 0.0
        val dx = x1 - x0
        val dy = y1 - y0
        val a = dx * dx + dy * dy
        if (a == 0.0) return NO_HIT
        // |f + t·d|² = r²  ⇔  a·t² + 2·b·t + c = 0, with b = f·d.
        val b = fx * dx + fy * dy
        if (b >= 0.0) return NO_HIT // moving away from (or parallel to) the center while outside
        val disc = b * b - a * c
        if (disc < 0.0) return NO_HIT
        val t = (-b - sqrt(disc)) / a
        return if (t <= 1.0) t else NO_HIT
    }

    /** `true` if the segment from (x0, y0) to (x1, y1) touches the circle. */
    fun segmentHitsCircle(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        cx: Double, cy: Double,
        radius: Double,
    ): Boolean = sweptCircleTime(x0, y0, x1, y1, cx, cy, radius) != NO_HIT
}
