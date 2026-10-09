package epheis.lyphossyss.kysheon.link.protocol

/** Big-endian writer over a buffer of known final size. */
internal class ByteWriter(size: Int) {
    private val bytes = ByteArray(size)
    private var position = 0

    fun u8(value: Int): ByteWriter = apply {
        bytes[position++] = value.toByte()
    }

    fun u16(value: Int): ByteWriter = apply {
        bytes[position++] = (value ushr 8).toByte()
        bytes[position++] = value.toByte()
    }

    fun i64(value: Long): ByteWriter = apply {
        for (shift in 56 downTo 0 step 8) {
            bytes[position++] = (value ushr shift).toByte()
        }
    }

    fun build(): ByteArray {
        check(position == bytes.size) { "wrote $position of ${bytes.size} bytes" }
        return bytes
    }
}

/** Big-endian reader. Callers check the frame length before reading. */
internal class ByteReader(private val bytes: ByteArray, private var position: Int = 0) {

    fun u8(): Int = bytes[position++].toInt() and 0xFF

    fun u16(): Int = (u8() shl 8) or u8()

    fun i16(): Int = u16().toShort().toInt()

    fun i64(): Long {
        var value = 0L
        repeat(8) { value = (value shl 8) or u8().toLong() }
        return value
    }
}
