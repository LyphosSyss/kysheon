package epheis.lyphossyss.kysheon.app.render

/**
 * Frame-time statistics for the debug overlay, allocation-free: a ring buffer of the last [capacity] frame
 * durations, percentiles recomputed every [refreshEvery] frames into a preallocated scratch array.
 */
class FrameStats(private val capacity: Int = 240, private val refreshEvery: Int = 30) {
    private val durations = LongArray(capacity)
    private val scratch = LongArray(capacity)
    private var count = 0
    private var next = 0
    private var sinceRefresh = 0
    private var lastFrameNanos = -1L

    var p50Micros: Long = 0
        private set
    var p95Micros: Long = 0
        private set
    var p99Micros: Long = 0
        private set

    /** Frames longer than 1.5 × the median: visible stutter. */
    var jankyFrames: Int = 0
        private set

    fun onFrame(frameTimeNanos: Long) {
        if (lastFrameNanos >= 0) {
            val micros = (frameTimeNanos - lastFrameNanos) / 1_000
            durations[next] = micros
            next = (next + 1) % capacity
            if (count < capacity) count++
            if (p50Micros > 0 && micros > p50Micros * 3 / 2) jankyFrames++
            if (++sinceRefresh >= refreshEvery) refresh()
        }
        lastFrameNanos = frameTimeNanos
    }

    private fun refresh() {
        sinceRefresh = 0
        durations.copyInto(scratch, endIndex = count)
        scratch.sort(0, count)
        p50Micros = scratch[(count - 1) * 50 / 100]
        p95Micros = scratch[(count - 1) * 95 / 100]
        p99Micros = scratch[(count - 1) * 99 / 100]
    }
}
