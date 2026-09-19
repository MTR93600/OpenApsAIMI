package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningChange
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import org.json.JSONArray
import org.json.JSONObject

enum class TpoPackId(val priority: Int) {
    EXHAUSTED_RECOVERY(priority = 3),
    POST_HYPO_RECOVERY(priority = 2),
    POOR_SLEEP_WINDOW(priority = 1),
    ;

    companion object {
        fun fromName(name: String?): TpoPackId? =
            entries.firstOrNull { it.name == name }
    }
}

enum class TpoSessionStatus {
    PENDING_LLM,
    ACTIVE,
    EXPIRED,
    REVERTED,
    SUPERSEDED,
}

enum class TpoLlmVerdict {
    CONFIRM,
    VETO,
    UNCERTAIN,
}

data class TpoProposal(
    val packId: TpoPackId,
    val tier: TuningStepTier,
    val algoConfidence: Double,
    val reasonCodes: List<String>,
)

data class TpoLlmResult(
    val verdict: TpoLlmVerdict,
    val confidence: Double,
    val rationale: String,
    val competingHypothesis: String = "none",
    val latencyMs: Long = 0L,
)

data class TpoTickInput(
    val nowMs: Long,
    val bgMgdl: Double,
    val deltaMgdl5m: Double,
    val cobGrams: Double,
    val minBgLookback75m: Double,
    val mealProb: Double,
    val sleepDebtScore: Double,
    val thermalRecoveryBurden: Double,
    val postHypoReboundProb: Double,
    val patientModeName: String,
    val patientModeConfidence: Double,
    val causalDominantName: String,
    val causalDominantConfidence: Double,
    val eventMemory: app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory,
    val reboundGuardActive: Boolean,
    val dawnEndogenousDrive: Double,
)

data class TpoSessionDocument(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val packId: TpoPackId,
    val tier: TuningStepTier,
    val status: TpoSessionStatus,
    val startedAtMs: Long,
    val expiresAtMs: Long,
    val triggerAlgoConfidence: Double,
    val triggerReasonCodes: List<String>,
    val baseline: Map<String, Any>,
    val overlay: Map<String, Any>,
    val userOwnedKeys: Set<String> = emptySet(),
    val llmResult: TpoLlmResult? = null,
    val lastRevertAtMs: Long? = null,
) {
    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("schema_version", schemaVersion)
            put("session_id", sessionId)
            put("pack_id", packId.name)
            put("tier", tier.name)
            put("status", status.name)
            put("started_at_ms", startedAtMs)
            put("expires_at_ms", expiresAtMs)
            put("ttl_ms", expiresAtMs - startedAtMs)
            put("trigger", JSONObject().apply {
                put("algo_confidence", triggerAlgoConfidence)
                put("reason_codes", JSONArray(triggerReasonCodes))
            })
            put("baseline", mapToJson(baseline))
            put("overlay", mapToJson(overlay))
            put("user_owned_keys", JSONArray(userOwnedKeys.toList()))
            llmResult?.let { llm ->
                put("llm", JSONObject().apply {
                    put("status", llm.verdict.name)
                    put("confidence", llm.confidence)
                    put("rationale", llm.rationale)
                    put("competing_hypothesis", llm.competingHypothesis)
                    put("latency_ms", llm.latencyMs)
                })
            }
            lastRevertAtMs?.let { put("last_revert_at_ms", it) }
        }

    companion object {
        fun fromJsonObject(json: JSONObject): TpoSessionDocument? {
            val pack = TpoPackId.fromName(json.optString("pack_id", null)) ?: return null
            val tier = runCatching { TuningStepTier.valueOf(json.optString("tier", "MODERATE")) }
                .getOrDefault(TuningStepTier.MODERATE)
            val status = runCatching { TpoSessionStatus.valueOf(json.optString("status", "ACTIVE")) }
                .getOrDefault(TpoSessionStatus.ACTIVE)
            return TpoSessionDocument(
                sessionId = json.optString("session_id", ""),
                packId = pack,
                tier = tier,
                status = status,
                startedAtMs = json.optLong("started_at_ms", 0L),
                expiresAtMs = json.optLong("expires_at_ms", 0L),
                triggerAlgoConfidence = json.optJSONObject("trigger")?.optDouble("algo_confidence") ?: 0.0,
                triggerReasonCodes = jsonArrayToStrings(
                    json.optJSONObject("trigger")?.optJSONArray("reason_codes"),
                ),
                baseline = jsonToMap(json.optJSONObject("baseline")),
                overlay = jsonToMap(json.optJSONObject("overlay")),
                userOwnedKeys = jsonArrayToStrings(json.optJSONArray("user_owned_keys")).toSet(),
                llmResult = json.optJSONObject("llm")?.let { llm ->
                    TpoLlmResult(
                        verdict = runCatching {
                            TpoLlmVerdict.valueOf(llm.optString("status", "UNCERTAIN"))
                        }.getOrDefault(TpoLlmVerdict.UNCERTAIN),
                        confidence = llm.optDouble("confidence", 0.0),
                        rationale = llm.optString("rationale", ""),
                        competingHypothesis = llm.optString("competing_hypothesis", "none"),
                        latencyMs = llm.optLong("latency_ms", 0L),
                    )
                },
                lastRevertAtMs = json.optLong("last_revert_at_ms").takeIf { json.has("last_revert_at_ms") },
            )
        }

        private fun mapToJson(map: Map<String, Any>): JSONObject =
            JSONObject().apply {
                map.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> put(key, value)
                        is Double -> put(key, value)
                        is Int -> put(key, value)
                        is Long -> put(key, value)
                        else -> put(key, value.toString())
                    }
                }
            }

        /**
         * Read a `baseline` / `overlay` map back, with every number as a `Double`.
         *
         * JSON has no idea that 1.0 is a preference value rather than a count: it writes a double
         * with nothing after the point **without a decimal point**, and reads `1` back as an `Int`.
         * The revert path then compares that `Int` with the live preference (always a `Double`) and
         * hands it to a writer that only accepts a `Double` — so a user value that happened to be a
         * whole number was silently never put back, and the session's own value stayed for good.
         * Field report 2026-09-18: `max_smb` 1.0 stuck at 0.80, `high_bg_max_smb` 2.0 stuck at 1.25.
         *
         * Normalising here fixes the write-back, the restore gate, the user-owned tracking and the
         * overlay application in one place. Safe because `TpoPreferenceKeys` whitelists nothing but
         * `Double` and `Boolean` keys — there is no number in these maps that is not a preference.
         */
        private fun jsonToMap(json: JSONObject?): Map<String, Any> {
            if (json == null) return emptyMap()
            val out = linkedMapOf<String, Any>()
            json.keys().forEach { key ->
                when (val value = json.get(key)) {
                    is Boolean -> out[key] = value
                    is Number -> out[key] = value.toDouble()
                    is String -> out[key] = value
                }
            }
            return out
        }

        private fun jsonArrayToStrings(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        }
    }
}

data class TpoApplyPlan(
    val proposal: TpoProposal,
    val changes: List<TuningChange>,
)
