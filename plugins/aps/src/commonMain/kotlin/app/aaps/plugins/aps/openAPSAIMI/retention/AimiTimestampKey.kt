package app.aaps.plugins.aps.openAPSAIMI.retention

/**
 * Reads an epoch-milliseconds value out of a raw JSONL line, without parsing the JSON.
 *
 * A decision line is about 38 KB. Building a `JSONObject` for each of them, only to read one number,
 * would dominate the whole pass. The support package export scans for the key by hand the same way,
 * which shows this works on the real data.
 *
 * Byte handling uses the multiplatform `encodeToByteArray` / `decodeToString` rather than
 * `Charsets.US_ASCII`, which exists only on the JVM. They are UTF-8, and that is byte-identical here
 * for both directions: the keys are ASCII identifiers (`timestamp`, `wall_ms`), and the only bytes ever
 * decoded are the ones that just passed the `0`..`9` test.
 */
internal class AimiTimestampKey(key: String) {

    private val needle = "\"$key\":".encodeToByteArray()

    /** Returns the value, or `null` when the key is missing, non-numeric, or cut off by the prefix. */
    fun extract(prefix: ByteArray): Long? {
        val at = indexOf(prefix) ?: return null
        var i = at + needle.size
        while (i < prefix.size && prefix[i] == SPACE) i++
        val start = i
        while (i < prefix.size && prefix[i] >= ZERO && prefix[i] <= NINE) i++
        if (i == start) return null
        return prefix.decodeToString(start, i).toLongOrNull()
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
