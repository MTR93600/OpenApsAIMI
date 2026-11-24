package app.aaps.core.interfaces.utils

/**
 * Text cuts that never break a character in two.
 *
 * A Kotlin `String` counts UTF-16 units, and an emoji such as 🐢 or 🛡 takes two of them (a "surrogate
 * pair"). `take(n)` can cut between the two and leave half an emoji at the end. That half is not a
 * valid character: the JSON written from it cannot be read back, so a device status that carries it
 * fails to upload to Nightscout.
 */

/**
 * The first [n] UTF-16 units, or one less when the cut would leave the first half of a pair at the
 * end. Use it in place of `take(n)` for any text that goes into a log, a reason or a file.
 */
fun String.takeWholeChars(n: Int): String {
    val cut = take(n)
    return if (cut.length < length && cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
}

/**
 * This text with every half of a surrogate pair that has no partner replaced by U+FFFD (�).
 * Returns the same instance when there is nothing to replace, which is the normal case.
 */
fun String.withoutLoneSurrogates(): String {
    if (!hasLoneSurrogate()) return this
    val out = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        when {
            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> {
                out.append(c).append(this[i + 1])
                i += 2
                continue
            }
            c.isSurrogate() -> out.append(REPLACEMENT_CHARACTER)
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

private fun String.hasLoneSurrogate(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            i += 2
            continue
        }
        if (c.isSurrogate()) return true
        i++
    }
    return false
}

private const val REPLACEMENT_CHARACTER = '�'
