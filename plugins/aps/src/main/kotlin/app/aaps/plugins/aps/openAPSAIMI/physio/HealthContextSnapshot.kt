package app.aaps.plugins.aps.openAPSAIMI.physio

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
    val stepsLast15m: Int = 0,
    val stepsLast60m: Int = 0,
    val activityState: String = "IDLE", // IDLE, WALKING, RUNNING, SLEEPING
    
    // ❤️ Heart Metrics
    val hrNow: Int = 0,           // Most recent HR sample
    val hrAvg15m: Int = 0,        // Average HR last 15m
    val hrvRmssd: Double = 0.0,   // Most recent valid HRV (Nocturnal preferred)
    val rhrResting: Int = 0,      // Today's RHR (or 7d baseline if missing)
    
    // 😴 Sleep & Recovery
    val sleepDebtMinutes: Int = 0, // Calculated debt vs Baseline
    val sleepEfficiency: Double = 1.0, // 0.0-1.0
    
    // 🩸 Cardiovascular
    val bpSys: Int = 0,
    val bpDia: Int = 0,
    
    // ℹ️ Metadata
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Double = 0.0, // 0.0-1.0 (Data quality/freshness)
    val source: String = "Unknown", // "HealthConnect", "Watch", "Merged"
    val isValid: Boolean = false
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("steps15", stepsLast15m)
        put("steps60", stepsLast60m)
        put("hr", hrNow)
        put("hrv", hrvRmssd)
        put("rhr", rhrResting)
        put("sleepDebt", sleepDebtMinutes)
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
        // Lower HRV = Higher Stress
        if (hrvRmssd > 0 && hrvRmssd < 30) {
             val suppression = ((30 - hrvRmssd) / 30.0).coerceIn(0.0, 0.2)
             score += suppression
        }
        
        return score.coerceIn(0.0, 1.0)
    }

    companion object {
        val EMPTY = HealthContextSnapshot()
    }
}
