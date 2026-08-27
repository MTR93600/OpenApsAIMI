package app.aaps.plugins.aps.openAPSAIMI.patient

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.SleepLiveDetector

/**
 * Live wearable / phone physio signals shown in Context UI between full loop recomputes.
 */
data class PhysioLiveDigest(
    val stepsLast15m: Int = 0,
    val stepsLast60m: Int = 0,
    val hrNowBpm: Int = 0,
    val hrAvg15mBpm: Int = 0,
    val rhrRestingBpm: Int = 0,
    val hrvRmssd: Double = 0.0,
    val activityState: String = "IDLE",
    val sleepDebtMinutes: Int = 0,
    val asleepLiveConfidence: Double = 0.0,
    val asleepLiveSource: String = SleepLiveDetector.Source.NONE.name,
    val snapshotAgeMs: Long = 0L,
    val source: String = "Unknown",
    val confidence: Double = 0.0,
    val thermalHypothesis: String = "DATA_PENDING",
    val thermalDeltaVsBaselineC: Double = 0.0,
    val thermalInflammationIndex: Double = 0.0,
    val thermalNarrative: String = "",
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("steps_last_15m", stepsLast15m)
            put("steps_last_60m", stepsLast60m)
            put("hr_now_bpm", hrNowBpm)
            put("hr_avg_15m_bpm", hrAvg15mBpm)
            put("rhr_resting_bpm", rhrRestingBpm)
            put("hrv_rmssd", hrvRmssd)
            put("activity_state", activityState)
            put("sleep_debt_minutes", sleepDebtMinutes)
            put("asleep_live_confidence", asleepLiveConfidence)
            put("asleep_live_source", asleepLiveSource)
            put("snapshot_age_ms", snapshotAgeMs)
            put("source", source)
            put("confidence", confidence)
            put("thermal_hypothesis", thermalHypothesis)
            put("thermal_delta_vs_baseline_c", thermalDeltaVsBaselineC)
            put("thermal_inflammation_index", thermalInflammationIndex)
            put("thermal_narrative", thermalNarrative)
        }

    companion object {
        fun from(snapshot: HealthContextSnapshot, nowMs: Long): PhysioLiveDigest {
            val ageMs = if (snapshot.timestamp > 0L) {
                (nowMs - snapshot.timestamp).coerceAtLeast(0L)
            } else {
                0L
            }
            return PhysioLiveDigest(
                stepsLast15m = snapshot.stepsLast15m,
                stepsLast60m = snapshot.stepsLast60m,
                hrNowBpm = snapshot.hrNow,
                hrAvg15mBpm = snapshot.hrAvg15m,
                rhrRestingBpm = snapshot.rhrResting,
                hrvRmssd = snapshot.hrvRmssd,
                activityState = snapshot.activityState,
                sleepDebtMinutes = snapshot.sleepDebtMinutes,
                asleepLiveConfidence = snapshot.asleepLiveConfidence,
                asleepLiveSource = snapshot.asleepLiveSource,
                snapshotAgeMs = ageMs,
                source = snapshot.source,
                confidence = snapshot.confidence,
                thermalHypothesis = snapshot.thermalBelief.hypothesis.name,
                thermalDeltaVsBaselineC = snapshot.thermalBelief.deltaVsBaselineC,
                thermalInflammationIndex = snapshot.thermalBelief.inflammationIndex,
                thermalNarrative = snapshot.thermalBelief.narrative,
            )
        }
    }
}
