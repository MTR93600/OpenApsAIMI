package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import java.util.regex.Pattern

/**
 * Parses oref/AAPS [RT.reason] strings for numeric fields (same idea as `predict_user.py`).
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
        "minPredBG" to Pattern.compile("minPredBG[:\\s]+([-\\d.,]+)", Pattern.CASE_INSENSITIVE),
        "minGuardBG" to Pattern.compile("minGuardBG[:\\s]+([-\\d.,]+)", Pattern.CASE_INSENSITIVE),
        "IOBpredBG" to Pattern.compile("IOBpredBG[:\\s]+([-\\d.,]+)", Pattern.CASE_INSENSITIVE),
        "UAMpredBG" to Pattern.compile("UAMpredBG[:\\s]+([-\\d.,]+)", Pattern.CASE_INSENSITIVE),
        "Dev" to Pattern.compile("Dev[:\\s]+([-\\d.,]+)", Pattern.CASE_INSENSITIVE),
        "BGI" to Pattern.compile("BGI[:\\s]+([-\\d.,]+)", Pattern.CASE_INSENSITIVE),
    )

    fun parse(reason: String): Parsed {
        if (reason.isBlank()) return Parsed()
        // One number only: avoids eating the list comma after "Target: 5,5, minPredBG" (EU decimal).
        val targetM = Pattern.compile(
            "Target[:\\s]+(-?\\d+(?:[.,]\\d+)?)",
            Pattern.CASE_INSENSITIVE,
        ).matcher(reason)
        val reasonMmol = targetM.find() && parseNumericToken(targetM.group(1))?.let { it < 20 } == true

        fun scale(v: Double) = if (reasonMmol) v * 18.0 else v

        fun extract(key: String): Double? {
            val m = patterns[key]!!.matcher(reason)
            if (!m.find()) return null
            return parseNumericToken(m.group(1))?.let(::scale)
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
        val m = Pattern.compile("CR[:\\s]+([\\d.,]+)", Pattern.CASE_INSENSITIVE).matcher(reason)
        return if (m.find()) parseNumericToken(m.group(1)) else null
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
        val smbM = Pattern.compile("maxSMBBasalMinutes:\\s*([\\d.]+)").matcher(text)
        val uamM = Pattern.compile("maxUAMSMBBasalMinutes:\\s*([\\d.]+)").matcher(text)
        val smb = if (smbM.find()) smbM.group(1)?.toDoubleOrNull() else null
        val uam = if (uamM.find()) uamM.group(1)?.toDoubleOrNull() else null
        return smb to uam
    }
}
