package app.aaps.plugins.aps.openAPSAIMI.tpo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import app.aaps.core.data.json.OrgJsonCompat.hasCompat
import app.aaps.core.data.json.OrgJsonCompat.optDoubleCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonArrayCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat

import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningChange
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier

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
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("schema_version", schemaVersion)
            put("session_id", sessionId)
            put("pack_id", packId.name)
            put("tier", tier.name)
            put("status", status.name)
            put("started_at_ms", startedAtMs)
            put("expires_at_ms", expiresAtMs)
            put("ttl_ms", expiresAtMs - startedAtMs)
            put("trigger", buildJsonObject {
                put("algo_confidence", triggerAlgoConfidence)
                put("reason_codes", JsonArray(triggerReasonCodes.map { JsonPrimitive(it) }))
            })
            put("baseline", mapToJson(baseline))
            put("overlay", mapToJson(overlay))
            put("user_owned_keys", JsonArray(userOwnedKeys.map { JsonPrimitive(it) }))
            llmResult?.let { llm ->
                put("llm", buildJsonObject {
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
        fun fromJsonObject(json: JsonObject): TpoSessionDocument? {
            val pack = TpoPackId.fromName(if (json.hasCompat("pack_id")) json.optStringCompat("pack_id") else null) ?: return null
            val tier = runCatching { TuningStepTier.valueOf(json.let { o -> if (o.hasCompat("tier")) o.optStringCompat("tier") else "MODERATE" }) }
                .getOrDefault(TuningStepTier.MODERATE)
            val status = runCatching { TpoSessionStatus.valueOf(json.let { o -> if (o.hasCompat("status")) o.optStringCompat("status") else "ACTIVE" }) }
                .getOrDefault(TpoSessionStatus.ACTIVE)
            return TpoSessionDocument(
                sessionId = json.optStringCompat("session_id"),
                packId = pack,
                tier = tier,
                status = status,
                startedAtMs = json.optLongCompat("started_at_ms", 0L),
                expiresAtMs = json.optLongCompat("expires_at_ms", 0L),
                triggerAlgoConfidence = json.optJsonObjectCompat("trigger")?.optDoubleCompat("algo_confidence", Double.NaN) ?: 0.0,
                triggerReasonCodes = jsonArrayToStrings(
                    json.optJsonObjectCompat("trigger")?.optJsonArrayCompat("reason_codes"),
                ),
                baseline = jsonToMap(json.optJsonObjectCompat("baseline")),
                overlay = jsonToMap(json.optJsonObjectCompat("overlay")),
                userOwnedKeys = jsonArrayToStrings(json.optJsonArrayCompat("user_owned_keys")).toSet(),
                llmResult = json.optJsonObjectCompat("llm")?.let { llm ->
                    TpoLlmResult(
                        verdict = runCatching {
                            TpoLlmVerdict.valueOf(llm.let { o -> if (o.hasCompat("status")) o.optStringCompat("status") else "UNCERTAIN" })
                        }.getOrDefault(TpoLlmVerdict.UNCERTAIN),
                        confidence = llm.optDoubleCompat("confidence", 0.0),
                        rationale = llm.optStringCompat("rationale"),
                        competingHypothesis = llm.let { o -> if (o.hasCompat("competing_hypothesis")) o.optStringCompat("competing_hypothesis") else "none" },
                        latencyMs = llm.optLongCompat("latency_ms", 0L),
                    )
                },
                lastRevertAtMs = json.optLongCompat("last_revert_at_ms", 0L).takeIf { json.hasCompat("last_revert_at_ms") },
            )
        }

        private fun mapToJson(map: Map<String, Any>): JsonObject =
            buildJsonObject {
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

        private fun jsonToMap(json: JsonObject?): Map<String, Any> {
            if (json == null) return emptyMap()
            val out = linkedMapOf<String, Any>()
            json.forEach { (key, element) ->
                val primitive = element as? JsonPrimitive ?: return@forEach
                if (primitive is JsonNull) return@forEach
                when {
                    !primitive.isString && primitive.booleanOrNull != null ->
                        out[key] = primitive.booleanOrNull!!
                    primitive.isString ->
                        out[key] = primitive.content
                    primitive.longOrNull != null && '.' !in primitive.content &&
                        'e' !in primitive.content && 'E' !in primitive.content -> {
                        val number = primitive.longOrNull!!
                        out[key] = if (number in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                            number.toInt()
                        } else {
                            number
                        }
                    }
                    primitive.doubleOrNull != null ->
                        out[key] = primitive.doubleOrNull!!
                    else ->
                        out[key] = primitive.content
                }
            }
            return out
        }

        private fun jsonArrayToStrings(array: JsonArray?): List<String> {
            if (array == null) return emptyList()
            return array.map { element ->
                (element as? JsonPrimitive)?.content ?: ""
            }
        }
    }
}

data class TpoApplyPlan(
    val proposal: TpoProposal,
    val changes: List<TuningChange>,
)
