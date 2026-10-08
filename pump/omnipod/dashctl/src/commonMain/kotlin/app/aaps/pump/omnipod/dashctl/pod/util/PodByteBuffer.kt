package app.aaps.pump.omnipod.dashctl.pod.util

/**
 * Minimal big-endian byte buffer replacing `java.nio.ByteBuffer` for the Dash pod protocol.
 * Only the operations used by the protocol are implemented; all multi-byte values are
 * big-endian, matching `ByteBuffer`'s default byte order.
 */
internal class PodByteBuffer private constructor(
    private val bytes: ByteArray,
    private var position: Int = 0
) {

    fun put(b: Byte): PodByteBuffer {
        bytes[position++] = b
        return this
    }

    fun put(src: ByteArray): PodByteBuffer {
        src.copyInto(bytes, position)
        position += src.size
        return this
    }

    fun putShort(s: Short): PodByteBuffer {
        bytes[position++] = (s.toInt() shr 8).toByte()
        bytes[position++] = s.toByte()
        return this
    }

    fun putInt(i: Int): PodByteBuffer {
        bytes[position++] = (i shr 24).toByte()
        bytes[position++] = (i shr 16).toByte()
        bytes[position++] = (i shr 8).toByte()
        bytes[position++] = i.toByte()
        return this
    }

    fun array(): ByteArray = bytes

    operator fun get(index: Int): Byte = bytes[index]

    /** Kotlin synthetic-property equivalent of `ByteBuffer.getShort()` at position 0. */
    val short: Short
        get() = ((bytes[0].toInt() and 0xff shl 8) or (bytes[1].toInt() and 0xff)).toShort()

    /** Kotlin synthetic-property equivalent of `ByteBuffer.getInt()` at position 0. */
    val int: Int
        get() = (bytes[0].toInt() and 0xff shl 24) or
            (bytes[1].toInt() and 0xff shl 16) or
            (bytes[2].toInt() and 0xff shl 8) or
            (bytes[3].toInt() and 0xff)

    /** Kotlin synthetic-property equivalent of `ByteBuffer.getLong()` at position 0. */
    val long: Long
        get() = (bytes[0].toLong() and 0xff shl 56) or
            (bytes[1].toLong() and 0xff shl 48) or
            (bytes[2].toLong() and 0xff shl 40) or
            (bytes[3].toLong() and 0xff shl 32) or
            (bytes[4].toLong() and 0xff shl 24) or
            (bytes[5].toLong() and 0xff shl 16) or
            (bytes[6].toLong() and 0xff shl 8) or
            (bytes[7].toLong() and 0xff)

    companion object {
        fun allocate(capacity: Int): PodByteBuffer = PodByteBuffer(ByteArray(capacity))
        fun wrap(array: ByteArray): PodByteBuffer = PodByteBuffer(array)
    }
}
