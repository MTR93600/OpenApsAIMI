package app.aaps.pump.omnipod.dashctl.comm.packet

import app.aaps.pump.omnipod.dashctl.comm.message.IncorrectPacketException
import kotlin.math.min

/**
 * One Bluetooth packet of a Dash message, at most [MAX_SIZE] bytes.
 *
 * Port of Android `app.aaps.pump.omnipod.common.bledriver.comm.packet.BlePacket`. The bytes are
 * the same. `java.nio.ByteBuffer` is replaced by explicit big-endian byte code.
 */
sealed class BlePacket {

    abstract val payload: ByteArray
    abstract fun toByteArray(): ByteArray

    companion object {

        const val MAX_SIZE = 20
    }
}

/**
 * The first packet of a message.
 *
 * Difference from Android: when the header plus [payload] does not fit in [BlePacket.MAX_SIZE]
 * bytes, Android throws `java.nio.BufferOverflowException` from [toByteArray]; this port throws
 * an `IndexOutOfBoundsException`. The splitter never builds such a packet.
 */
data class FirstBlePacket(
    val fullFragments: Int,
    override val payload: ByteArray,
    val size: Byte? = null,
    val crc32: Long? = null,
    val oneExtraPacket: Boolean = false
) : BlePacket() {

    override fun toByteArray(): ByteArray {
        // Same layout as Android's ByteBuffer code: the bytes after the last written one stay 0.
        val ret = ByteArray(MAX_SIZE)
        var pos = 0
        ret[pos++] = 0 // index
        ret[pos++] = fullFragments.toByte() // # of fragments except FirstBlePacket and LastOptionalPlusOneBlePacket
        crc32?.let {
            pos = ret.putIntBigEndian(pos, it.toInt())
        }
        size?.let {
            ret[pos++] = it
        }
        ret.putBytes(pos, payload)
        return ret
    }

    companion object {

        fun parse(payload: ByteArray): FirstBlePacket {
            payload.assertSizeAtLeast(HEADER_SIZE_WITH_MIDDLE_PACKETS, 0)

            if (payload[0].toInt() != 0) {
                // most likely we lost the first packet.
                throw IncorrectPacketException(payload, 0)
            }
            val fullFragments = payload[1].toInt()
            require(fullFragments < MAX_FRAGMENTS) { "Received more than $MAX_FRAGMENTS fragments" }

            payload.assertSizeAtLeast(HEADER_SIZE_WITHOUT_MIDDLE_PACKETS, 0)

            return when {

                fullFragments == 0      -> {
                    val rest = payload[6]
                    val end = min(rest + HEADER_SIZE_WITHOUT_MIDDLE_PACKETS, payload.size)
                    payload.assertSizeAtLeast(end, 0)
                    FirstBlePacket(
                        fullFragments = fullFragments,
                        payload = payload.copyOfRange(HEADER_SIZE_WITHOUT_MIDDLE_PACKETS, end),
                        crc32 = payload.copyOfRange(2, 6).readIntBigEndian().toUnsignedLong(),
                        size = rest,
                        oneExtraPacket = rest + HEADER_SIZE_WITHOUT_MIDDLE_PACKETS > end
                    )
                }

                // With middle packets
                payload.size < MAX_SIZE ->
                    throw IncorrectPacketException(payload, 0)

                else                    -> {
                    FirstBlePacket(
                        fullFragments = fullFragments,
                        payload = payload.copyOfRange(HEADER_SIZE_WITH_MIDDLE_PACKETS, MAX_SIZE)
                    )
                }
            }
        }

        private const val HEADER_SIZE_WITHOUT_MIDDLE_PACKETS = 7 // we are using all fields
        private const val HEADER_SIZE_WITH_MIDDLE_PACKETS = 2

        internal const val CAPACITY_WITHOUT_MIDDLE_PACKETS =
            MAX_SIZE - HEADER_SIZE_WITHOUT_MIDDLE_PACKETS // we are using all fields
        internal const val CAPACITY_WITH_MIDDLE_PACKETS =
            MAX_SIZE - HEADER_SIZE_WITH_MIDDLE_PACKETS // we are not using crc32 or size
        internal const val CAPACITY_WITH_THE_OPTIONAL_PLUS_ONE_PACKET = 18

        private const val MAX_FRAGMENTS = 15 // 15*20=300 bytes
    }
}

