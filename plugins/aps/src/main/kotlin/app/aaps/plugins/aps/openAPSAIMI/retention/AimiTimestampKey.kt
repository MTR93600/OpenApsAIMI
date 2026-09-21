package app.aaps.plugins.aps.openAPSAIMI.retention

/**
 * Reads an epoch-milliseconds value out of a raw JSONL line, without parsing the JSON.
 *
 * A decision line is about 38 KB. Building a `JSONObject` for each of them, only to read one number,
 * would dominate the whole pass. `AimiProfileAdvisorActivity` already scans for the key by hand for
 * the support package export, which shows this works on the real data.
 */
internal class AimiTimestampKey(key: String) {

    private val needle = "\"$key\":".toByteArray(Charsets.US_ASCII)

    /** Returns the value, or `null` when the key is missing, non-numeric, or cut off by the prefix. */
    fun extract(prefix: ByteArray): Long? {
        val at = indexOf(prefix) ?: return null
        var i = at + needle.size
        while (i < prefix.size && prefix[i] == SPACE) i++
        val start = i
        while (i < prefix.size && prefix[i] >= ZERO && prefix[i] <= NINE) i++
        if (i == start) return null
        return String(prefix, start, i - start, Charsets.US_ASCII).toLongOrNull()
    }

    private fun indexOf(haystack: ByteArray): Int? {
        val last = haystack.size - needle.size
        var i = 0
        outer@ while (i <= last) {
            var j = 0
            while (j < needle.size) {
                if (haystack[i + j] != needle[j]) {
                    i++
                    continue@outer
                }
                j++
            }
            return i
        }
        return null
    }

    private companion object {

        const val SPACE = ' '.code.toByte()
        const val ZERO = '0'.code.toByte()
        const val NINE = '9'.code.toByte()
    }
}
