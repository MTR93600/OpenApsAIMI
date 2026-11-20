package app.aaps.plugins.dexcomoneplus.identity

/**
 * Parses Dexcom ONE+ / G7 applicator Data Matrix (GS1) payloads.
 *
 * Mirrors Juggluco [BarCode] AIs used for Dexcom:
 * - `01` GTIN
 * - `21` Serial
 * - `240` PIN / additional product id (4-digit KEKS password, often trailing)
 *
 * Also accepts a bare 4-digit PIN (manual entry without QR).
 */
object OnePlusGs1ApplicatorParser {

    private const val GS: Char = 0x1D.toChar()

    fun parse(raw: String): OnePlusSensorIdentity? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.length == 4 && trimmed.all { it.isDigit() || it.isWhitespace() }) {
            return OnePlusSensorIdentity(pin = digitsOnly)
        }

        val normalized = normalizeGs(trimmed)
        val fields = parseFields(normalized)?.takeIf { it.containsKey("240") }
            ?: parseWithoutSeparators(normalized)
            ?: return null
        val pinRaw = fields["240"] ?: return null
        val pin = extractPin(pinRaw) ?: return null
        return OnePlusSensorIdentity(
            pin = pin,
            serial = fields["21"]?.takeIf { it.isNotBlank() },
            gtin = fields["01"]?.takeIf { it.isNotBlank() },
            rawGs1 = trimmed,
        )
    }

    /** Extract trailing 4-digit KEKS PIN from AI 240 (Juggluco `dexcomEnd` style). */
    fun extractPin(ai240: String): String? {
        val digits = ai240.filter { it.isDigit() }
        if (digits.length >= 4) return digits.takeLast(4)
        return null
    }

    internal fun normalizeGs(raw: String): String {
        val withGs = raw
            .removePrefix(SYMBOLOGY_ID)
            .replace("^]", GS.toString())
            .replace("\\u001d", GS.toString(), ignoreCase = true)
        return withGs.trimStart(GS).trim()
    }

    /**
     * Reads a Dexcom applicator code whose separators were lost.
     *
     * The serial (`21`) has no fixed length, so a reader that drops the GS character after it makes
     * the serial run to the end of the text, and the PIN (`240`) is never found. A camera can do
     * that: some readers give the GS1 Data Matrix text without its separators. A copied text can
     * lose them too.
     *
     * Only the layout printed on Dexcom applicators is accepted: GTIN, a 12 digit serial, then the
     * fixed-length dates in any order, then the PIN last. Anything else gives null, as before, so a
     * text that only looks like a code is never read as one. A wrong PIN would still be refused by
     * the sensor at Connect.
     */
    private fun parseWithoutSeparators(normalized: String): Map<String, String>? {
        val compact = normalized.filterNot { it == GS || it.isWhitespace() }
        val match = DEXCOM_LAYOUT.matchEntire(compact) ?: return null
        return mapOf(
            "01" to match.groupValues[1],
            "21" to match.groupValues[2],
            "240" to match.groupValues[3],
        )
    }

    private fun parseFields(s: String): Map<String, String>? {
        if (s.isEmpty()) return null
        val out = linkedMapOf<String, String>()
        var pos = 0
        while (pos < s.length) {
            if (s[pos] == GS) {
                pos++
                continue
            }
            val ai = readAi(s, pos) ?: return if (out.isEmpty()) null else out
            pos = ai.nextPos
            val value = readValue(s, pos, ai.fixedLen)
            pos = value.nextPos
            out[ai.code] = value.text
        }
        return out.ifEmpty { null }
    }

    private data class AiRead(val code: String, val fixedLen: Int?, val nextPos: Int)
    private data class ValueRead(val text: String, val nextPos: Int)

    private fun readAi(s: String, pos: Int): AiRead? {
        if (pos >= s.length || !s[pos].isDigit()) return null
        // Prefer longer AIs first (240 before 24, 01 before 0…).
        for (len in intArrayOf(4, 3, 2)) {
            if (pos + len > s.length) continue
            val code = s.substring(pos, pos + len)
            val fixed = FIXED_AI[code]
            if (fixed != null || VARIABLE_AI.contains(code)) {
                return AiRead(code, fixed, pos + len)
            }
        }
        return null
    }

    private fun readValue(s: String, pos: Int, fixedLen: Int?): ValueRead {
        if (fixedLen != null) {
            val end = (pos + fixedLen).coerceAtMost(s.length)
            return ValueRead(s.substring(pos, end), end)
        }
        var end = pos
        while (end < s.length && s[end] != GS) end++
        return ValueRead(s.substring(pos, end), if (end < s.length && s[end] == GS) end + 1 else end)
    }

    private val FIXED_AI = mapOf(
        "01" to 14,
        "11" to 6,
        "17" to 6,
    )

    private val VARIABLE_AI = setOf("10", "21", "240", "250")

    /** The GS1 Data Matrix symbology identifier, which some readers put before the text. */
    private const val SYMBOLOGY_ID = "]d2"

    /** `01` GTIN, `21` 12 digit serial, `11` / `17` dates, `240` PIN at the end. */
    private val DEXCOM_LAYOUT = Regex("""01(\d{14})21(\d{12})(?:11\d{6}|17\d{6})*240(\d{4})""")
}
