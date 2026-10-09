package epheis.lyphossyss.kysheon.link.protocol

sealed interface DecodeResult {
    data class Ok(val message: Message) : DecodeResult

    /** The peer speaks another protocol version. Detected before any length check. */
    data class IncompatibleVersion(val remoteVersion: Int) : DecodeResult

    data class Malformed(val error: DecodeError) : DecodeResult
}

enum class DecodeError { Empty, UnknownCode, BadLength, BadField }

/** Encoder / decoder for protocol v1. Never throws on input bytes. */
object MessageCodec {
    const val CODE_HELLO: Int = 1
    const val CODE_PING_PONG: Int = 2
    const val CODE_READY: Int = 3
    const val CODE_START: Int = 4
    const val CODE_HANDOFF: Int = 5
    const val CODE_HIT: Int = 6
    const val CODE_END: Int = 7
    const val CODE_HEARTBEAT: Int = 8

    private const val KIND_PING = 0
    private const val KIND_PONG = 1

    const val SIZE_HELLO: Int = 5
    const val SIZE_PING: Int = 11
    const val SIZE_PONG: Int = 19
    const val SIZE_READY: Int = 1
    const val SIZE_START: Int = 9
    const val SIZE_HANDOFF: Int = 17
    const val SIZE_HIT: Int = 11
    const val SIZE_END: Int = 3
    const val SIZE_HEARTBEAT: Int = 2

    fun encode(message: Message): ByteArray = when (message) {
        is Message.Hello -> ByteWriter(SIZE_HELLO)
            .u8(CODE_HELLO).u8(message.protocolVersion).u8(message.role.wire).u16(message.aspectMilli)
        is Message.Ping -> ByteWriter(SIZE_PING)
            .u8(CODE_PING_PONG).u8(KIND_PING).u8(message.seq).i64(message.t0Micros)
        is Message.Pong -> ByteWriter(SIZE_PONG)
            .u8(CODE_PING_PONG).u8(KIND_PONG).u8(message.seq).i64(message.t1Micros).i64(message.t2Micros)
        Message.Ready -> ByteWriter(SIZE_READY)
            .u8(CODE_READY)
        is Message.Start -> ByteWriter(SIZE_START)
            .u8(CODE_START).i64(message.atHostMicros)
        is Message.Handoff -> ByteWriter(SIZE_HANDOFF)
            .u8(CODE_HANDOFF).u16(message.projectileId).u16(message.yQ)
            .u16(message.vxQ and 0xFFFF).u16(message.vyQ and 0xFFFF).i64(message.exitAtHostMicros)
        is Message.Hit -> ByteWriter(SIZE_HIT)
            .u8(CODE_HIT).u16(message.projectileId).i64(message.atHostMicros)
        is Message.End -> ByteWriter(SIZE_END)
            .u8(CODE_END).u8(message.winner.wire).u8(message.reason.wire)
        is Message.Heartbeat -> ByteWriter(SIZE_HEARTBEAT)
            .u8(CODE_HEARTBEAT).u8(message.seq)
    }.build()

    fun decode(frame: ByteArray): DecodeResult {
        if (frame.isEmpty()) return DecodeResult.Malformed(DecodeError.Empty)
        val reader = ByteReader(frame, position = 1)
        return when (frame[0].toInt() and 0xFF) {
            CODE_HELLO -> decodeHello(frame, reader)
            CODE_PING_PONG -> decodePingPong(frame, reader)
            CODE_READY -> exact(frame, SIZE_READY) { Message.Ready }
            CODE_START -> exact(frame, SIZE_START) { Message.Start(reader.i64()) }
            CODE_HANDOFF -> exact(frame, SIZE_HANDOFF) {
                Message.Handoff(
                    projectileId = reader.u16(),
                    yQ = reader.u16(),
                    vxQ = reader.i16(),
                    vyQ = reader.i16(),
                    exitAtHostMicros = reader.i64(),
                )
            }
            CODE_HIT -> exact(frame, SIZE_HIT) { Message.Hit(reader.u16(), reader.i64()) }
            CODE_END -> exact(frame, SIZE_END) {
                val winner = Role.fromWire(reader.u8())
                val reason = EndReason.fromWire(reader.u8())
                if (winner == null || reason == null) null else Message.End(winner, reason)
            }
            CODE_HEARTBEAT -> exact(frame, SIZE_HEARTBEAT) { Message.Heartbeat(reader.u8()) }
            else -> DecodeResult.Malformed(DecodeError.UnknownCode)
        }
    }

    private fun decodeHello(frame: ByteArray, reader: ByteReader): DecodeResult {
        if (frame.size < 2) return DecodeResult.Malformed(DecodeError.BadLength)
        val version = reader.u8()
        if (version != PROTOCOL_VERSION) return DecodeResult.IncompatibleVersion(version)
        return exact(frame, SIZE_HELLO) {
            val role = Role.fromWire(reader.u8())
            val aspectMilli = reader.u16()
            if (role == null || aspectMilli == 0) null else Message.Hello(version, role, aspectMilli)
        }
    }

    private fun decodePingPong(frame: ByteArray, reader: ByteReader): DecodeResult {
        if (frame.size < 2) return DecodeResult.Malformed(DecodeError.BadLength)
        return when (reader.u8()) {
            KIND_PING -> exact(frame, SIZE_PING) { Message.Ping(reader.u8(), reader.i64()) }
            KIND_PONG -> exact(frame, SIZE_PONG) { Message.Pong(reader.u8(), reader.i64(), reader.i64()) }
            else -> DecodeResult.Malformed(DecodeError.BadField)
        }
    }

    /** Checks the exact size, then builds; a null result means an invalid field value. */
    private inline fun exact(frame: ByteArray, size: Int, build: () -> Message?): DecodeResult {
        if (frame.size != size) return DecodeResult.Malformed(DecodeError.BadLength)
        val message = build() ?: return DecodeResult.Malformed(DecodeError.BadField)
        return DecodeResult.Ok(message)
    }
}
