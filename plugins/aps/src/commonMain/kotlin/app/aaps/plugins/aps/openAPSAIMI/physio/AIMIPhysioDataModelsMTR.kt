package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.core.data.json.OrgJsonCompat.hasCompat
import app.aaps.core.data.json.OrgJsonCompat.optBooleanCompat
import app.aaps.core.data.json.OrgJsonCompat.optDoubleCompat
import app.aaps.core.data.json.OrgJsonCompat.optIntCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs

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
    val fragmentationScore: Double = 0.0, // Higher = more fragmented
    /** HC session covering [now] when both bounds set (live asleep). */
    val ongoingStartMs: Long? = null,
    val ongoingEndMs: Long? = null,
) {
    fun hasValidData(): Boolean = durationHours > 0.0

    fun isOngoingAt(nowMs: Long): Boolean {
        val start = ongoingStartMs ?: return false
        val end = ongoingEndMs ?: return false
        return nowMs in start..end
    }
    
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
    val fetchTimestamp: Long = aimiWallClockMs(),
    /** Vitals from PersistenceLayer via UnifiedActivityProvider (Wear / HealthConnect sync / phone) when HC-native fetch is empty. */
    val ambientHeartRateBpm: Int = 0,
    /** Approximate step signal from unified DB window (e.g. last 24h sum of 5m buckets). */
    val ambientStepsAggregated: Int = 0
) {
    fun hasAnyData(): Boolean =
        (sleep?.hasValidData() == true) ||
        hrv.any { it.hasValidData() } ||
        rhr.any { it.hasValidData() } ||
        steps > 0 ||
        ambientHeartRateBpm > 0 ||
        ambientStepsAggregated > 0
    
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
    val sleepQualityScore: Double = 0.0, // 0-1, higher = better
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
    val timestamp: Long = aimiWallClockMs(),
    val dataQuality: Double = 0.0, // 0-1, based on completeness
    val hasValidData: Boolean = false
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("sleepDuration", sleepDurationHours)
        put("sleepEfficiency", sleepEfficiency)
        put("sleepQuality", sleepQualityScore)
        put("hrvMean", hrvMeanRMSSD)
        put("hrvTrend", hrvTrend)
        put("rhrDeviation", rhrDeviation)
        put("stepsAvg", stepsDailyAverage)
        put("timestamp", timestamp)
        put("quality", dataQuality)
    }
    
    companion object {
        val EMPTY = PhysioFeaturesMTR()
        
        fun fromJSON(json: JsonObject): PhysioFeaturesMTR = try {
            PhysioFeaturesMTR(
                sleepDurationHours = json.optDoubleCompat("sleepDuration", 0.0),
                sleepEfficiency = json.optDoubleCompat("sleepEfficiency", 0.0),
                sleepQualityScore = json.optDoubleCompat("sleepQuality", 0.0),
                hrvMeanRMSSD = json.optDoubleCompat("hrvMean", 0.0),
                hrvTrend = json.optDoubleCompat("hrvTrend", 0.0),
                rhrDeviation = json.optDoubleCompat("rhrDeviation", 0.0),
                stepsDailyAverage = json.optIntCompat("stepsAvg", 0),
                timestamp = json.optLongCompat("timestamp", 0),
                dataQuality = json.optDoubleCompat("quality", 0.0),
                hasValidData = json.optDoubleCompat("quality", 0.0) > 0.3
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
    
    fun toJSON(): JsonObject = buildJsonObject {
        put("sleepDuration", with(sleepDuration) {
            buildJsonObject {
                put("p25", p25); put("p50", p50); put("p75", p75)
                put("mean", mean); put("stdDev", stdDev); put("count", sampleCount)
            }
        })
        put("hrvRMSSD", with(hrvRMSSD) {
            buildJsonObject {
                put("p25", p25); put("p50", p50); put("p75", p75)
                put("mean", mean); put("stdDev", stdDev); put("count", sampleCount)
            }
        })
        put("morningRHR", with(morningRHR) {
            buildJsonObject {
                put("p25", p25); put("p50", p50); put("p75", p75)
                put("mean", mean); put("stdDev", stdDev); put("count", sampleCount)
            }
        })
        put("dailySteps", with(dailySteps) {
            buildJsonObject {
                put("p25", p25); put("p50", p50); put("p75", p75)
                put("mean", mean); put("stdDev", stdDev); put("count", sampleCount)
            }
        })
        put("lastUpdate", lastUpdateTimestamp)
        put("validDays", validDaysCount)
        
        // Serialize History (Compact format)
        put("history", buildJsonObject {
            put("sleep", buildJsonObject { sleepHistory.forEach { (k, v) -> put(k.toString(), v) } })
            put("hrv", buildJsonObject { hrvHistory.forEach { (k, v) -> put(k.toString(), v) } })
            put("rhr", buildJsonObject { rhrHistory.forEach { (k, v) -> put(k.toString(), v) } })
            put("steps", buildJsonObject { stepsHistory.forEach { (k, v) -> put(k.toString(), v) } })
        })
    }
    
    companion object {
        val EMPTY = PhysioBaselineMTR()
        
        fun fromJSON(json: JsonObject): PhysioBaselineMTR = try {
            fun parseMetric(name: String, obj: JsonObject?): MetricBaselineMTR {
                if (obj == null) return MetricBaselineMTR(name)
                return MetricBaselineMTR(
                    metricName = name,
                    p25 = obj.optDoubleCompat("p25", 0.0),
                    p50 = obj.optDoubleCompat("p50", 0.0),
                    p75 = obj.optDoubleCompat("p75", 0.0),
                    mean = obj.optDoubleCompat("mean", 0.0),
                    stdDev = obj.optDoubleCompat("stdDev", 0.0),
                    sampleCount = obj.optIntCompat("count", 0)
                )
            }
            
            PhysioBaselineMTR(
                sleepDuration = parseMetric("sleepDuration", json.optJsonObjectCompat("sleepDuration")),
                hrvRMSSD = parseMetric("hrvRMSSD", json.optJsonObjectCompat("hrvRMSSD")),
                morningRHR = parseMetric("morningRHR", json.optJsonObjectCompat("morningRHR")),
                dailySteps = parseMetric("dailySteps", json.optJsonObjectCompat("dailySteps")),
                lastUpdateTimestamp = json.optLongCompat("lastUpdate", 0),
                validDaysCount = json.optIntCompat("validDays", 0),
                
                // Restore History
                sleepHistory = json.optJsonObjectCompat("history")?.optJsonObjectCompat("sleep")?.let { obj ->
                    obj.keys.asSequence().associate { it.toLong() to obj.optDoubleCompat(it, 0.0) }
                } ?: emptyMap(),
                
                hrvHistory = json.optJsonObjectCompat("history")?.optJsonObjectCompat("hrv")?.let { obj ->
                    obj.keys.asSequence().associate { it.toLong() to obj.optDoubleCompat(it, 0.0) }
                } ?: emptyMap(),
                
                rhrHistory = json.optJsonObjectCompat("history")?.optJsonObjectCompat("rhr")?.let { obj ->
                    obj.keys.asSequence().associate { it.toLong() to obj.optIntCompat(it, 0) }
                } ?: emptyMap(),
                
                stepsHistory = json.optJsonObjectCompat("history")?.optJsonObjectCompat("steps")?.let { obj ->
                    obj.keys.asSequence().associate { it.toLong() to obj.optIntCompat(it, 0) }
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
    val timestamp: Long = aimiWallClockMs(),
    val validUntil: Long = timestamp + (20 * 60 * 60 * 1000), // 20h
    val narrative: String = "", // LLM-generated, optional
    val features: PhysioFeaturesMTR? = null
) {
    fun isValid(): Boolean = aimiWallClockMs() < validUntil && confidence > 0.3
    
    fun ageSeconds(): Long = (aimiWallClockMs() - timestamp) / 1000
    
    fun toJSON(): JsonObject = buildJsonObject {
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
        
        fun fromJSON(json: JsonObject): PhysioContextMTR = try {
            PhysioContextMTR(
                state = PhysioStateMTR.valueOf(json.let { o -> if (o.hasCompat("state")) o.optStringCompat("state") else "UNKNOWN" }),
                confidence = json.optDoubleCompat("confidence", 0.0),
                poorSleepDetected = json.optBooleanCompat("poorSleep"),
                hrvDepressed = json.optBooleanCompat("hrvDepressed"),
                rhrElevated = json.optBooleanCompat("rhrElevated"),
                sleepDeviationZ = json.optDoubleCompat("sleepZ", 0.0),
                hrvDeviationZ = json.optDoubleCompat("hrvZ", 0.0),
                rhrDeviationZ = json.optDoubleCompat("rhrZ", 0.0),
                recommendReduceBasal = json.optBooleanCompat("reduceBasal"),
                recommendReduceSMB = json.optBooleanCompat("reduceSMB"),
                recommendIncreaseISF = json.optBooleanCompat("increaseISF"),
                timestamp = json.optLongCompat("timestamp", 0),
                validUntil = json.optLongCompat("validUntil", 0),
                narrative = json.optStringCompat("narrative"),
                features = json.optJsonObjectCompat("features")?.let { PhysioFeaturesMTR.fromJSON(it) }
            )
        } catch (e: Exception) {
            NEUTRAL
        }
    }
}

/**
 * Converts physiological context into a Risk Aversion Factor (0.0-1.0)
 * 
 * CLINICAL SAFETY LOGIC:
 * "In times of uncertainty or stress, play it safe."
 * 
 * 1.0 = Optimal State (Normal Operation, Full Confidence)
 * <1.0 = Protective Mode (Reduce Aggression to avoid Hypo on sick/stressed body)
 * 
 * Mapping:
 * - OPTIMAL -> 1.0 (Normal)
 * - UNKNOWN -> 1.0 (Neutral/Fail-safe)
 * - RECOVERY -> 0.9 (Mild Caution, -10%)
 * - STRESS  -> 0.8 (High Caution, -20%)
 * - INFECTION -> 0.8 (High Caution, -20%)
 */
fun PhysioContextMTR.toRiskAversionFactor(): Double {
    return when (this.state) {
        // Optimal or Unknown -> No Modulation (Neutral)
        PhysioStateMTR.OPTIMAL, PhysioStateMTR.UNKNOWN -> 1.0 
        
        // Mild Caution (Recovery needed) -> 90% Aggressiveness
        PhysioStateMTR.RECOVERY_NEEDED -> 0.9 
        
        // High Caution (Stress/Infection) -> 80% Aggressiveness
        // We brake because stress/sickness makes insulin sensitivity unpredictable.
        // Safety First: Avoid hypo > Avoid hyper.
        PhysioStateMTR.STRESS_DETECTED, PhysioStateMTR.INFECTION_RISK -> 0.8 
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
    val peakShiftMinutes: Int = 0,    // 🌀 Phase Shift (Cosine Gate)
    val trajectoryRelevanceScore: Double = 0.0, // 🌀 Relevance Score (0.0 - 1.0)
    val confidence: Double = 0.0,     // Confidence in these multipliers
    val appliedCaps: String = "",     // Description of applied limits
    val source: String = "Deterministic", // "Deterministic" or "LLM-Assisted"
    val detailedReason: String = "",       // 🌀 Internal Debug Info (e.g. Cosine Gate breakdown)
    val physiologicalPhase: PhysiologicalPhase = PhysiologicalPhase.OFF,
    val phaseConfidence: Double = 0.0,
) {
    fun isNeutral(): Boolean = 
        isfFactor == 1.0 && 
        basalFactor == 1.0 && 
        smbFactor == 1.0 && 
        reactivityFactor == 1.0 &&
        peakShiftMinutes == 0
    
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

// ═══════════════════════════════════════════════════════════════════════════
// STANDARDIZED INPUTS FOR ADJUSTERS
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Standard container for all inputs required by physiological adjusters.
 * Allows decoupling the adjuster logic from the source of data.
 */
data class AimiPhysioInputs(
    val bg: Double,
    val delta: Double,
    val physioContext: PhysioContextMTR? = null,
    val isMealActive: Boolean = false,
    val activityState: String = "IDLE" // e.g., "WALKING", "RUNNING"
)

/**
 * Standardized trace payload for study/export pipelines.
 * Captures a single physio decision event from adapter runtime.
 */
data class PhysioDecisionTraceMTR(
    val timestamp: Long = aimiWallClockMs(),
    val physioState: String = PhysioStateMTR.UNKNOWN.name,
    val physioConfidence: Double = 0.0,
    val physioDataQuality: Double = 0.0,
    val sleepQualityScore: Double? = null,
    val isfFactor: Double = 1.0,
    val basalFactor: Double = 1.0,
    val smbFactor: Double = 1.0,
    val reactivityFactor: Double = 1.0,
    val inflammationLatentIndex: Double = 0.0,
    val inflammationConfidence: Double = 0.0,
    val inflammationTimescale: String = "UNKNOWN",
    val inflammationDrivers: List<String> = emptyList(),
    val shadowOrchestratorEnabled: Boolean = false,
    val shadowBudgetedIsfFactor: Double = 1.0,
    val shadowBudgetedBasalFactor: Double = 1.0,
    val shadowBudgetedSmbFactor: Double = 1.0,
    val shadowOverlapPenalty: Double = 1.0,
    val shadowContributions: Map<String, Double> = emptyMap(),
    val shadowNotes: List<String> = emptyList(),
    val vetoReason: String? = null,
    val finalLoopDecisionType: String? = null,
    val smbActionType: String? = null,
    val basalActionType: String? = null,
    val decisionConflictFlags: List<String> = emptyList(),
    val source: String = "Deterministic"
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("timestamp", timestamp)
        put("physio_state", physioState)
        put("physio_confidence", physioConfidence)
        put("physio_data_quality", physioDataQuality)
        put("sleep_quality_score", sleepQualityScore)
        put("isf_factor", isfFactor)
        put("basal_factor", basalFactor)
        put("smb_factor", smbFactor)
        put("reactivity_factor", reactivityFactor)
        put("inflammation_latent_index", inflammationLatentIndex)
        put("inflammation_confidence", inflammationConfidence)
        put("inflammation_timescale", inflammationTimescale)
        put("inflammation_drivers", JsonArray(inflammationDrivers.map { JsonPrimitive(it) }))
        put("shadow_orchestrator_enabled", shadowOrchestratorEnabled)
        put("shadow_budgeted_isf_factor", shadowBudgetedIsfFactor)
        put("shadow_budgeted_basal_factor", shadowBudgetedBasalFactor)
        put("shadow_budgeted_smb_factor", shadowBudgetedSmbFactor)
        put("shadow_overlap_penalty", shadowOverlapPenalty)
        put("shadow_contributions", buildJsonObject { shadowContributions.forEach { (k, v) -> put(k, v) } })
        put("shadow_notes", JsonArray(shadowNotes.map { JsonPrimitive(it) }))
        put("physio_veto_reason", vetoReason)
        put("final_loop_decision_type", finalLoopDecisionType)
        put("smb_action_type", smbActionType)
        put("basal_action_type", basalActionType)
        put("decision_conflict_flags", JsonArray(decisionConflictFlags.map { JsonPrimitive(it) }))
        put("source", source)
    }
}

data class InflammationLatentStateMTR(
    val index: Double = 0.0,                // 0..1
    val confidence: Double = 0.0,           // 0..1
    val timescale: InflammationTimescaleMTR = InflammationTimescaleMTR.UNKNOWN,
    val drivers: List<String> = emptyList()
)

enum class InflammationTimescaleMTR {
    ACUTE,
    SUBACUTE,
    CHRONIC,
    UNKNOWN
}

/**
 * Shadow estimator only: computes an explicit latent inflammation index from
 * already available proxy signals, without modifying insulin dosing logic.
 */
class InflammationLatentEstimatorMTR {
    fun estimate(
        context: PhysioContextMTR,
        snapshot: HealthContextSnapshot
    ): InflammationLatentStateMTR {
        val drivers = mutableListOf<String>()
        var weighted = 0.0
        var totalWeight = 0.0

        fun add(weight: Double, score: Double, label: String, active: Boolean) {
            totalWeight += weight
            weighted += weight * score.coerceIn(0.0, 1.0)
            if (active) drivers.add(label)
        }

        val poorSleepScore = when {
            context.poorSleepDetected -> 0.8
            snapshot.sleepDebtMinutes >= 90 -> 0.7
            snapshot.sleepDebtMinutes >= 45 -> 0.45
            else -> 0.1
        }
        add(0.30, poorSleepScore, "sleep_burden", context.poorSleepDetected || snapshot.sleepDebtMinutes >= 45)

        val autonomicScore = when {
            context.hrvDepressed && context.rhrElevated -> 0.9
            context.hrvDepressed || context.rhrElevated -> 0.6
            snapshot.hrvRmssd in 1.0..22.0 -> 0.55
            else -> 0.15
        }
        add(0.35, autonomicScore, "autonomic_stress", context.hrvDepressed || context.rhrElevated)

        val lowActivityScore = if (context.activityReduced) 0.55 else 0.2
        add(0.20, lowActivityScore, "reduced_activity", context.activityReduced)

        val anomalyPressure = (listOf(
            context.poorSleepDetected,
            context.hrvDepressed,
            context.rhrElevated,
            context.activityReduced
        ).count { it } / 4.0)
        add(0.15, anomalyPressure, "multi_signal_pressure", anomalyPressure >= 0.5)

        val index = if (totalWeight > 0.0) (weighted / totalWeight).coerceIn(0.0, 1.0) else 0.0
        val confidence = ((context.confidence * 0.6) + (snapshot.confidence * 0.4)).coerceIn(0.0, 1.0)
        val timescale = when {
            context.state == PhysioStateMTR.INFECTION_RISK || (index >= 0.75 && context.rhrElevated) -> InflammationTimescaleMTR.ACUTE
            index >= 0.55 -> InflammationTimescaleMTR.SUBACUTE
            index >= 0.30 -> InflammationTimescaleMTR.CHRONIC
            else -> InflammationTimescaleMTR.UNKNOWN
        }

        return InflammationLatentStateMTR(
            index = index,
            confidence = confidence,
            timescale = timescale,
            drivers = drivers
        )
    }
}
