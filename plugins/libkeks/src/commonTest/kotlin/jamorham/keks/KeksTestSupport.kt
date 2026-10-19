package jamorham.keks

import jamorham.keks.crypto.KeksEcPoint
import jamorham.keks.crypto.KeksUInt

/** Lower case hex, which is how every value in `KeksGolden` is written. */
internal fun ByteArray.hex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return out.toString()
}

internal fun hexBytes(text: String): ByteArray {
    require(text.length % 2 == 0) { "odd hex: $text" }
    val out = ByteArray(text.length / 2)
    for (i in out.indices) {
        out[i] = ((hexDigit(text[i * 2]) shl 4) or hexDigit(text[i * 2 + 1])).toByte()
    }
    return out
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else        -> throw IllegalArgumentException("not hex: $c")
}

internal fun bigOf(text: String): KeksUInt = KeksUInt.fromHex(text)

internal fun pointOf(encoded: String): KeksEcPoint = KeksEcPoint.decodePoint(hexBytes(encoded))
