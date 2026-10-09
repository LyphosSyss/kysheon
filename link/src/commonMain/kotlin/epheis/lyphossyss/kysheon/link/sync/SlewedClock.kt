package epheis.lyphossyss.kysheon.link.sync

import epheis.lyphossyss.kysheon.link.time.MonotonicClock

/**
 * Game clock on the host timebase: `local + applied offset`.
 *
 * The first estimate is applied at once ([snapTo], before the game starts). Later estimates ([setTarget]) are
 * reached progressively: the applied offset moves by at most [maxSlewRate] × elapsed local time, so the game clock
 * runs at a rate within [1 − maxSlewRate, 1 + maxSlewRate]: it never jumps and never goes backwards.
 */
class SlewedClock(
    private val local: MonotonicClock,
    private val maxSlewRate: Double = 0.05,
) : MonotonicClock {

    init {
        require(maxSlewRate > 0.0 && maxSlewRate < 1.0) { "maxSlewRate must be in (0, 1): $maxSlewRate" }
    }

    private var lastLocal = local.nowMicros()

    /** Offset currently applied, in microseconds. */
    var appliedOffsetMicros: Long = 0
        private set

    /** Offset being converged to, in microseconds. */
    var targetOffsetMicros: Long = 0
        private set

    fun snapTo(offsetMicros: Long) {
        advance(local.nowMicros())
        appliedOffsetMicros = offsetMicros
        targetOffsetMicros = offsetMicros
    }

    fun setTarget(offsetMicros: Long) {
        advance(local.nowMicros())
        targetOffsetMicros = offsetMicros
    }

    override fun nowMicros(): Long {
        val now = local.nowMicros()
        advance(now)
        return now + appliedOffsetMicros
    }

    private fun advance(now: Long) {
        val elapsed = now - lastLocal
        if (elapsed <= 0) return
        lastLocal = now
        val maxStep = (elapsed * maxSlewRate).toLong()
        val remaining = targetOffsetMicros - appliedOffsetMicros
        appliedOffsetMicros += remaining.coerceIn(-maxStep, maxStep)
    }
}
