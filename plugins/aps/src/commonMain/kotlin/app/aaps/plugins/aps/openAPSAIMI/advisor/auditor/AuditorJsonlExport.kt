package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
        fun toJsonObject(): JsonObject =
            buildJsonObject {
                put("status", disposition.name.lowercase())
                put("recorded_at_ms", recordedAtMs)
                put("loop_smb_u", loopSmbU)
                if (loopTbrUph != null) put("loop_tbr_uph", loopTbrUph) else put("loop_tbr_uph", JsonNull)
                put("loop_interval_min", loopIntervalMin)
                put("loop_authoritative", true)
                if (sentinelAgreement != null) put("sentinel_agreement", sentinelAgreement) else put("sentinel_agreement", JsonNull)
                if (sentinelSmbFactor != null) put("sentinel_smb_factor", sentinelSmbFactor) else put("sentinel_smb_factor", JsonNull)
                if (sentinelReason != null) put("sentinel_reason", sentinelReason) else put("sentinel_reason", JsonNull)
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
    ): JsonObject =
        buildJsonObject {
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
                    if (result.bolusU != null) put("smb_u", result.bolusU) else put("smb_u", JsonNull)
                    if (result.tbrUph != null) put("tbr_uph", result.tbrUph) else put("tbr_uph", JsonNull)
                    if (result.tbrMin != null) put("tbr_min", result.tbrMin) else put("tbr_min", JsonNull)
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

    /**
     * Adds one JSONL record to [decisionsFile].
     *
     * Same three steps as before the port: create the parent directory and the file when it is not
     * there, then append. [AimiStorage] answers `false` instead of throwing, so a full disk drops a
     * journal line rather than a dosing tick.
     */
    fun appendLine(storage: AimiStorage, decisionsFile: AimiPath, jsonLine: String) {
        if (!storage.exists(decisionsFile)) {
            storage.createParentDirectories(decisionsFile)
            storage.createFile(decisionsFile)
        }
        storage.appendText(decisionsFile, "$jsonLine\n")
    }
}
