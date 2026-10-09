package epheis.lyphossyss.kysheon.link.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class SerialSenderTest {

    /** Records every started write; [answers] scripts the synchronous answer of the next writes. */
    private class FakeWriter : PlatformWriter {
        val written = mutableListOf<Int>()
        val answers = ArrayDeque<() -> WriteStart>()

        override fun startWrite(frame: ByteArray): WriteStart {
            written += frame[0].toInt()
            return answers.removeFirstOrNull()?.invoke() ?: WriteStart.Started
        }
    }

    private class Harness(scope: CoroutineScope, capacity: Int = SerialSender.DEFAULT_CAPACITY) {
        val writer = FakeWriter()
        val outcomes = mutableListOf<WriteOutcome>()
        val sender = SerialSender(scope, writer, TIMEOUT, capacity) { outcomes += it }
    }

    private fun frame(id: Int) = byteArrayOf(id.toByte())

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis.milliseconds)
        runCurrent()
    }

    @Test
    fun writesOneFrameAtATimeInOrder() = runTest {
        val h = Harness(backgroundScope)
        (1..3).forEach { assertTrue(h.sender.offer(frame(it))) }
        runCurrent()
        assertEquals(listOf(1), h.writer.written, "only the first write may be in flight")

        h.sender.confirm(success = true)
        runCurrent()
        assertEquals(listOf(1, 2), h.writer.written)

        h.sender.confirm(success = true)
        runCurrent()
        h.sender.confirm(success = true)
        runCurrent()
        assertEquals(listOf(1, 2, 3), h.writer.written)
        assertEquals(List<WriteOutcome>(3) { WriteOutcome.Sent }, h.outcomes)
    }

    @Test
    fun waitsForConfirmationBeforeTheNextWrite() = runTest {
        val h = Harness(backgroundScope)
        h.sender.offer(frame(1))
        h.sender.offer(frame(2))
        runCurrent()
        advance(TIMEOUT_MS - 1)
        assertEquals(listOf(1), h.writer.written, "no second write without confirmation")
        assertTrue(h.outcomes.isEmpty())

        h.sender.confirm(success = true)
        runCurrent()
        assertEquals(listOf(1, 2), h.writer.written)
    }

    @Test
    fun timeoutReleasesTheQueue() = runTest {
        val h = Harness(backgroundScope)
        h.sender.offer(frame(1))
        h.sender.offer(frame(2))
        runCurrent()
        advance(TIMEOUT_MS)
        assertEquals(listOf(1, 2), h.writer.written)
        assertEquals(listOf<WriteOutcome>(WriteOutcome.TimedOut), h.outcomes)

        h.sender.confirm(success = true)
        runCurrent()
        assertEquals(listOf(WriteOutcome.TimedOut, WriteOutcome.Sent), h.outcomes)
    }

    @Test
    fun confirmationBeforeTheWriteStartsIsDiscarded() = runTest {
        val h = Harness(backgroundScope)
        h.sender.confirm(success = true)
        runCurrent()
        h.sender.offer(frame(1))
        h.sender.offer(frame(2))
        runCurrent()
        advance(TIMEOUT_MS / 2)
        assertEquals(listOf(1), h.writer.written, "a stale confirmation must not release the queue")
    }

    @Test
    fun rejectedStartFailsAndMovesOnImmediately() = runTest {
        val h = Harness(backgroundScope)
        h.writer.answers += { WriteStart.Rejected(201) }
        h.sender.offer(frame(1))
        h.sender.offer(frame(2))
        runCurrent()
        assertEquals(listOf(1, 2), h.writer.written)
        assertEquals(listOf<WriteOutcome>(WriteOutcome.Failed(201)), h.outcomes)
    }

    @Test
    fun throwingWriterIsReportedAndNeverEscapes() = runTest {
        val h = Harness(backgroundScope)
        val boom = IllegalStateException("adapter off")
        h.writer.answers += { throw boom }
        h.sender.offer(frame(1))
        h.sender.offer(frame(2))
        runCurrent()
        assertEquals(listOf(1, 2), h.writer.written)
        val failure = assertIs<WriteOutcome.Failed>(h.outcomes.single())
        assertEquals(boom, failure.cause)
    }

    @Test
    fun negativeConfirmationIsAFailureWithItsCode() = runTest {
        val h = Harness(backgroundScope)
        h.sender.offer(frame(1))
        runCurrent()
        h.sender.confirm(success = false, platformCode = 133)
        runCurrent()
        assertEquals(listOf<WriteOutcome>(WriteOutcome.Failed(133)), h.outcomes)
    }

    @Test
    fun throwingOutcomeCallbackDoesNotStopTheQueue() = runTest {
        val writer = FakeWriter()
        val sender = SerialSender(backgroundScope, writer, TIMEOUT) { error("diagnostics bug") }
        sender.offer(frame(1))
        sender.offer(frame(2))
        runCurrent()
        sender.confirm(success = true)
        runCurrent()
        assertEquals(listOf(1, 2), writer.written)
    }

    @Test
    fun tracksQueueDepthAndItsMaximum() = runTest {
        val h = Harness(backgroundScope)
        (1..5).forEach { h.sender.offer(frame(it)) }
        assertEquals(5, h.sender.depth.value)
        assertEquals(5, h.sender.maxDepth.value)

        runCurrent()
        assertEquals(4, h.sender.depth.value, "the frame in flight is not waiting any more")

        repeat(4) {
            h.sender.confirm(success = true)
            runCurrent()
        }
        assertEquals(0, h.sender.depth.value)
        assertEquals(5, h.sender.maxDepth.value)
    }

    @Test
    fun dropsFramesBeyondCapacity() = runTest {
        val h = Harness(backgroundScope, capacity = 2)
        assertTrue(h.sender.offer(frame(1)))
        assertTrue(h.sender.offer(frame(2)))
        assertFalse(h.sender.offer(frame(3)))
        assertEquals(listOf<WriteOutcome>(WriteOutcome.Dropped), h.outcomes)
        assertEquals(2, h.sender.depth.value)
    }

    @Test
    fun closeDropsPendingFramesAndRefusesNewOnes() = runTest {
        val h = Harness(backgroundScope)
        (1..3).forEach { h.sender.offer(frame(it)) }
        runCurrent()
        h.sender.close()
        assertEquals(List<WriteOutcome>(2) { WriteOutcome.Dropped }, h.outcomes, "frames 2 and 3 were still waiting")
        assertEquals(0, h.sender.depth.value)

        assertFalse(h.sender.offer(frame(4)))
        h.sender.confirm(success = true)
        h.sender.close()
        advance(TIMEOUT_MS * 2)
        assertEquals(listOf(1), h.writer.written)
        assertEquals(3, h.outcomes.size)
    }

    private companion object {
        const val TIMEOUT_MS = 500L
        val TIMEOUT = TIMEOUT_MS.milliseconds
    }
}
