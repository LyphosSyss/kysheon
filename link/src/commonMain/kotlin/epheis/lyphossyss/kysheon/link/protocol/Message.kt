package epheis.lyphossyss.kysheon.link.protocol

/** Bumped on any wire change. The first two bytes of HELLO (`[1][version]`) never change across versions. */
const val PROTOCOL_VERSION: Int = 1

/** Every frame fits the default ATT MTU (23 − 3), so the protocol works even if MTU negotiation fails. */
const val MAX_FRAME_BYTES: Int = 20

enum class Role(val wire: Int) {
    Host(0),
    Guest(1);

    val opposite: Role get() = if (this == Host) Guest else Host

    companion object {
        fun fromWire(value: Int): Role? = entries.firstOrNull { it.wire == value }
    }
}

enum class EndReason(val wire: Int) {
    KnockOut(0),
    Forfeit(1);

    companion object {
        fun fromWire(value: Int): EndReason? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Protocol v1 messages. Big-endian, fixed sizes, one code byte. Field ranges are enforced at construction,
 * so a message that exists can always be encoded. Times are microseconds of a monotonic clock:
 * PING/PONG carry each side's own clock, every other timestamp is expressed on the host clock.
 */
sealed interface Message {

    /** Code 1, 5 bytes. [aspectMilli]: logical height / width of the sender's half, × 1000. */
    data class Hello(val protocolVersion: Int, val role: Role, val aspectMilli: Int) : Message {
        init {
            require(protocolVersion in 0..0xFF) { "protocolVersion out of u8: $protocolVersion" }
            require(aspectMilli in 1..0xFFFF) { "aspectMilli out of u16: $aspectMilli" }
        }
    }

    /** Code 2 kind 0, 11 bytes. [t0Micros] is the sender's clock at emission. */
    data class Ping(val seq: Int, val t0Micros: Long) : Message {
        init {
            require(seq in 0..0xFF) { "seq out of u8: $seq" }
        }
    }

    /**
     * Code 2 kind 1, 19 bytes. [t1Micros] reception and [t2Micros] emission, on the responder's clock.
     * `t0` is not echoed: the pinger finds it again through [seq] (keeps the frame within 20 bytes).
     */
    data class Pong(val seq: Int, val t1Micros: Long, val t2Micros: Long) : Message {
        init {
            require(seq in 0..0xFF) { "seq out of u8: $seq" }
        }
    }

    /** Code 3, 1 byte. */
    data object Ready : Message

    /** Code 4, 9 bytes. Common start instant on the host clock. Also used to resume after a pause. */
    data class Start(val atHostMicros: Long) : Message

    /**
     * Code 5, 17 bytes. A projectile leaves the sender's half.
     * [yQ]: normalized height (0..1) as u16, see [Quant.encodeY].
     * [vxQ], [vyQ]: velocity in logical widths per second × 1000, as i16, see [Quant.encodeVelocity].
     */
    data class Handoff(
        val projectileId: Int,
        val yQ: Int,
        val vxQ: Int,
        val vyQ: Int,
        val exitAtHostMicros: Long,
    ) : Message {
        init {
            require(projectileId in 0..0xFFFF) { "projectileId out of u16: $projectileId" }
            require(yQ in 0..0xFFFF) { "yQ out of u16: $yQ" }
            require(vxQ in Short.MIN_VALUE..Short.MAX_VALUE) { "vxQ out of i16: $vxQ" }
            require(vyQ in Short.MIN_VALUE..Short.MAX_VALUE) { "vyQ out of i16: $vyQ" }
        }
    }

    /** Code 6, 11 bytes. Sent by the half that received the projectile and decided the impact. */
    data class Hit(val projectileId: Int, val atHostMicros: Long) : Message {
        init {
            require(projectileId in 0..0xFFFF) { "projectileId out of u16: $projectileId" }
        }
    }

    /** Code 7, 3 bytes. */
    data class End(val winner: Role, val reason: EndReason) : Message

    /** Code 8, 2 bytes. Sent every second by both sides. */
    data class Heartbeat(val seq: Int) : Message {
        init {
            require(seq in 0..0xFF) { "seq out of u8: $seq" }
        }
    }
}
