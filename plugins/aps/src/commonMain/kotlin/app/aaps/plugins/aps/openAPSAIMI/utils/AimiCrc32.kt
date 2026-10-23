package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * IEEE CRC-32, the checksum ZIP and gzip files carry. Same result as `java.util.zip.CRC32` on the
 * same bytes.
 *
 * Pure Kotlin so that it runs on every target. It can be fed in pieces with [update], which is what
 * the support package ZIP writer needs: it checksums a file of several megabytes while it streams
 * it, without ever holding it whole.
 */
internal class AimiCrc32 {

    private var crc = INIT

    /** Adds [length] bytes of [bytes], starting at [offset], to the checksum. */
    fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        var c = crc
        for (i in offset until offset + length) {
            c = TABLE[(c xor bytes[i].toInt()) and 0xFF] xor (c ushr 8)
        }
        crc = c
    }

    /** The checksum of everything given to [update] since the last [reset]. */
    val value: Int get() = crc xor INIT

    /** Starts again, as if nothing had been given. */
    fun reset() {
        crc = INIT
    }

    companion object {

        /** CRC-32 of [bytes] in one call. */
        fun of(bytes: ByteArray): Int = AimiCrc32().apply { update(bytes) }.value

        private const val INIT = 0xFFFFFFFF.toInt()

        private val TABLE: IntArray = IntArray(256) { i ->
            var c = i
            repeat(8) {
                c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1
            }
            c
        }
    }
}
