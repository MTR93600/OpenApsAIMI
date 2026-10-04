package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.abs

/**
 * Locale-stable number text for AIMI. Do not use `String.format` in commonMain.
 *
 * Each helper replaces `String.format(Locale.US, "%.Nf", x)`. Ties go away from zero, and a
 * negative value that rounds to zero keeps the sign (`%.0f` of `-0.25` is `-0`). The digits come
 * from [Double.toString] (the shortest round-trip decimal), then half-up on that decimal.
 * `DecimalFormat` half-up of the exact binary value does not match: `1.2345` at three decimals
 * is `1.235` here and `1.234` there.
 *
 * The separator is a dot. Most of this text is read in logs or sent to a server, so it must not
 * follow the device locale. Text shown to the user goes through a string resource instead.
 */
internal fun aimiFmt0(value: Double): String = formatFixedHalfAway(value, 0)

internal fun aimiFmt1(value: Double): String = formatFixedHalfAway(value, 1)

internal fun aimiFmt2(value: Double): String = formatFixedHalfAway(value, 2)

internal fun aimiFmt3(value: Double): String = formatFixedHalfAway(value, 3)

internal fun aimiFmt4(value: Double): String = formatFixedHalfAway(value, 4)

/** `String.format` promotes `float` to `double` before `%.Nf`. */
internal fun aimiFmt0(value: Float): String = aimiFmt0(value.toDouble())

internal fun aimiFmt1(value: Float): String = aimiFmt1(value.toDouble())

internal fun aimiFmt2(value: Float): String = aimiFmt2(value.toDouble())

internal fun aimiFmt3(value: Float): String = aimiFmt3(value.toDouble())

internal fun aimiFmt4(value: Float): String = aimiFmt4(value.toDouble())

/**
 * `String.format("%.Nf", null)` prints the word `null` cut to N characters
 * (`%.0f` is empty, `%.2f` is `nu`). The tick passes a few `Double?` values
 * straight through, and this keeps that text.
 */
internal fun aimiFmt0(value: Double?): String = value?.let { aimiFmt0(it) } ?: formatterNullText(0)

internal fun aimiFmt1(value: Double?): String = value?.let { aimiFmt1(it) } ?: formatterNullText(1)

internal fun aimiFmt2(value: Double?): String = value?.let { aimiFmt2(it) } ?: formatterNullText(2)

internal fun aimiFmt3(value: Double?): String = value?.let { aimiFmt3(it) } ?: formatterNullText(3)

internal fun aimiFmt4(value: Double?): String = value?.let { aimiFmt4(it) } ?: formatterNullText(4)

private fun formatterNullText(decimals: Int): String = "null".substring(0, decimals.coerceIn(0, 4))

internal fun aimiFmtSigned1(value: Double): String {
    val body = aimiFmt1(abs(value))
    return if (value >= 0.0) "+$body" else "-$body"
}

/**
 * `%.Nf` as Java's Formatter prints it: shortest decimal, ties away from zero,
 * signed zero preserved. `decimals` is 0..4 at every call site.
 */
private fun formatFixedHalfAway(value: Double, decimals: Int): String {
    if (value.isNaN()) return "NaN"
    if (value == Double.POSITIVE_INFINITY) return "Infinity"
    if (value == Double.NEGATIVE_INFINITY) return "-Infinity"
    val negative = value.toRawBits() < 0L
    val magnitude = if (negative) -value else value
    var digits = unscaledDigits(magnitude.toString())
    val scale = digits.second
    var unscaled = digits.first
    if (decimals >= scale) {
        unscaled += "0".repeat(decimals - scale)
    } else {
        val drop = scale - decimals
        val kept: String
        val guard: Char
        if (unscaled.length < drop) {
            kept = "0"
            guard = '0'
        } else if (unscaled.length == drop) {
            kept = "0"
            guard = unscaled[0]
        } else {
            kept = unscaled.substring(0, unscaled.length - drop)
            guard = unscaled[unscaled.length - drop]
        }
        unscaled = if (guard >= '5') incrementDecimal(kept) else kept
    }
    val body = renderPlain(unscaled, decimals)
    return if (negative) "-$body" else body
}

/** Shortest-decimal text → unscaled integer digits and the BigDecimal-style scale. */
private fun unscaledDigits(text: String): Pair<String, Int> {
    val exponentMark = text.indexOf('E').let { if (it < 0) text.indexOf('e') else it }
    val mantissa = if (exponentMark >= 0) text.substring(0, exponentMark) else text
    val exponent = if (exponentMark >= 0) text.substring(exponentMark + 1).toInt() else 0
    val dot = mantissa.indexOf('.')
    val rawDigits = if (dot < 0) mantissa else mantissa.removeRange(dot, dot + 1)
    val mantissaScale = if (dot < 0) 0 else mantissa.length - dot - 1
    val scale = mantissaScale - exponent
    val trimmed = rawDigits.trimStart('0')
    if (trimmed.isEmpty() || trimmed == "0") return "0" to 0
    return trimmed to scale
}

private fun incrementDecimal(text: String): String {
    if (text.isEmpty() || text == "0") return "1"
    val chars = text.toCharArray()
    var index = chars.lastIndex
    while (index >= 0) {
        if (chars[index] < '9') {
            chars[index] = chars[index] + 1
            return chars.concatToString()
        }
        chars[index] = '0'
        index--
    }
    return "1" + chars.concatToString()
}

private fun renderPlain(digits: String, scale: Int): String {
    val safe = digits.ifEmpty { "0" }
    if (scale <= 0) return safe + "0".repeat(-scale)
    if (safe.length <= scale) return "0." + "0".repeat(scale - safe.length) + safe
    val cut = safe.length - scale
    return safe.substring(0, cut) + "." + safe.substring(cut)
}
