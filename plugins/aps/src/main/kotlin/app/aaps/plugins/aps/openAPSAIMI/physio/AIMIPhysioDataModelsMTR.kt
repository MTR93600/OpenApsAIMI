package app.aaps.plugins.aps.openAPSAIMI.physio

import org.json.JSONObject
import java.time.Instant

/**
 * 🏥 AIMI Physiological Assistant - Data Models MTR
 * 
 * Immutable data classes representing physiological state and context.
 * All fields have safe defaults to handle missing data gracefully.
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */

// ═══════════════════════════════════════════════════════════════════════════
// RAW DATA MODELS (From Health Connect)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Raw sleep data from Health Connect
 */
data class SleepDataMTR(
    val startTime: Long,
    val endTime: Long,
    val durationHours: Double = 0.0,
    val efficiency: Double = 0.0, // 0.0-1.0
    val deepSleepMinutes: Int = 0,
    val remSleepMinutes: Int = 0,
    val lightSleepMinutes: Int = 0,
    val awakeMinutes: Int = 0,
    val fragmentationScore: Double = 0.0 // Higher = more fragmented
) {
    fun hasValidData(): Boolean = durationHours > 0.0
    
    companion object {
        val EMPTY = SleepDataMTR(0, 0)
    }
}

/**
 * Raw HRV data from Health Connect
 */
data class HRVDataMTR(
    val timestamp: Long,
    val rmssd: Double = 0.0, // Root Mean Square of Successive Differences (ms)
    val sdnn: Double = 0.0,  // Standard Deviation of NN intervals (ms)
    val source: String = "Unknown"
) {
    fun hasValidData(): Boolean = rmssd > 0.0
    
    companion object {
        val EMPTY = HRVDataMTR(0)
    }
}

/**
 * Raw Resting Heart Rate data
 */
data class RHRDataMTR(
    val timestamp: Long,
    val bpm: Int = 0,
    val source: String = "Unknown"
) {
    fun hasValidData(): Boolean = bpm in 35..120
    
    companion object {
        val EMPTY = RHRDataMTR(0)
    }
}

/**
 * Aggregated raw data container
 */
