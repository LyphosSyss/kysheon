package epheis.lyphossyss.kysheon.link.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Starts exactly one platform write (characteristic write, notification…). Called by [SerialSender] only,
 * never while a previous write is still waiting for its confirmation. May throw: the sender catches everything.
 */
fun interface PlatformWriter {
    fun startWrite(frame: ByteArray): WriteStart
}

/** Immediate answer of the platform when a write is started. */
sealed interface WriteStart {
    /** Accepted: the platform will confirm later through [SerialSender.confirm]. */
    data object Started : WriteStart

    /** Refused synchronously (busy, not connected, missing permission…). [platformCode] is the raw code, if any. */
    data class Rejected(val platformCode: Int? = null) : WriteStart
}

/** What became of one frame, reported to [SerialSender]'s `onOutcome`. */
sealed interface WriteOutcome {
    data object Sent : WriteOutcome

    /** Rejected at start, confirmed with an error status, or the writer threw [cause]. */
    data class Failed(val platformCode: Int? = null, val cause: Throwable? = null) : WriteOutcome

    /** No confirmation within the timeout: the queue moves on, the frame may or may not have left. */
    data object TimedOut : WriteOutcome

    /** Never handed to the platform: the sender was closed or its queue was full. */
    data object Dropped : WriteOutcome
}

/**
 * Serialized write queue shared by the BLE implementations.
 *
 * Frames go through a channel to a single consumer coroutine that performs ONE platform write at a time
 * ([PlatformWriter.startWrite]) and suspends until the platform confirms it ([confirm], usually called from a
 * Bluetooth callback thread) or [confirmTimeout] elapses. Platform stacks reject a write issued while the previous
 * one is in flight, hence this queue.
 *
 * Never throws to callers. [offer] and [confirm] are thread-safe and do not suspend. After [close], pending frames
 * are dropped and new ones are refused. One instance per connection: it is not reusable after [close].
 *
 * Confirmations carry no identifier (Android's `onCharacteristicWrite` / `onNotificationSent` do not either):
 * a confirmation arriving before a write starts is discarded, but a very late confirmation of a timed-out write
 * would be credited to the next one. With a timeout far above the normal confirmation delay this is harmless.
 *
 * @param scope runs the consumer coroutine. On Android it is the link's serial worker scope.
 * @param capacity maximum number of frames waiting (not counting the one in flight); beyond it frames are dropped,
 *   so that a stalled stack cannot make the queue grow without bound.
 * @param onOutcome called from the consumer coroutine (or from [offer]/[close] for [WriteOutcome.Dropped]).
 *   Exceptions thrown by it are swallowed.
 */
class SerialSender(
    scope: CoroutineScope,
    private val writer: PlatformWriter,
    private val confirmTimeout: Duration = DEFAULT_CONFIRM_TIMEOUT,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val onOutcome: (WriteOutcome) -> Unit = {},
) {
    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    private val confirmations = Channel<Confirmation>(Channel.UNLIMITED)
    private val mutableDepth = MutableStateFlow(0)
    private val mutableMaxDepth = MutableStateFlow(0)

    @Volatile
    private var closed = false

    /** Frames waiting to be written, excluding the one in flight. */
    val depth: StateFlow<Int> = mutableDepth.asStateFlow()

    /** Highest [depth] observed since creation. */
    val maxDepth: StateFlow<Int> = mutableMaxDepth.asStateFlow()

    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private val consumer: Job = scope.launch {
        for (frame in queue) {
            mutableDepth.update { it - 1 }
            report(writeOne(frame))
        }
    }

    /** Queues [frame]. Returns false (and reports [WriteOutcome.Dropped]) when closed or full. */
    fun offer(frame: ByteArray): Boolean {
        if (closed) return drop()
        val newDepth = mutableDepth.updateAndGet { it + 1 }
        if (newDepth > capacity) {
            mutableDepth.update { it - 1 }
            return drop()
        }
        if (queue.trySend(frame).isFailure) {
            mutableDepth.update { it - 1 }
            return drop()
        }
        mutableMaxDepth.update { maxOf(it, newDepth) }
        return true
    }

    /** Completion signal of the write in flight. Thread-safe; ignored when nothing is in flight. */
    fun confirm(success: Boolean, platformCode: Int? = null) {
        confirmations.trySend(Confirmation(success, platformCode))
    }

    /** Idempotent. Stops the consumer and drops every frame still waiting. */
    fun close() {
        if (closed) return
        closed = true
        consumer.cancel()
        queue.close()
        while (true) {
            if (queue.tryReceive().getOrNull() == null) break
            mutableDepth.update { it - 1 }
            report(WriteOutcome.Dropped)
        }
        confirmations.close()
    }

    private suspend fun writeOne(frame: ByteArray): WriteOutcome {
        // Discard confirmations that do not belong to the write about to start.
        while (confirmations.tryReceive().isSuccess) continue
        return try {
            when (val start = writer.startWrite(frame)) {
                is WriteStart.Rejected -> WriteOutcome.Failed(start.platformCode)
                WriteStart.Started -> {
                    val confirmation = withTimeoutOrNull(confirmTimeout) { confirmations.receive() }
                    when {
                        confirmation == null -> WriteOutcome.TimedOut
                        confirmation.success -> WriteOutcome.Sent
                        else -> WriteOutcome.Failed(confirmation.platformCode)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            WriteOutcome.Failed(cause = failure)
        }
    }

    private fun drop(): Boolean {
        report(WriteOutcome.Dropped)
        return false
    }

    private fun report(outcome: WriteOutcome) {
        try {
            onOutcome(outcome)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A diagnostics callback must never break the queue.
        }
    }

    private class Confirmation(val success: Boolean, val platformCode: Int?)

    companion object {
        val DEFAULT_CONFIRM_TIMEOUT: Duration = 1.seconds
        const val DEFAULT_CAPACITY: Int = 256
    }
}
