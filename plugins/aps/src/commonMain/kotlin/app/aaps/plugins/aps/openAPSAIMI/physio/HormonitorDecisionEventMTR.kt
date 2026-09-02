package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.patient.PhysioLiveDigest
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One AIMI study telemetry record, as shared code builds it.
 *
 * It used to sit inside the Android exporter file. It is here because the loop *builds* the record in
 * shared code and only the *writing* of it is a platform job - see [HormonitorStudyExporter]. Nothing
 * in it is platform specific: every field is a number, a string or a shared model, and [toJSON] uses
 * `kotlinx.serialization` only.
 *
 * The package is unchanged, so no call site had to move with it.
 */
data class HormonitorDecisionEventMTR(
    val eventId: String,
    val eventTimestamp: Long,
    val trigger: String,
    val profileIsfMgdl: Double,
    val profileBasalUph: Double,
    val currentBgMgdl: Double,
    val cobG: Double,
    val iobU: Double,
    val cyclePhase: String? = null,
    val cycleDay: Int? = null,
    val cycleTrackingMode: String? = null,
    val contraceptiveType: String? = null,
    val wcycleBasalMult: Double? = null,
    val wcycleSmbMult: Double? = null,
    val wcycleIsfMult: Double? = null,
    val thyroidStatus: String? = null,
    val inflammationStatus: String? = null,
    val hrNowBpm: Int? = null,
    val hrAvg15mBpm: Int? = null,
    val rhrRestingBpm: Int? = null,
    val hrvRmssdMs: Double? = null,
    val steps5m: Int? = null,
    val steps15m: Int? = null,
    val steps60m: Int? = null,
    val activityState: String? = null,
    val sleepDebtMinutes: Int? = null,
    val sleepEfficiency: Double? = null,
    val physioSnapshotTimestamp: Long? = null,
    val physioSnapshotValidFlag: Boolean? = null,
    val physioTrace: PhysioDecisionTraceMTR,
    val safetyPhase: String? = null,
    val predictiveHypoSuppressed: Boolean? = null,
    val safetyGate: String? = null,
    val safetyCompositeMinMgdl: Double? = null,
    val safetyUamTerminalMgdl: Double? = null,
    val decisionCompositeMinMgdl: Double? = null,
    val safetyReconcileDeltaMgdl: Double? = null,
    val patientMode: String? = null,
    val patientModeConfidence: Double? = null,
    val patientStrategyHint: String? = null,
    val patientNarrative: String? = null,
    val patientReasonCodes: List<String>? = null,
    val patientPhysioLive: PhysioLiveDigest? = null,
    val patientThermalBelief: ThermalBeliefDigest? = null,
) {
    fun toJSON(datasetId: String, generatedAtIsoUtc: String, appVersion: String, schemaVersion: String): JsonObject =
        buildJsonObject {
            put("dataset_id", datasetId)
            put("generated_at", generatedAtIsoUtc)
            put("app_version", appVersion)
            put("schema_version", schemaVersion)
            put("event_id", eventId)
            put("timestamp", eventTimestamp)
            put("trigger", trigger)
            put("profile_isf_mgdl", profileIsfMgdl)
            put("profile_basal_uph", profileBasalUph)
            put("current_bg_mgdl", currentBgMgdl)
            put("cob_g", cobG)
            put("iob_u", iobU)
            putOrNull("cycle_phase", cyclePhase)
            putOrNull("cycle_day", cycleDay)
            putOrNull("cycle_tracking_mode", cycleTrackingMode)
            putOrNull("contraceptive_type", contraceptiveType)
            putOrNull("wcycle_basal_mult", wcycleBasalMult)
            putOrNull("wcycle_smb_mult", wcycleSmbMult)
            putOrNull("wcycle_isf_mult", wcycleIsfMult)
            putOrNull("thyroid_status", thyroidStatus)
            putOrNull("inflammation_status", inflammationStatus)
            putOrNull("hr_now_bpm", hrNowBpm)
            putOrNull("hr_avg_15m_bpm", hrAvg15mBpm)
            putOrNull("rhr_resting_bpm", rhrRestingBpm)
            putOrNull("hrv_rmssd_ms", hrvRmssdMs)
            putOrNull("steps_5m", steps5m)
            putOrNull("steps_15m", steps15m)
            putOrNull("steps_60m", steps60m)
            putOrNull("activity_state", activityState)
            putOrNull("sleep_debt_minutes", sleepDebtMinutes)
            putOrNull("sleep_efficiency", sleepEfficiency)
            putOrNull("physio_snapshot_timestamp", physioSnapshotTimestamp)
            putOrNull("physio_snapshot_valid_flag", physioSnapshotValidFlag)
            put("physio_state", physioTrace.physioState)
            put("physio_confidence", physioTrace.physioConfidence)
            put("physio_data_quality", physioTrace.physioDataQuality)
            putOrNull("sleep_quality_score", physioTrace.sleepQualityScore)
            put("isf_factor", physioTrace.isfFactor)
            put("basal_factor", physioTrace.basalFactor)
            put("smb_factor", physioTrace.smbFactor)
            put("reactivity_factor", physioTrace.reactivityFactor)
            putOrNull("physio_veto_reason", physioTrace.vetoReason)
            putOrNull("final_loop_decision_type", physioTrace.finalLoopDecisionType)
            putOrNull("smb_action_type", physioTrace.smbActionType)
            putOrNull("basal_action_type", physioTrace.basalActionType)
            put("decision_conflict_flags", stringJsonArray(physioTrace.decisionConflictFlags))
            put("source", physioTrace.source)
            putOrNull("safety_phase", safetyPhase)
            putOrNull("predictive_hypo_suppressed", predictiveHypoSuppressed)
            putOrNull("safety_gate", safetyGate)
            putOrNull("safety_composite_min_mgdl", safetyCompositeMinMgdl)
            putOrNull("safety_uam_terminal_mgdl", safetyUamTerminalMgdl)
            putOrNull("decision_composite_min_mgdl", decisionCompositeMinMgdl)
            putOrNull("safety_reconcile_delta_mgdl", safetyReconcileDeltaMgdl)
            put(
                "patient_story",
                buildJsonObject {
                    putOrNull("patient_mode", patientMode)
                    putOrNull("patient_mode_confidence", patientModeConfidence)
                    putOrNull("patient_strategy_hint", patientStrategyHint)
                    putOrNull("patient_narrative", patientNarrative)
                    putOrNull(
                        "patient_reason_codes",
                        patientReasonCodes?.let { codes -> stringJsonArray(codes) },
                    )
                    putOrNull(
                        "physio_live",
                        patientPhysioLive?.toJsonObject(),
                    )
                    putOrNull(
                        "thermal_belief",
                        patientThermalBelief?.toJsonObject(),
                    )
                },
            )
        }
}
/**
 * Writes [value], or an explicit JSON `null` when it is absent.
 *
 * The study schema keeps every key on every row, so a missing value has to be written rather than
 * skipped - a reader that counts columns must not see a shorter row.
 *
 * `internal` and shared with the Android exporter, which uses the same six overloads for the rows it
 * builds itself. There is one definition, not two.
 */
internal fun JsonObjectBuilder.putOrNull(key: String, value: String?) {
    if (value != null) put(key, value) else put(key, JsonNull)
}

internal fun JsonObjectBuilder.putOrNull(key: String, value: Int?) {
    if (value != null) put(key, value) else put(key, JsonNull)
}

internal fun JsonObjectBuilder.putOrNull(key: String, value: Long?) {
    if (value != null) put(key, value) else put(key, JsonNull)
}

internal fun JsonObjectBuilder.putOrNull(key: String, value: Double?) {
    if (value != null) put(key, value) else put(key, JsonNull)
}

internal fun JsonObjectBuilder.putOrNull(key: String, value: Boolean?) {
    if (value != null) put(key, value) else put(key, JsonNull)
}

internal fun JsonObjectBuilder.putOrNull(key: String, value: JsonElement?) {
    if (value != null) put(key, value) else put(key, JsonNull)
}

internal fun stringJsonArray(values: List<String>): JsonArray =
    buildJsonArray { values.forEach { add(it) } }