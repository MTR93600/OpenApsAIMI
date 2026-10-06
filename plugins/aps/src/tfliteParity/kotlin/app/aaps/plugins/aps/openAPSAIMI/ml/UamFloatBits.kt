package app.aaps.plugins.aps.openAPSAIMI.ml

/**
 * Bit-level comparison of float32 values. Hex is the raw IEEE-754 bits, not a
 * rounded decimal. ULP distance maps the sign bit into an ordered integer space
 * so a negative and a positive value are not reported as a small distance.
 */
internal fun floatBitsHex(bits: Int): String =
    bits.toUInt().toString(16).padStart(8, '0')

internal fun floatBitsFromHex(hex: String): Int =
    hex.toULong(16).toInt()

internal fun sameFloatBits(actual: Int, expected: Int): Boolean = actual == expected

internal fun ulpDistance(actual: Int, expected: Int): Int {
    val a = orderedFloatBits(actual)
    val b = orderedFloatBits(expected)
    val delta = a.toLong() - b.toLong()
    return if (delta < 0) (-delta).toInt() else delta.toInt()
}

private fun orderedFloatBits(bits: Int): Int =
    if (bits < 0) 0x80000000.toInt() - bits else bits
