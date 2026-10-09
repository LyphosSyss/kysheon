package epheis.lyphossyss.kysheon.link.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageCodecTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** Every message with its exact expected encoding, written by hand. */
    private val vectors: List<Pair<Message, ByteArray>> = listOf(
        Message.Hello(protocolVersion = 1, role = Role.Host, aspectMilli = 2222) to
            bytes(1, 1, 0, 0x08, 0xAE),
        Message.Ping(seq = 7, t0Micros = 0x0102030405060708) to
            bytes(2, 0, 7, 1, 2, 3, 4, 5, 6, 7, 8),
        Message.Pong(seq = 255, t1Micros = -1, t2Micros = Long.MAX_VALUE) to
            bytes(
                2, 1, 0xFF,
                0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
                0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
            ),
        Message.Ready to bytes(3),
        Message.Start(atHostMicros = 1_000_000) to
            bytes(4, 0, 0, 0, 0, 0, 0x0F, 0x42, 0x40),
        Message.Handoff(projectileId = 0xBEEF, yQ = 0x8000, vxQ = -1, vyQ = 1000, exitAtHostMicros = 42) to
            bytes(5, 0xBE, 0xEF, 0x80, 0x00, 0xFF, 0xFF, 0x03, 0xE8, 0, 0, 0, 0, 0, 0, 0, 42),
        Message.Hit(projectileId = 1, atHostMicros = Long.MIN_VALUE) to
            bytes(6, 0, 1, 0x80, 0, 0, 0, 0, 0, 0, 0),
        Message.End(winner = Role.Guest, reason = EndReason.KnockOut) to
            bytes(7, 1, 0),
        Message.Heartbeat(seq = 200) to
            bytes(8, 200),
    )

    @Test
    fun encodesEveryMessageByteForByte() {
        for ((message, expected) in vectors) {
            assertContentEquals(expected, MessageCodec.encode(message), "encode $message")
        }
    }

    @Test
    fun decodesEveryMessageByteForByte() {
        for ((message, frame) in vectors) {
            assertEquals(DecodeResult.Ok(message), MessageCodec.decode(frame), "decode $message")
        }
    }

    /** Exhaustive `when`: adding a message type breaks compilation here until a vector is added. */
    private fun typeIndex(message: Message): Int = when (message) {
        is Message.Hello -> 0
        is Message.Ping -> 1
        is Message.Pong -> 2
        Message.Ready -> 3
        is Message.Start -> 4
        is Message.Handoff -> 5
        is Message.Hit -> 6
        is Message.End -> 7
        is Message.Heartbeat -> 8
    }

    @Test
    fun coversEveryMessageType() {
        assertEquals((0..8).toSet(), vectors.map { typeIndex(it.first) }.toSet())
    }

    @Test
    fun everyFrameFitsTheDefaultMtu() {
        for ((message, _) in vectors) {
            assertTrue(MessageCodec.encode(message).size <= MAX_FRAME_BYTES, "$message exceeds $MAX_FRAME_BYTES bytes")
        }
    }

    @Test
    fun rejectsEmptyFrame() {
        assertEquals(DecodeResult.Malformed(DecodeError.Empty), MessageCodec.decode(ByteArray(0)))
    }

    @Test
    fun rejectsUnknownCode() {
        assertEquals(DecodeResult.Malformed(DecodeError.UnknownCode), MessageCodec.decode(bytes(0)))
        assertEquals(DecodeResult.Malformed(DecodeError.UnknownCode), MessageCodec.decode(bytes(9)))
        assertEquals(DecodeResult.Malformed(DecodeError.UnknownCode), MessageCodec.decode(bytes(0xFF)))
    }

    @Test
    fun rejectsWrongLengths() {
        for ((message, frame) in vectors) {
            val truncated = frame.copyOf(frame.size - 1)
            val extended = frame.copyOf(frame.size + 1)
            if (truncated.isNotEmpty()) {
                assertEquals(DecodeResult.Malformed(DecodeError.BadLength), MessageCodec.decode(truncated), "truncated $message")
            }
            assertEquals(DecodeResult.Malformed(DecodeError.BadLength), MessageCodec.decode(extended), "extended $message")
        }
    }

    @Test
    fun rejectsInvalidFieldValues() {
        assertEquals(DecodeResult.Malformed(DecodeError.BadField), MessageCodec.decode(bytes(1, 1, 2, 0x08, 0xAE)), "role 2")
        assertEquals(DecodeResult.Malformed(DecodeError.BadField), MessageCodec.decode(bytes(1, 1, 0, 0, 0)), "aspect 0")
        assertEquals(DecodeResult.Malformed(DecodeError.BadField), MessageCodec.decode(bytes(2, 2, 0)), "ping kind 2")
        assertEquals(DecodeResult.Malformed(DecodeError.BadField), MessageCodec.decode(bytes(7, 2, 0)), "winner 2")
        assertEquals(DecodeResult.Malformed(DecodeError.BadField), MessageCodec.decode(bytes(7, 0, 9)), "reason 9")
    }

    @Test
    fun reportsIncompatibleVersionWhateverTheLength() {
        assertEquals(DecodeResult.IncompatibleVersion(2), MessageCodec.decode(bytes(1, 2)))
        assertEquals(DecodeResult.IncompatibleVersion(0), MessageCodec.decode(bytes(1, 0, 0, 0x08, 0xAE)))
        assertEquals(DecodeResult.IncompatibleVersion(9), MessageCodec.decode(ByteArray(40) { if (it == 0) 1 else 9 }))
        assertEquals(DecodeResult.Malformed(DecodeError.BadLength), MessageCodec.decode(bytes(1)))
    }

    @Test
    fun signedVelocitiesSurviveTheRoundTrip() {
        for (v in listOf(Short.MIN_VALUE.toInt(), -1000, -1, 0, 1, 1000, Short.MAX_VALUE.toInt())) {
            val message = Message.Handoff(projectileId = 0, yQ = 0, vxQ = v, vyQ = -v.coerceAtLeast(-Short.MAX_VALUE), exitAtHostMicros = 0)
            assertEquals(DecodeResult.Ok(message), MessageCodec.decode(MessageCodec.encode(message)))
        }
    }

    @Test
    fun constructorsRejectOutOfRangeFields() {
        assertFailsWith<IllegalArgumentException> { Message.Ping(seq = 256, t0Micros = 0) }
        assertFailsWith<IllegalArgumentException> { Message.Heartbeat(seq = -1) }
        assertFailsWith<IllegalArgumentException> { Message.Hit(projectileId = 0x10000, atHostMicros = 0) }
        assertFailsWith<IllegalArgumentException> { Message.Handoff(0, 0, vxQ = 40_000, vyQ = 0, exitAtHostMicros = 0) }
        assertFailsWith<IllegalArgumentException> { Message.Hello(protocolVersion = 1, role = Role.Host, aspectMilli = 0) }
    }
}
