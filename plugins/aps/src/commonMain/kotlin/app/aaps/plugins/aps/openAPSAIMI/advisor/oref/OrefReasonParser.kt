package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

/**
 * Parses oref/AAPS `RT.reason` strings for numeric fields (same idea as `predict_user.py`).
 * Values are assumed mg/dL when parsed from reason; optional mmol heuristic matches Python.
 */
object OrefReasonParser {

    data class Parsed(
        val minPredBG: Double? = null,
        val minGuardBG: Double? = null,
        val iobPredBG: Double? = null,
        val uamPredBG: Double? = null,
        val dev: Double? = null,
        val bgi: Double? = null,
    )

    private val patterns = mapOf(
        "minPredBG" to Regex("minPredBG[:\\s]+([-\\d.,]+)", RegexOption.IGNORE_CASE),
        "minGuardBG" to Regex("minGuardBG[:\\s]+([-\\d.,]+)", RegexOption.IGNORE_CASE),
        "IOBpredBG" to Regex("IOBpredBG[:\\s]+([-\\d.,]+)", RegexOption.IGNORE_CASE),
        "UAMpredBG" to Regex("UAMpredBG[:\\s]+([-\\d.,]+)", RegexOption.IGNORE_CASE),
        "Dev" to Regex("Dev[:\\s]+([-\\d.,]+)", RegexOption.IGNORE_CASE),
        "BGI" to Regex("BGI[:\\s]+([-\\d.,]+)", RegexOption.IGNORE_CASE),
    )

    fun parse(reason: String): Parsed {
        if (reason.isBlank()) return Parsed()
        // One number only: avoids eating the list comma after "Target: 5,5, minPredBG" (EU decimal).
        val targetM = Regex(
            "Target[:\\s]+(-?\\d+(?:[.,]\\d+)?)",
            RegexOption.IGNORE_CASE,
        ).find(reason)
        val reasonMmol = targetM != null && parseNumericToken(targetM.groupValues[1])?.let { it < 20 } == true

        fun scale(v: Double) = if (reasonMmol) v * 18.0 else v

        fun extract(key: String): Double? {
            val m = patterns[key]!!.find(reason) ?: return null
            return parseNumericToken(m.groupValues[1])?.let(::scale)
        }

        return Parsed(
            minPredBG = extract("minPredBG"),
            minGuardBG = extract("minGuardBG"),
            iobPredBG = extract("IOBpredBG"),
            uamPredBG = extract("UAMpredBG"),
            dev = extract("Dev"),
            bgi = extract("BGI"),
        )
    }

    fun parseCrFromReason(reason: String): Double? {
        val m = Regex("CR[:\\s]+([\\d.,]+)", RegexOption.IGNORE_CASE).find(reason)
        return if (m != null) parseNumericToken(m.groupValues[1]) else null
    }

    /** Parses BG-like tokens from reason strings (supports `5,5`-style decimals from localized formatting). */
    internal fun parseNumericToken(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim().replace(',', '.')
        return t.toDoubleOrNull()
    }

    fun parseMaxSmbMinutesFromConsole(consoleLines: Collection<String>?): Pair<Double?, Double?> {
        if (consoleLines.isNullOrEmpty()) return null to null
        val text = consoleLines.joinToString(" ")
        val smbM = Regex("maxSMBBasalMinutes:\\s*([\\d.]+)").find(text)
        val uamM = Regex("maxUAMSMBBasalMinutes:\\s*([\\d.]+)").find(text)
        val smb = smbM?.groupValues?.get(1)?.toDoubleOrNull()
        val uam = uamM?.groupValues?.get(1)?.toDoubleOrNull()
        return smb to uam
    }
}
