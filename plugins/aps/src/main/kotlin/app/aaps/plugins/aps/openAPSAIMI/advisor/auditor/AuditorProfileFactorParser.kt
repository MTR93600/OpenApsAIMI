package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.json.JSONObject

/**
 * Reads the profile checker's answer, and never trusts one character of it.
 *
 * Nothing here can throw and nothing here can return a value the loop would use as given. A field
 * that is missing, empty, not a number, not finite or not positive becomes 1.0 — the answer that
 * changes nothing — and the field name is written to `parseIssues` so the log says what happened.
 * The bounds themselves belong to `AuditorProfileFactorValidator`, which sees the un-clamped value
 * and can report how far outside the model went.
 */
object AuditorProfileFactorParser {

    private const val ISF_KEY = "isfFactor"
    private const val TARGET_KEY = "targetFactor"

    /** Parses one answer. Returns a neutral output with a `failure` when the text is not usable. */
    fun parse(text: String): AuditorProfileFactorLlmOutput {
        val body = outermostObject(text) ?: return AuditorProfileFactorLlmOutput.failed("parse")
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return AuditorProfileFactorLlmOutput.failed("parse")

        val issues = mutableListOf<String>()
        val isf = readFactor(json, ISF_KEY, issues)
        val target = readFactor(json, TARGET_KEY, issues)

        val reasonCode = runCatching {
            ProfileFactorReasonCode.valueOf(json.optString("reasonCode", "NONE").trim().uppercase())
        }.getOrDefault(ProfileFactorReasonCode.NONE)

        val confidence = readDouble(json, "confidence")?.coerceIn(0.0, 1.0) ?: 0.0

        val hypothesis = json.optString("competingHypothesis", "none")
            .trim().lowercase().ifEmpty { "none" }

        val claimsJson = json.optJSONObject("claims")
        val claims = ProfileFactorClaims(
            bgStartMgdl = readDouble(claimsJson, "bg_start_mgdl"),
            bgEndMgdl = readDouble(claimsJson, "bg_end_mgdl"),
            bgMinMgdl = readDouble(claimsJson, "bg_min_mgdl"),
            iobStartU = readDouble(claimsJson, "iob_start_u"),
            iobEndU = readDouble(claimsJson, "iob_end_u"),
            insulinDeliveredU = readDouble(claimsJson, "insulin_delivered_u"),
        )

        return AuditorProfileFactorLlmOutput(
            isfFactorRaw = isf,
            targetFactorRaw = target,
            reasonCode = reasonCode,
            confidence = confidence,
            competingHypothesis = hypothesis,
            claims = claims,
            rationale = json.optString("rationale", "").take(AuditorProfileFactorLimits.RATIONALE_MAX_CHARS),
            parseIssues = issues,
            failure = null,
        )
    }

    /**
     * The text between the first `{` and the last `}`.
     *
     * This also strips a markdown code fence, because what is inside the outermost braces is the
     * object whatever is written around it.
     */
    private fun outermostObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    /** A factor, or 1.0 plus an issue when the model did not give a usable number. */
    private fun readFactor(json: JSONObject, key: String, issues: MutableList<String>): Double {
        val value = readDouble(json, key)
        if (value == null || value <= 0.0) {
            issues.add("${key}_invalid")
            return 1.0
        }
        return value
    }

    /** A finite number from a JSON number or a numeric string, else null. */
    private fun readDouble(json: JSONObject?, key: String): Double? {
        val raw = json?.opt(key) ?: return null
        val value = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else       -> null
        } ?: return null
        return value.takeIf { it.isFinite() }
    }
}
