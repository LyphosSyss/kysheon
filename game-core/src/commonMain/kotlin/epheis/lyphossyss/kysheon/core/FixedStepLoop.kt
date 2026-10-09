package epheis.lyphossyss.kysheon.core

/**
 * Fixed-step accumulator. Time is passed in (microseconds of a monotonic clock), never read.
 *
 * All bookkeeping is integer microseconds, so there is no drift: after N calls the number of steps run plus the
 * steps dropped always equals `elapsed / stepMicros` exactly.
 *
 * Catch-up is capped at [maxStepsPerAdvance] per call (avoids the spiral of death after a stall): the excess
 * whole steps are dropped and counted in [droppedSteps]; the sub-step remainder is kept so [alpha] stays smooth.
 */
class FixedStepLoop(
    val stepMicros: Long = 16_667L,
    val maxStepsPerAdvance: Int = 5,
) {
    init {
        require(stepMicros > 0) { "stepMicros must be > 0: $stepMicros" }
        require(maxStepsPerAdvance > 0) { "maxStepsPerAdvance must be > 0: $maxStepsPerAdvance" }
    }

    private var started = false
    private var lastMicros = 0L
    private var accumulatorMicros = 0L

    /** Steps run since the start. */
    var stepCount: Long = 0L
        private set

    /** Whole steps discarded by the catch-up cap since the start. */
    var droppedSteps: Long = 0L
        private set

    /** Interpolation factor in [0, 1) between the previous and the current simulated state. */
    val alpha: Double get() = accumulatorMicros.toDouble() / stepMicros.toDouble()

    /** (Re)starts the loop at [nowMicros] with an empty accumulator. Counters are kept. */
    fun reset(nowMicros: Long) {
        started = true
        lastMicros = nowMicros
        accumulatorMicros = 0L
    }

    /**
     * Accounts the time elapsed up to [nowMicros] and returns how many steps to run now (0..[maxStepsPerAdvance]).
     * The first call only starts the loop and returns 0. A clock going backwards counts as no time elapsed.
     * Prefer [advanceTo], which also runs the steps.
     */
    fun consume(nowMicros: Long): Int {
        if (!started) {
            reset(nowMicros)
            return 0
        }
        val delta = nowMicros - lastMicros
        if (delta > 0) {
            lastMicros = nowMicros
            accumulatorMicros += delta
        }
        var steps = accumulatorMicros / stepMicros
        accumulatorMicros -= steps * stepMicros
        if (steps > maxStepsPerAdvance) {
            droppedSteps += steps - maxStepsPerAdvance
            steps = maxStepsPerAdvance.toLong()
        }
        stepCount += steps
        return steps.toInt()
    }

    /** Runs [step] as many times as the elapsed time allows (capped) and returns that count. Inline: no allocation. */
    inline fun advanceTo(nowMicros: Long, step: () -> Unit): Int {
        val steps = consume(nowMicros)
        for (i in 0 until steps) step()
        return steps
    }
}