data class MiddleBlePacket(val index: Byte, override val payload: ByteArray) : BlePacket() {

    override fun toByteArray(): ByteArray {
        return byteArrayOf(index) + payload
    }

    companion object {

        fun parse(payload: ByteArray): MiddleBlePacket {
            payload.assertSizeAtLeast(MAX_SIZE)
            return MiddleBlePacket(
                index = payload[0],
                payload.copyOfRange(1, MAX_SIZE)
            )
        }

        internal const val CAPACITY = 19
    }
}

/**
 * The last packet of a message that has middle packets. It carries the size of its own part and
 * the CRC-32 of the whole message.
 *
 * Difference from Android: an over-long [payload] makes [toByteArray] throw an
 * `IndexOutOfBoundsException` here, where Android throws `java.nio.BufferOverflowException`.
 */
data class LastBlePacket(
    val index: Byte,
    val size: Byte,
    override val payload: ByteArray,
    val crc32: Long,
    val oneExtraPacket: Boolean = false
) : BlePacket() {

    override fun toByteArray(): ByteArray {
        val ret = ByteArray(MAX_SIZE)
        var pos = 0
        ret[pos++] = index
        ret[pos++] = size
        pos = ret.putIntBigEndian(pos, crc32.toInt())
        ret.putBytes(pos, payload)
        return ret
    }

    companion object {

        fun parse(payload: ByteArray): LastBlePacket {
            payload.assertSizeAtLeast(HEADER_SIZE)

            val rest = payload[1]
            val end = min(rest + HEADER_SIZE, payload.size)

            payload.assertSizeAtLeast(end)

            return LastBlePacket(
                index = payload[0],
                crc32 = payload.copyOfRange(2, 6).readIntBigEndian().toUnsignedLong(),
                oneExtraPacket = rest + HEADER_SIZE > end,
                size = rest,
                payload = payload.copyOfRange(HEADER_SIZE, end)
            )
        }

        private const val HEADER_SIZE = 6
        internal const val CAPACITY = MAX_SIZE - HEADER_SIZE
    }
}

data class LastOptionalPlusOneBlePacket(
    val index: Byte,
    override val payload: ByteArray,
    val size: Byte
) : BlePacket() {

    override fun toByteArray(): ByteArray {
        return byteArrayOf(index, size) + payload + ByteArray(MAX_SIZE - payload.size - 2)
    }

    companion object {

        fun parse(payload: ByteArray): LastOptionalPlusOneBlePacket {
            payload.assertSizeAtLeast(2)
            val size = payload[1].toInt()
            payload.assertSizeAtLeast(HEADER_SIZE + size)

            return LastOptionalPlusOneBlePacket(
                index = payload[0],
                payload = payload.copyOfRange(
                    HEADER_SIZE,
                    HEADER_SIZE + size
                ),
                size = size.toByte(),
            )
        }

        private const val HEADER_SIZE = 2
    }
}

private fun ByteArray.assertSizeAtLeast(size: Int, index: Byte? = null) {
    if (this.size < size) {
        throw IncorrectPacketException(this, index)
    }
}

/** Writes [value] big-endian at [offset], like `ByteBuffer.putInt`. Returns the next position. */
private fun ByteArray.putIntBigEndian(offset: Int, value: Int): Int {
    if (offset + 4 > size) throw IndexOutOfBoundsException("No room for an Int at $offset in $size bytes")
    this[offset] = (value shr 24).toByte()
    this[offset + 1] = (value shr 16).toByte()
    this[offset + 2] = (value shr 8).toByte()
    this[offset + 3] = value.toByte()
    return offset + 4
}

/** Copies [src] at [offset], like `ByteBuffer.put(ByteArray)`; throws when it does not fit. */
private fun ByteArray.putBytes(offset: Int, src: ByteArray) {
    if (offset + src.size > size) {
        throw IndexOutOfBoundsException("No room for ${src.size} bytes at $offset in $size bytes")
    }
    src.copyInto(this, offset)
}

/** Reads the first 4 bytes as a big-endian Int, like `ByteBuffer.wrap(this).int`. */
private fun ByteArray.readIntBigEndian(): Int =
    (this[0].toInt() and 0xff shl 24) or
        (this[1].toInt() and 0xff shl 16) or
        (this[2].toInt() and 0xff shl 8) or
        (this[3].toInt() and 0xff)
