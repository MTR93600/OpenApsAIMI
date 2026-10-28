package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import org.json.JSONObject

/**
 * 🏥 AIMI Health Context Snapshot
 * 
 * Single Source of Truth for physiological data at a specific point in time.
 * Used by insulin decision logic to apply modulations.
 * 
 * Contains merged data from:
 * - Health Connect (Historical/Background)
 * - Wearable/Watch (Real-time)
 * - Internal Aggregators (Sliding windows)
 */
data class HealthContextSnapshot(
    // 🏃 Activity (Real-time & Recent)
    val stepsLast5m: Int = 0,
    val stepsLast15m: Int = 0,
    val stepsLast60m: Int = 0,
    val activityState: String = "IDLE", // IDLE, WALKING, RUNNING, SLEEPING
    
    // ❤️ Heart Metrics
    val hrNow: Int = 0,           // Most recent HR sample
    val hrAvg15m: Int = 0,        // Average HR last 15m
    /**
     * When [hrNow] was actually measured, or 0 when there is no usable reading.
     *
     * Kept apart from [timestamp] on purpose. A snapshot is re-stamped on every refresh, so before
     * this field existed a heart rate carried forward from an earlier refresh was indistinguishable
     * from one measured this minute — and the re-dating compounded tick after tick. See
     * [HeartRateCarryForward].
     */
    val hrMeasuredAtMs: Long = 0L,
    val hrvRmssd: Double = 0.0,   // Most recent valid HRV (Nocturnal preferred)
    /**
     * How far below this person's own normal their HRV is, from 0.0 to 1.0. See [HrvDepression].
     *
     * **A consumer should read this, not [hrvRmssd].** Three places used to compare the raw value
     * against fixed bands (`1.0..22.0`, `< 30`, `1.0..30.0`), so a person whose own normal RMSSD is
     * 25 was read as stressed all the time, and a person whose normal is 90 never was.
     *
     * `0.0` means "normal, or not known". A cold start answers `0.0` on purpose: with fewer than
     * three baseline samples there is no personal normal to compare against.
     */
    val hrvDepression: Double = 0.0,
    /**
     * The person's HRV baseline when this snapshot was taken, exported so the change from fixed
     * bands to a person-relative reading can be judged from real data later.
     *
     * Without the sample count a reader cannot tell the two reasons for [hrvDepression] `0.0`
     * apart: a cold start with no baseline yet, and an HRV sitting exactly at this person's normal.
     */
    val hrvBaselineMean: Double = 0.0,
    val hrvBaselineSd: Double = 0.0,
    val hrvBaselineSamples: Int = 0,
    val rhrResting: Int = 0,      // Today's RHR (or 7d baseline if missing)
    
    // 😴 Sleep & Recovery
    val sleepDebtMinutes: Int = 0, // Calculated debt vs Baseline
    val sleepEfficiency: Double = 1.0, // 0.0-1.0
    /** Health Connect session active at snapshot time. */
    val hcSleepSessionActive: Boolean = false,
    /** Live asleep confidence 0..1 (wearable/HC/therapy — therapy merged at RBT tick). */
    val asleepLiveConfidence: Double = 0.0,
    val asleepLiveSource: String = SleepLiveDetector.Source.NONE.name,
    
    // 🩸 Cardiovascular
    val bpSys: Int = 0,
    val bpDia: Int = 0,

    // 🌡️ Thermal rhythm (skin delta evolution — Garmin / Oura via Health Connect)
    val thermalBelief: ThermalBeliefDigest = ThermalBeliefDigest.EMPTY,
    
    // ℹ️ Metadata
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Double = 0.0, // 0.0-1.0 (Data quality/freshness)
    val source: String = "Unknown", // "HealthConnect", "Watch", "Merged"
    val isValid: Boolean = false
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("steps5", stepsLast5m)
        put("steps15", stepsLast15m)
        put("steps60", stepsLast60m)
        put("hr", hrNow)
        put("hrv", hrvRmssd)
        // Exported for the move from fixed HRV bands to a person-relative reading. `hrv_was_low` is
        // what the old `hrvRmssd < 30` band would have answered, kept beside the new value so the
        // two can be compared over real days before the sigma threshold in `HrvDepression` is
        // settled. Remove these once that is done.
        put("hrv_depression", hrvDepression)
        put("hrv_base_mean", hrvBaselineMean)
        put("hrv_base_sd", hrvBaselineSd)
        put("hrv_base_n", hrvBaselineSamples)
        put("hrv_was_low", hrvRmssd > 0 && hrvRmssd < 30)
        put("rhr", rhrResting)
        put("sleepDebt", sleepDebtMinutes)
        put("hcSleepActive", hcSleepSessionActive)
        put("asleepLive", asleepLiveConfidence)
        put("asleepLiveSrc", asleepLiveSource)
        put("bp", "$bpSys/$bpDia")
        put("conf", confidence)
        put("ts", timestamp)
    }

    /**
     * Estimates Sympathetic Nervous System (SNS) Dominance based on Snapshot.
     * Returns 0.0 (Para-sympathetic/Rest) to 1.0 (Sympathetic/Stress/Activity).
     * 0.3-0.5 is Neutral.
     */
    fun toSNSDominance(): Double {
        var score = 0.3 // Default basal state
        
        // 1. Activity Influence (+0.0 to +0.5)
        if (stepsLast15m > 0) {
            val activityScore = (stepsLast15m / 2000.0).coerceIn(0.0, 0.5)
            score += activityScore
        }
        
        // 2. HR Elevation (+0.0 to +0.3)
        if (hrNow > 0 && rhrResting > 0) {
            val elevation = hrNow - rhrResting
            if (elevation > 10) {
                 score += (elevation / 100.0).coerceIn(0.0, 0.3)
            }
        }
        
        // 3. HRV Suppression (+0.0 to +0.2)
        // Lower HRV = Higher Stress. Was `hrvRmssd < 30` ramped over a fixed 30 ms. The ramp is now
        // over this person's own spread, so the same share of the score is reached at the same
        // distance from *their* normal. An unknown baseline gives 0.0, as an absent HRV always did.
        score += (hrvDepression * 0.2).coerceIn(0.0, 0.2)
        
        return score.coerceIn(0.0, 1.0)
    }

    companion object {
        val EMPTY = HealthContextSnapshot()
    }
}
