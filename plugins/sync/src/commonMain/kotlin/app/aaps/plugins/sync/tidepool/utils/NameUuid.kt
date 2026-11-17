package app.aaps.plugins.sync.tidepool.utils

/**
 * A name based UUID (version 3, MD5), the same as Java's `UUID.nameUUIDFromBytes(name.toByteArray())`.
 *
 * Tidepool deduplicates records on `origin.id`, and that id has always been made this way on Android.
 * It must stay byte for byte the same on every platform: a different id would upload every record a
 * second time. Kotlin has no MD5 in its common library, so it is written out here. The constants are
 * a fixed table, not computed with `sin()`, so floating point cannot change them on Kotlin/Native.
 *
 * Pure: no clock, no state.
 */
internal object NameUuid {

    /** UUID v3 of the UTF-8 bytes of [name], lower case, as `xxxxxxxx-xxxx-3xxx-yxxx-xxxxxxxxxxxx`. */
    fun fromName(name: String): String {
        val md5 = md5(name.encodeToByteArray())
        md5[6] = ((md5[6].toInt() and 0x0f) or 0x30).toByte() // version 3
        md5[8] = ((md5[8].toInt() and 0x3f) or 0x80).toByte() // IETF variant
        val hex = md5.toHex()
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    /** MD5 (RFC 1321) of [input]. */
    fun md5(input: ByteArray): ByteArray {
        val length = input.size
        val padding = ((56 - (length + 1) % 64) + 64) % 64
        val total = length + 1 + padding + 8
        val buffer = ByteArray(total)
        input.copyInto(buffer)
        buffer[length] = 0x80.toByte()
        val bitLength = length.toLong() * 8
        for (i in 0 until 8) buffer[total - 8 + i] = (bitLength ushr (8 * i)).toByte()

        var a0 = 0x67452301
        var b0 = 0xefcdab89L.toInt()
        var c0 = 0x98badcfeL.toInt()
        var d0 = 0x10325476
        val words = IntArray(16)
        for (chunk in 0 until total step 64) {
            for (i in 0 until 16) {
                val o = chunk + i * 4
                words[i] = (buffer[o].toInt() and 0xff) or
                    ((buffer[o + 1].toInt() and 0xff) shl 8) or
                    ((buffer[o + 2].toInt() and 0xff) shl 16) or
                    ((buffer[o + 3].toInt() and 0xff) shl 24)
            }
            var a = a0
            var b = b0
            var c = c0
            var d = d0
            for (i in 0 until 64) {
                val f: Int
                val g: Int
                when (i / 16) {
                    0 -> {
                        f = (b and c) or (b.inv() and d)
                        g = i
                    }

                    1 -> {
                        f = (d and b) or (d.inv() and c)
                        g = (5 * i + 1) % 16
                    }

                    2 -> {
                        f = b xor c xor d
                        g = (3 * i + 5) % 16
                    }

                    else -> {
                        f = c xor (b or d.inv())
                        g = (7 * i) % 16
                    }
                }
                val sum = f + a + K[i] + words[g]
                a = d
                d = c
                c = b
                b += sum.rotateLeft(S[i])
            }
            a0 += a
            b0 += b
            c0 += c
            d0 += d
        }
        val out = ByteArray(16)
        intArrayOf(a0, b0, c0, d0).forEachIndexed { index, word ->
            for (i in 0 until 4) out[index * 4 + i] = (word ushr (8 * i)).toByte()
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private val S = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
    )

    private val K = longArrayOf(
        0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
        0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
        0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
        0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
        0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
        0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
        0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
        0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
    ).map { it.toInt() }.toIntArray()
}
