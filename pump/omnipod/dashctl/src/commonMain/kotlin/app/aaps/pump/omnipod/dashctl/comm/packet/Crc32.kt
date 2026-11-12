package app.aaps.pump.omnipod.dashctl.comm.packet

/**
 * CRC-32 as in IEEE 802.3, the same as `java.util.zip.CRC32`: reflected polynomial 0xEDB88320,
 * start value 0xFFFFFFFF, final xor 0xFFFFFFFF.
 *
 * Plain Kotlin, so it runs on every target. It replaces `java.util.zip.CRC32`, which Android
 * uses in `PayloadSplitter` and `PayloadJoiner`. Like `CRC32.getValue()`, [value] is the
 * 32 bit result in the low bits of a Long, so it is never negative.
 */
class Crc32 {

    private var crc: Int = INITIAL

    /** Adds all bytes of [bytes] to the checksum. */
    fun update(bytes: ByteArray) {
        update(bytes, 0, bytes.size)
    }

    /** Adds [length] bytes of [bytes], starting at [offset], to the checksum. */
    fun update(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IndexOutOfBoundsException("offset=$offset length=$length size=${bytes.size}")
        }
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xff] xor (c ushr 8)
        }
        crc = c
    }

    /** The checksum of all bytes added since the start or the last [reset]. */
    val value: Long
        get() = (crc xor FINAL_XOR).toLong() and 0xffffffffL

    /** Starts again, as if no byte had been added. */
    fun reset() {
        crc = INITIAL
    }

    private companion object {

        const val POLYNOMIAL = 0xEDB88320.toInt()
        const val INITIAL = 0xFFFFFFFF.toInt()
        const val FINAL_XOR = 0xFFFFFFFF.toInt()

        val TABLE: IntArray = IntArray(256) { n ->
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) POLYNOMIAL xor (c ushr 1) else c ushr 1
            }
            c
        }
    }
}
