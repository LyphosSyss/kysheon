package epheis.lyphossyss.kysheon.link.time

import kotlin.time.TimeSource

/**
 * Monotonic time in microseconds, from an arbitrary origin. Never wall-clock time.
 * Injected everywhere so that tests run on virtual time.
 */
fun interface MonotonicClock {
    fun nowMicros(): Long
}

class SystemMonotonicClock : MonotonicClock {
    private val origin = TimeSource.Monotonic.markNow()

    override fun nowMicros(): Long = origin.elapsedNow().inWholeMicroseconds
}
