package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import org.json.JSONObject
import java.io.File

/**
 * Honest JSONL export for loop decisions vs async AI auditor follow-up.
 * Loop outcome is authoritative for pump delivery; auditor is advisory (often async).
 */
object AuditorJsonlExport {

    enum class TickDisposition {
        DISABLED,
        SKIPPED_NO_TRIGGER,
        SKIPPED_STALE_DATA,
        SENTINEL_ONLY,
        SENTINEL_RATE_LIMITED,
        EXTERNAL_PENDING,
    }

    data class TickSnapshot(
        val disposition: TickDisposition,
        val recordedAtMs: Long,
        val loopSmbU: Double,
        val loopTbrUph: Double?,
        val loopIntervalMin: Int,
        val sentinelAgreement: Double? = null,
        val sentinelSmbFactor: Double? = null,
        val sentinelReason: String? = null,
    ) {
        fun toJsonObject(): JSONObject =
            JSONObject().apply {
                put("status", disposition.name.lowercase())
                put("recorded_at_ms", recordedAtMs)
                put("loop_smb_u", loopSmbU)
                put("loop_tbr_uph", loopTbrUph ?: JSONObject.NULL)
                put("loop_interval_min", loopIntervalMin)
                put("loop_authoritative", true)
                put("sentinel_agreement", sentinelAgreement ?: JSONObject.NULL)
                put("sentinel_smb_factor", sentinelSmbFactor ?: JSONObject.NULL)
                put("sentinel_reason", sentinelReason ?: JSONObject.NULL)
                put(
                    "auditor_binding",
                    disposition == TickDisposition.SENTINEL_ONLY ||
                        disposition == TickDisposition.SENTINEL_RATE_LIMITED,
                )
                put(
                    "note",
                    when (disposition) {
                        TickDisposition.DISABLED ->
                            "Auditor preference off; loop outcome only."
                        TickDisposition.SKIPPED_NO_TRIGGER,
                        TickDisposition.SKIPPED_STALE_DATA,
                        ->
                            "Auditor not invoked this tick."
                        TickDisposition.SENTINEL_ONLY,
                        TickDisposition.SENTINEL_RATE_LIMITED,
                        ->
                            "Local Sentinel applied synchronously; see auditor_followup if present."
                        TickDisposition.EXTERNAL_PENDING ->
                            "External LLM audit pending; loop already committed."
                    },
                )
            }
    }

    fun followupToJsonObject(
        parentEventId: String,
        recordedAtMs: Long,
        auditStartedAtMs: Long,
        verdict: AuditorVerdict?,
        result: DecisionResult,
    ): JSONObject =
        JSONObject().apply {
            put("record_type", "auditor_followup")
            put("parent_event_id", parentEventId)
            put("timestamp", recordedAtMs)
            put("latency_ms", (recordedAtMs - auditStartedAtMs).coerceAtLeast(0L))
            verdict?.let { v ->
                put("verdict", v.verdict.name)
                put("confidence", v.confidence)
                put("degraded_mode", v.degradedMode)
            }
            when (result) {
                is DecisionResult.Applied -> {
                    put("decision_result", "applied")
                    put("smb_u", result.bolusU ?: JSONObject.NULL)
                    put("tbr_uph", result.tbrUph ?: JSONObject.NULL)
                    put("tbr_min", result.tbrMin ?: JSONObject.NULL)
                }
                is DecisionResult.Rejected -> {
                    put("decision_result", "rejected")
                    put("severity", result.severity.toString())
                }
                is DecisionResult.Skipped -> put("decision_result", "skipped")
                is DecisionResult.Fallthrough -> put("decision_result", "fallthrough")
            }
            put("reason", result.reason)
            put("advisory_only", true)
        }

    fun appendLine(decisionsFile: File, jsonLine: String) {
        if (!decisionsFile.exists()) {
            decisionsFile.parentFile?.mkdirs()
            decisionsFile.createNewFile()
        }
        decisionsFile.appendText("$jsonLine\n")
    }
}