data class RawPhysioDataMTR(
    val sleep: SleepDataMTR? = null,
    val hrv: List<HRVDataMTR> = emptyList(),
    val rhr: List<RHRDataMTR> = emptyList(),
    val steps: Int = 0,
    val fetchTimestamp: Long = System.currentTimeMillis()
) {
    fun hasAnyData(): Boolean = 
        (sleep?.hasValidData() == true) || 
        hrv.any { it.hasValidData() } || 
        rhr.any { it.hasValidData() } ||
        steps > 0
    
    companion object {
        val EMPTY = RawPhysioDataMTR()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// EXTRACTED FEATURES (Normalized & Processed)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Processed physiological features ready for analysis
 * All values normalized, missing data = 0.0
 */
data class PhysioFeaturesMTR(
    // Sleep metrics
    val sleepDurationHours: Double = 0.0,
    val sleepEfficiency: Double = 0.0, // 0-1
    val sleepFragmentation: Double = 0.0, // 0-1, higher = worse
    val deepSleepPercent: Double = 0.0, // 0-1
    
    // HRV metrics
    val hrvMeanRMSSD: Double = 0.0, // Average over last 7 days
    val hrvTrend: Double = 0.0, // -1 (deteriorating) to +1 (improving)
    val hrvVariability: Double = 0.0, // Coefficient of variation
    
    // RHR metrics
    val rhrMorning: Int = 0, // Lowest RHR in morning window
    val rhrDeviation: Double = 0.0, // Z-score from baseline
    
    // Activity metrics
    val stepsDailyAverage: Int = 0,
    val stepsTrend: Double = 0.0, // -1 to +1
    
    // Metadata
    val timestamp: Long = System.currentTimeMillis(),
    val dataQuality: Double = 0.0, // 0-1, based on completeness
    val hasValidData: Boolean = false
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("sleepDuration", sleepDurationHours)
        put("sleepEfficiency", sleepEfficiency)
        put("hrvMean", hrvMeanRMSSD)
        put("hrvTrend", hrvTrend)
        put("rhrDeviation", rhrDeviation)
        put("stepsAvg", stepsDailyAverage)
        put("timestamp", timestamp)
        put("quality", dataQuality)
    }
    
    companion object {
        val EMPTY = PhysioFeaturesMTR()
        
        fun fromJSON(json: JSONObject): PhysioFeaturesMTR = try {
            PhysioFeaturesMTR(
                sleepDurationHours = json.optDouble("sleepDuration", 0.0),
                sleepEfficiency = json.optDouble("sleepEfficiency", 0.0),
                hrvMeanRMSSD = json.optDouble("hrvMean", 0.0),
                hrvTrend = json.optDouble("hrvTrend", 0.0),
                rhrDeviation = json.optDouble("rhrDeviation", 0.0),
                stepsDailyAverage = json.optInt("stepsAvg", 0),
                timestamp = json.optLong("timestamp", 0),
                dataQuality = json.optDouble("quality", 0.0),
                hasValidData = json.optDouble("quality", 0.0) > 0.3
            )
        } catch (e: Exception) {
            EMPTY
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// BASELINE MODEL (7-day rolling statistics)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 7-day rolling baseline for a single metric
 */
data class MetricBaselineMTR(
    val metricName: String,
    val p25: Double = 0.0, // 25th percentile
    val p50: Double = 0.0, // Median
    val p75: Double = 0.0, // 75th percentile
    val mean: Double = 0.0,
    val stdDev: Double = 0.0,
    val sampleCount: Int = 0
) {
    fun isValid(): Boolean = sampleCount >= 3 // Need at least 3 days
    
    fun zScore(value: Double): Double {
        return if (stdDev > 0.0) (value - mean) / stdDev else 0.0
    }
}

/**
 * Complete 7-day baseline for all metrics
 */
data class PhysioBaselineMTR(
    val sleepDuration: MetricBaselineMTR = MetricBaselineMTR("sleepDuration"),
    val hrvRMSSD: MetricBaselineMTR = MetricBaselineMTR("hrvRMSSD"),
    val morningRHR: MetricBaselineMTR = MetricBaselineMTR("morningRHR"),
    val dailySteps: MetricBaselineMTR = MetricBaselineMTR("dailySteps"),
    val lastUpdateTimestamp: Long = 0,
    val validDaysCount: Int = 0,
    
    // 🆕 RAW HISTORY PERSISTENCE (Crucial for progressive baseline)
    val sleepHistory: Map<Long, Double> = emptyMap(),
    val hrvHistory: Map<Long, Double> = emptyMap(),
    val rhrHistory: Map<Long, Int> = emptyMap(),
    val stepsHistory: Map<Long, Int> = emptyMap()
) {
    fun isValid(): Boolean = validDaysCount >= 3
    
    fun toJSON(): JSONObject = JSONObject().apply {
        put("sleepDuration", with(sleepDuration) {
            JSONObject().apply {
                put("p25", p25); put("p50", p50); put("p75", p75)
                put("mean", mean); put("stdDev", stdDev); put("count", sampleCount)
            }
        })
        put("hrvRMSSD", with(hrvRMSSD) {
            JSONObject().apply {
                put("p25", p25); put("p50", p50); put("p75", p75)
                put("mean", mean); put("stdDev", stdDev); put("count", sampleCount)
            }
        })
        put("morningRHR", with(morningRHR) {
            JSONObject().apply {
                put("p25", p25); put("p50", p50); put("p75", p75)
                put("mean", mean); put("stdDev", stdDev); put("count", sampleCount)
            }
        })
        put("dailySteps", with(dailySteps) {
            JSONObject().apply {
                put("p25", p25); put("p50", p50); put("p75", p75)
                put("mean", mean); put("stdDev", stdDev); put("count", sampleCount)
            }
        })
        put("lastUpdate", lastUpdateTimestamp)
        put("validDays", validDaysCount)
        
        // Serialize History (Compact format)
        put("history", JSONObject().apply {
            put("sleep", JSONObject().apply { sleepHistory.forEach { (k, v) -> put(k.toString(), v) } })
            put("hrv", JSONObject().apply { hrvHistory.forEach { (k, v) -> put(k.toString(), v) } })
            put("rhr", JSONObject().apply { rhrHistory.forEach { (k, v) -> put(k.toString(), v) } })
            put("steps", JSONObject().apply { stepsHistory.forEach { (k, v) -> put(k.toString(), v) } })
        })
    }
    
    companion object {
        val EMPTY = PhysioBaselineMTR()
        
        fun fromJSON(json: JSONObject): PhysioBaselineMTR = try {
            fun parseMetric(name: String, obj: JSONObject?): MetricBaselineMTR {
                if (obj == null) return MetricBaselineMTR(name)
                return MetricBaselineMTR(
                    metricName = name,
                    p25 = obj.optDouble("p25", 0.0),
                    p50 = obj.optDouble("p50", 0.0),
                    p75 = obj.optDouble("p75", 0.0),
                    mean = obj.optDouble("mean", 0.0),
                    stdDev = obj.optDouble("stdDev", 0.0),
                    sampleCount = obj.optInt("count", 0)
                )
            }
            
            PhysioBaselineMTR(
                sleepDuration = parseMetric("sleepDuration", json.optJSONObject("sleepDuration")),
                hrvRMSSD = parseMetric("hrvRMSSD", json.optJSONObject("hrvRMSSD")),
                morningRHR = parseMetric("morningRHR", json.optJSONObject("morningRHR")),
                dailySteps = parseMetric("dailySteps", json.optJSONObject("dailySteps")),
                lastUpdateTimestamp = json.optLong("lastUpdate", 0),
                validDaysCount = json.optInt("validDays", 0),
                
                // Restore History
                sleepHistory = json.optJSONObject("history")?.optJSONObject("sleep")?.let { obj ->
                    obj.keys().asSequence().associate { it.toLong() to obj.getDouble(it) }
                } ?: emptyMap(),
                
                hrvHistory = json.optJSONObject("history")?.optJSONObject("hrv")?.let { obj ->
                    obj.keys().asSequence().associate { it.toLong() to obj.getDouble(it) }
                } ?: emptyMap(),
                
                rhrHistory = json.optJSONObject("history")?.optJSONObject("rhr")?.let { obj ->
                    obj.keys().asSequence().associate { it.toLong() to obj.getInt(it) }
                } ?: emptyMap(),
                
                stepsHistory = json.optJSONObject("history")?.optJSONObject("steps")?.let { obj ->
                    obj.keys().asSequence().associate { it.toLong() to obj.getInt(it) }
                } ?: emptyMap()
            )
        } catch (e: Exception) {
            EMPTY
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHYSIOLOGICAL CONTEXT (Analyzed State)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Detected physiological state
 */
enum class PhysioStateMTR {
    OPTIMAL,         // All metrics normal
    RECOVERY_NEEDED, // Poor sleep, low HRV
    STRESS_DETECTED, // High RHR, low HRV
    INFECTION_RISK,  // Multiple anomalies
    UNKNOWN          // Insufficient data
}

/**
 * Complete physiological context with analysis
 */
data class PhysioContextMTR(
    val state: PhysioStateMTR = PhysioStateMTR.UNKNOWN,
    val confidence: Double = 0.0, // 0-1
    
    // Deviation flags
    val poorSleepDetected: Boolean = false,
    val hrvDepressed: Boolean = false,
    val rhrElevated: Boolean = false,
    val activityReduced: Boolean = false,
    
    // Quantitative deviations (Z-scores)
    val sleepDeviationZ: Double = 0.0,
    val hrvDeviationZ: Double = 0.0,
    val rhrDeviationZ: Double = 0.0,
    
    // Recommendations (deterministic)
    val recommendReduceBasal: Boolean = false,
    val recommendReduceSMB: Boolean = false,
    val recommendIncreaseISF: Boolean = false,
    
    // Metadata
    val timestamp: Long = System.currentTimeMillis(),
    val validUntil: Long = timestamp + (20 * 60 * 60 * 1000), // 20h
    val narrative: String = "", // LLM-generated, optional
    val features: PhysioFeaturesMTR? = null
) {
    fun isValid(): Boolean = System.currentTimeMillis() < validUntil && confidence > 0.3
    
    fun ageSeconds(): Long = (System.currentTimeMillis() - timestamp) / 1000
    
    fun toJSON(): JSONObject = JSONObject().apply {
        put("state", state.name)
        put("confidence", confidence)
        put("poorSleep", poorSleepDetected)
        put("hrvDepressed", hrvDepressed)
        put("rhrElevated", rhrElevated)
        put("sleepZ", sleepDeviationZ)
        put("hrvZ", hrvDeviationZ)
        put("rhrZ", rhrDeviationZ)
        put("reduceBasal", recommendReduceBasal)
        put("reduceSMB", recommendReduceSMB)
        put("increaseISF", recommendIncreaseISF)
        put("timestamp", timestamp)
        put("validUntil", validUntil)
        put("narrative", narrative)
        if (features != null) put("features", features.toJSON())
    }
    
    companion object {
        val NEUTRAL = PhysioContextMTR(
            state = PhysioStateMTR.UNKNOWN,
            confidence = 0.0
        )
        
        fun fromJSON(json: JSONObject): PhysioContextMTR = try {
            PhysioContextMTR(
                state = PhysioStateMTR.valueOf(json.optString("state", "UNKNOWN")),
                confidence = json.optDouble("confidence", 0.0),
                poorSleepDetected = json.optBoolean("poorSleep", false),
                hrvDepressed = json.optBoolean("hrvDepressed", false),
                rhrElevated = json.optBoolean("rhrElevated", false),
                sleepDeviationZ = json.optDouble("sleepZ", 0.0),
                hrvDeviationZ = json.optDouble("hrvZ", 0.0),
                rhrDeviationZ = json.optDouble("rhrZ", 0.0),
                recommendReduceBasal = json.optBoolean("reduceBasal", false),
                recommendReduceSMB = json.optBoolean("reduceSMB", false),
                recommendIncreaseISF = json.optBoolean("increaseISF", false),
                timestamp = json.optLong("timestamp", 0),
                validUntil = json.optLong("validUntil", 0),
                narrative = json.optString("narrative", ""),
                features = json.optJSONObject("features")?.let { PhysioFeaturesMTR.fromJSON(it) }
            )
        } catch (e: Exception) {
            NEUTRAL
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// INSULIN DECISION MULTIPLIERS (Output for APS)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Multipliers to apply to insulin parameters (soft caps enforced)
 */
data class PhysioMultipliersMTR(
    val isfFactor: Double = 1.0,      // ISF multiplier (0.85 - 1.15)
    val basalFactor: Double = 1.0,    // Basal multiplier (0.85 - 1.15)
    val smbFactor: Double = 1.0,      // SMB multiplier (0.90 - 1.10)
    val reactivityFactor: Double = 1.0, // Reactivity modulation (0.90 - 1.10)
    val confidence: Double = 0.0,     // Confidence in these multipliers
    val appliedCaps: String = "",     // Description of applied limits
    val source: String = "Deterministic" // "Deterministic" or "LLM-Assisted"
) {
    fun isNeutral(): Boolean = 
        isfFactor == 1.0 && 
        basalFactor == 1.0 && 
        smbFactor == 1.0 && 
        reactivityFactor == 1.0
    
    companion object {
        val NEUTRAL = PhysioMultipliersMTR()
        
        // Soft caps constants
        const val ISF_MIN = 0.85
        const val ISF_MAX = 1.15
        const val BASAL_MIN = 0.85
        const val BASAL_MAX = 1.15
        const val SMB_MIN = 0.90
        const val SMB_MAX = 1.10
        const val REACTIVITY_MIN = 0.90
        const val REACTIVITY_MAX = 1.10
    }
}
