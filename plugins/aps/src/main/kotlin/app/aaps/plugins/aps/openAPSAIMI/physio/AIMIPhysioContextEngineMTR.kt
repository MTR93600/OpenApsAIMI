package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 🧠 AIMI Physiological Context Engine - MTR Implementation
 * 
 * Deterministic analysis engine that detects physiological states
 * based on deviation patterns from baseline.
 * 
 * States Detected:
 * - OPTIMAL: All metrics within normal range
 * - RECOVERY_NEEDED: Poor sleep, low HRV (reduce insulin aggression)
 * - STRESS_DETECTED: Elevated RHR, low HRV (caution mode)
 * - INFECTION_RISK: Multiple anomalies (protective mode)
 * - UNKNOWN: Insufficient data
 * 
 * This engine is PURELY deterministic and rule-based.
 * LLM integration is optional and handled separately.
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMIPhysioContextEngineMTR @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "PhysioContextEngine"
        
        // Deviation thresholds (Z-scores)
        private const val SIGNIFICANT_DEVIATION_Z = 1.5
        private const val SEVERE_DEVIATION_Z = 2.0
        
        // Feature thresholds
        private const val POOR_SLEEP_THRESHOLD_HOURS = 5.5
        private const val LOW_SLEEP_EFFICIENCY = 0.70 // 70%
        private const val HIGH_FRAGMENTATION = 0.30 // 30%
        private const val LOW_HRV_PERCENTILE_THRESHOLD = 0.25 // P25
        private const val HIGH_RHR_PERCENTILE_THRESHOLD = 0.75 // P75
        
        // Confidence scoring
        private const val MIN_DATA_QUALITY_FOR_CONFIDENCE = 0.4
        // 🚀 CHANGED: Activate immediately to show data (Absolute thresholds apply even without baseline)
        private const val MIN_BASELINE_VALIDITY_DAYS = 0
    }
    
    /**
     * Analyzes features and baseline to produce physiological context
     * 
     * @param features Current physiological features
     * @param baseline 7-day rolling baseline
     * @return PhysioContextMTR with state and recommendations
     */
    fun analyze(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR
    ): PhysioContextMTR {
        
        // Early exit if insufficient data
        if (!features.hasValidData) {
            aapsLogger.debug(LTag.APS, "[$TAG] No valid features - returning NEUTRAL context")
            return PhysioContextMTR.NEUTRAL
        }
        
        if (baseline.validDaysCount < 1) {
            aapsLogger.debug(LTag.APS, "[$TAG] Baseline empty (Day 0) - returning LEARNING context")
            return PhysioContextMTR.NEUTRAL.copy(
                features = features,
                narrative = "Building Initial Baseline (Day 0/1)"
            )
        }
        
        // 🚀 PROGRESSIVE CONFIDENCE: Proceed even with partial baseline (Day 1+)
        if (!baseline.isValid()) {
             aapsLogger.info(LTag.APS, "[$TAG] Using partial baseline (Day ${baseline.validDaysCount}/$MIN_BASELINE_VALIDITY_DAYS)")
        }
        
        // Calculate deviations
        val deviations = calculateDeviations(features, baseline)
        
        // Detect anomaly flags
        val flags = detectAnomalyFlags(features, baseline, deviations)
        
        // Determine physiological state
        val state = determinePhysiologicalState(flags, deviations)
        
        // Generate recommendations
        val recommendations = generateRecommendations(state, flags, deviations)
        
        // Calculate confidence
        val confidence = calculateConfidence(features, baseline, flags)
        
        // Build context
        val context = PhysioContextMTR(
            state = state,
            confidence = confidence,
            poorSleepDetected = flags.poorSleep,
            hrvDepressed = flags.hrvDepressed,
            rhrElevated = flags.rhrElevated,
            activityReduced = flags.activityReduced,
            sleepDeviationZ = deviations.sleepZ,
            hrvDeviationZ = deviations.hrvZ,
            rhrDeviationZ = deviations.rhrZ,
            recommendReduceBasal = recommendations.reduceBasal,
            recommendReduceSMB = recommendations.reduceSMB,
            recommendIncreaseISF = recommendations.increaseISF,
            timestamp = System.currentTimeMillis(),
            features = features
        )
        
        logContext(context)
        
        return context
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // DEVIATION CALCULATION
    // ═══════════════════════════════════════════════════════════════════════
    
    private data class Deviations(
        val sleepZ: Double,
        val hrvZ: Double,
        val rhrZ: Double,
        val stepsZ: Double
    )
    
    private fun calculateDeviations(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR
    ): Deviations {
        
        val sleepZ = if (baseline.sleepDuration.isValid() && features.sleepDurationHours > 0) {
            baseline.sleepDuration.zScore(features.sleepDurationHours)
        } else 0.0
        
        val hrvZ = if (baseline.hrvRMSSD.isValid() && features.hrvMeanRMSSD > 0) {
            baseline.hrvRMSSD.zScore(features.hrvMeanRMSSD)
        } else 0.0
        
        val rhrZ = if (baseline.morningRHR.isValid() && features.rhrMorning > 0) {
            baseline.morningRHR.zScore(features.rhrMorning.toDouble())
        } else 0.0
        
        val stepsZ = if (baseline.dailySteps.isValid() && features.stepsDailyAverage > 0) {
            baseline.dailySteps.zScore(features.stepsDailyAverage.toDouble())
        } else 0.0
        
        return Deviations(sleepZ, hrvZ, rhrZ, stepsZ)
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // ANOMALY DETECTION
    // ═══════════════════════════════════════════════════════════════════════
    
    private data class AnomalyFlags(
        val poorSleep: Boolean,
        val hrvDepressed: Boolean,
        val rhrElevated: Boolean,
        val activityReduced: Boolean,
        val anomalyCount: Int
    )
    
    private fun detectAnomalyFlags(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        deviations: Deviations
    ): AnomalyFlags {
        
        var anomalyCount = 0
        
        // Poor sleep detection
        val poorSleep = (features.sleepDurationHours > 0 && features.sleepDurationHours < POOR_SLEEP_THRESHOLD_HOURS) ||
                       (features.sleepEfficiency > 0 && features.sleepEfficiency < LOW_SLEEP_EFFICIENCY) ||
                       (features.sleepFragmentation > HIGH_FRAGMENTATION) ||
                       (deviations.sleepZ < -SIGNIFICANT_DEVIATION_Z)
        if (poorSleep) anomalyCount++
        
        // HRV depression detection
        val hrvDepressed = (features.hrvMeanRMSSD > 0 && deviations.hrvZ < -SIGNIFICANT_DEVIATION_Z) ||
                          (baseline.hrvRMSSD.isValid() && features.hrvMeanRMSSD > 0 && 
                           features.hrvMeanRMSSD < baseline.hrvRMSSD.p25)
        if (hrvDepressed) anomalyCount++
        
        // Elevated RHR detection
        val rhrElevated = (features.rhrMorning > 0 && deviations.rhrZ > SIGNIFICANT_DEVIATION_Z) ||
                         (baseline.morningRHR.isValid() && features.rhrMorning > 0 &&
                          features.rhrMorning > baseline.morningRHR.p75)
        if (rhrElevated) anomalyCount++
        
        // Reduced activity detection
        val activityReduced = features.stepsDailyAverage > 0 && deviations.stepsZ < -SIGNIFICANT_DEVIATION_Z
        if (activityReduced) anomalyCount++
        
        return AnomalyFlags(
            poorSleep = poorSleep,
            hrvDepressed = hrvDepressed,
            rhrElevated = rhrElevated,
            activityReduced = activityReduced,
            anomalyCount = anomalyCount
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STATE DETERMINATION
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun determinePhysiologicalState(
        flags: AnomalyFlags,
        deviations: Deviations
    ): PhysioStateMTR {
        
        // INFECTION_RISK: Multiple severe anomalies
        if (flags.anomalyCount >= 3 || 
            (flags.rhrElevated && flags.hrvDepressed && abs(deviations.rhrZ) > SEVERE_DEVIATION_Z)) {
            return PhysioStateMTR.INFECTION_RISK
        }
        
        // STRESS_DETECTED: Elevated RHR + Low HRV
        if (flags.rhrElevated && flags.hrvDepressed) {
            return PhysioStateMTR.STRESS_DETECTED
        }
        
        // RECOVERY_NEEDED: Poor sleep or significant HRV drop
        if (flags.poorSleep || (flags.hrvDepressed && abs(deviations.hrvZ) > SIGNIFICANT_DEVIATION_Z)) {
            return PhysioStateMTR.RECOVERY_NEEDED
        }
        
        // OPTIMAL: No significant anomalies
        if (flags.anomalyCount == 0) {
            return PhysioStateMTR.OPTIMAL
        }
        
        // Default: Minor anomaly but not classified
        return PhysioStateMTR.UNKNOWN
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // RECOMMENDATIONS
    // ═══════════════════════════════════════════════════════════════════════
    
    private data class Recommendations(
        val reduceBasal: Boolean,
        val reduceSMB: Boolean,
        val increaseISF: Boolean
    )
    
    private fun generateRecommendations(
        state: PhysioStateMTR,
        flags: AnomalyFlags,
        deviations: Deviations
    ): Recommendations {
        
        return when (state) {
            PhysioStateMTR.INFECTION_RISK -> {
                // Very protective: increase ISF (less aggressive corrections)
                Recommendations(
                    reduceBasal = true,
                    reduceSMB = true,
                    increaseISF = true
                )
            }
            
            PhysioStateMTR.STRESS_DETECTED -> {
                // Moderately protective: reduce SMB aggression
                Recommendations(
                    reduceBasal = false,
                    reduceSMB = true,
                    increaseISF = true
                )
            }
            
            PhysioStateMTR.RECOVERY_NEEDED -> {
                // Slightly protective: reduce SMB if significant
                Recommendations(
                    reduceBasal = false,
                    reduceSMB = flags.poorSleep, // Only if sleep was really bad
                    increaseISF = flags.hrvDepressed
                )
            }
            
            PhysioStateMTR.OPTIMAL -> {
                // No changes needed
                Recommendations(
                    reduceBasal = false,
                    reduceSMB = false,
                    increaseISF = false
                )
            }
            
            PhysioStateMTR.UNKNOWN -> {
                // Neutral/conservative
                Recommendations(
                    reduceBasal = false,
                    reduceSMB = false,
                    increaseISF = false
                )
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // CONFIDENCE SCORING
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Calculates confidence in the analysis (0-1)
     * Based on data quality and baseline validity
     */
    private fun calculateConfidence(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        flags: AnomalyFlags
    ): Double {
        
        var confidence = 0.0
        
        // Data quality contribution (40%)
        if (features.dataQuality >= MIN_DATA_QUALITY_FOR_CONFIDENCE) {
            confidence += 0.4 * features.dataQuality
        }
        
        // Baseline validity contribution (30%)
        if (baseline.validDaysCount >= MIN_BASELINE_VALIDITY_DAYS) {
            val baselineScore = (baseline.validDaysCount / 7.0).coerceAtMost(1.0)
            confidence += 0.3 * baselineScore
        }
        
        // Anomaly clarity contribution (30%)
        // Clear anomalies (either 0 or 2+) = high confidence
        // Ambiguous (1 anomaly) = lower confidence
        val clarityScore = when (flags.anomalyCount) {
            0 -> 1.0 // Clear optimal
            1 -> 0.5 // Ambiguous
            else -> 0.8 // Clear problem
        }
        confidence += 0.3 * clarityScore
        
        return confidence.coerceIn(0.0, 1.0)
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // LOGGING
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun logContext(context: PhysioContextMTR) {
        val emoji = when (context.state) {
            PhysioStateMTR.OPTIMAL -> "✅"
            PhysioStateMTR.RECOVERY_NEEDED -> "😴"
            PhysioStateMTR.STRESS_DETECTED -> "⚠️"
            PhysioStateMTR.INFECTION_RISK -> "🚨"
            PhysioStateMTR.UNKNOWN -> "❓"
        }
        
        val flags = buildList {
            if (context.poorSleepDetected) add("PoorSleep")
            if (context.hrvDepressed) add("LowHRV")
            if (context.rhrElevated) add("HighRHR")
            if (context.activityReduced) add("LowActivity")
        }.joinToString(", ")
        
        val recs = buildList {
            if (context.recommendReduceBasal) add("↓Basal")
            if (context.recommendReduceSMB) add("↓SMB")
            if (context.recommendIncreaseISF) add("↑ISF")
        }.joinToString(", ")
        
        aapsLogger.info(
            LTag.APS,
            "[$TAG] $emoji State: ${context.state} | " +
            "Confidence: ${(context.confidence * 100).toInt()}% | " +
            "Flags: ${if (flags.isEmpty()) "None" else flags} | " +
            "Recommendations: ${if (recs.isEmpty()) "None" else recs}"
        )
        
        if (context.confidence < 0.5) {
            aapsLogger.warn(
                LTag.APS,
                "[$TAG] ⚠️ Low confidence (${(context.confidence * 100).toInt()}%) - " +
                "recommendations may not be reliable"
            )
        }
    }
}
