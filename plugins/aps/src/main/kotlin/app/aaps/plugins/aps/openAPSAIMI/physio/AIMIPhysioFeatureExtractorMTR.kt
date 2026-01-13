package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 🧮 AIMI Physiological Feature Extractor - MTR Implementation
 * 
 * Extracts normalized features from raw physiological data.
 * Handles missing data gracefully with safe defaults.
 * 
 * Feature Categories:
 * 1. Sleep: Duration, efficiency, fragmentation, stage distribution
 * 2. HRV: Mean RMSSD, trend, variability
 * 3. RHR: Morning baseline, deviation
 * 4. Activity: Daily steps, trend
 * 
 * All outputs are normalized for ML/deterministic analysis.
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMIPhysioFeatureExtractorMTR @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "PhysioFeatureExtractor"
        
        // Normalization constants
        private const val NORMAL_SLEEP_HOURS = 7.5
        private const val NORMAL_HRV_RMSSD = 40.0 // ms
        private const val NORMAL_RHR = 60 // bpm
        private const val NORMAL_DAILY_STEPS = 8000
    }
    
    /**
     * Extracts features from raw physiological data
     * 
     * @param rawData Raw data from repository
     * @param previousFeatures Previous features for trend calculation (optional)
     * @return PhysioFeaturesMTR with normalized features
     */
    fun extractFeatures(
        rawData: RawPhysioDataMTR,
        previousFeatures: PhysioFeaturesMTR? = null
    ): PhysioFeaturesMTR {
        
        if (!rawData.hasAnyData()) {
            aapsLogger.debug(LTag.APS, "[$TAG] No data available - returning empty features")
            return PhysioFeaturesMTR.EMPTY
        }
        
        // Extract sleep features
        val sleepFeatures = extractSleepFeatures(rawData.sleep)
        
        // Extract HRV features
        val hrvFeatures = extractHRVFeatures(rawData.hrv, previousFeatures)
        
        // Extract RHR features
        val rhrFeatures = extractRHRFeatures(rawData.rhr)
        
        // Extract activity features
        val activityFeatures = extractActivityFeatures(rawData.steps, previousFeatures)
        
        // Calculate data quality score
        val dataQuality = calculateDataQuality(rawData)
        
        val features = PhysioFeaturesMTR(
            // Sleep
            sleepDurationHours = sleepFeatures.duration,
            sleepEfficiency = sleepFeatures.efficiency,
            sleepFragmentation = sleepFeatures.fragmentation,
            deepSleepPercent = sleepFeatures.deepPercent,
            
            // HRV
            hrvMeanRMSSD = hrvFeatures.meanRMSSD,
            hrvTrend = hrvFeatures.trend,
            hrvVariability = hrvFeatures.variability,
            
            // RHR
            rhrMorning = rhrFeatures.morningBPM,
            rhrDeviation = rhrFeatures.deviation,
            
            // Activity
            stepsDailyAverage = activityFeatures.dailyAverage,
            stepsTrend = activityFeatures.trend,
            
            // Metadata
            timestamp = System.currentTimeMillis(),
            dataQuality = dataQuality,
            hasValidData = dataQuality > 0.3
        )
        
        logFeatures(features)
        
        return features
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SLEEP FEATURE EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════
    
    private data class SleepFeatures(
        val duration: Double,
        val efficiency: Double,
        val fragmentation: Double,
        val deepPercent: Double
    )
    
    private fun extractSleepFeatures(sleep: SleepDataMTR?): SleepFeatures {
        if (sleep == null || !sleep.hasValidData()) {
            return SleepFeatures(0.0, 0.0, 0.0, 0.0)
        }
        
        val totalSleepMinutes = sleep.deepSleepMinutes + sleep.remSleepMinutes + sleep.lightSleepMinutes
        val deepPercent = if (totalSleepMinutes > 0) {
            sleep.deepSleepMinutes.toDouble() / totalSleepMinutes
        } else 0.0
        
        return SleepFeatures(
            duration = sleep.durationHours,
            efficiency = sleep.efficiency,
            fragmentation = sleep.fragmentationScore.coerceIn(0.0, 1.0),
            deepPercent = deepPercent
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // HRV FEATURE EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════
    
    private data class HRVFeatures(
        val meanRMSSD: Double,
        val trend: Double,
        val variability: Double
    )
    
    private fun extractHRVFeatures(
        hrvList: List<HRVDataMTR>,
        previousFeatures: PhysioFeaturesMTR?
    ): HRVFeatures {
        if (hrvList.isEmpty()) {
            return HRVFeatures(0.0, 0.0, 0.0)
        }
        
        val validHRV = hrvList.filter { it.hasValidData() }
        if (validHRV.isEmpty()) {
            return HRVFeatures(0.0, 0.0, 0.0)
        }
        
        // Mean RMSSD
        val meanRMSSD = validHRV.map { it.rmssd }.average()
        
        // Trend calculation (compare to previous mean)
        val trend = if (previousFeatures != null && previousFeatures.hrvMeanRMSSD > 0) {
            val change = (meanRMSSD - previousFeatures.hrvMeanRMSSD) / previousFeatures.hrvMeanRMSSD
            change.coerceIn(-1.0, 1.0) // Normalize to [-1, 1]
        } else 0.0
        
        // Variability (coefficient of variation)
        val variability = if (validHRV.size > 1) {
            val stdDev = calculateStdDev(validHRV.map { it.rmssd })
            if (meanRMSSD > 0) (stdDev / meanRMSSD).coerceIn(0.0, 1.0) else 0.0
        } else 0.0
        
        return HRVFeatures(
            meanRMSSD = meanRMSSD,
            trend = trend,
            variability = variability
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // RHR FEATURE EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════
    
    private data class RHRFeatures(
        val morningBPM: Int,
        val deviation: Double
    )
    
    private fun extractRHRFeatures(rhrList: List<RHRDataMTR>): RHRFeatures {
        if (rhrList.isEmpty()) {
            return RHRFeatures(0, 0.0)
        }
        
        val validRHR = rhrList.filter { it.hasValidData() }
        if (validRHR.isEmpty()) {
            return RHRFeatures(0, 0.0)
        }
        
        // Most recent morning RHR
        val morningBPM = validRHR.maxByOrNull { it.timestamp }?.bpm ?: 0
        
        // Deviation from expected normal (Z-score approximation)
        val mean = validRHR.map { it.bpm }.average()
        val stdDev = if (validRHR.size > 1) {
            calculateStdDev(validRHR.map { it.bpm.toDouble() })
        } else 5.0 // Default stdDev
        
        val deviation = if (stdDev > 0) {
            (morningBPM - mean) / stdDev
        } else 0.0
        
        return RHRFeatures(
            morningBPM = morningBPM,
            deviation = deviation
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // ACTIVITY FEATURE EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════
    
    private data class ActivityFeatures(
        val dailyAverage: Int,
        val trend: Double
    )
    
    private fun extractActivityFeatures(
        currentSteps: Int,
        previousFeatures: PhysioFeaturesMTR?
    ): ActivityFeatures {
        // Steps are already aggregated by StepsManager
        // Here we just track trend
        
        val trend = if (previousFeatures != null && previousFeatures.stepsDailyAverage > 0) {
            val change = (currentSteps - previousFeatures.stepsDailyAverage).toDouble() / previousFeatures.stepsDailyAverage
            change.coerceIn(-1.0, 1.0)
        } else 0.0
        
        return ActivityFeatures(
            dailyAverage = currentSteps,
            trend = trend
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // DATA QUALITY ASSESSMENT
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Calculates overall data quality score (0-1)
     * Based on completeness and validity of data sources
     */
    private fun calculateDataQuality(rawData: RawPhysioDataMTR): Double {
        var score = 0.0
        var maxScore = 0.0
        
        // Sleep data (40% weight)
        maxScore += 0.4
        if (rawData.sleep?.hasValidData() == true) {
            score += 0.4
        }
        
        // HRV data (30% weight)
        maxScore += 0.3
        if (rawData.hrv.any { it.hasValidData() }) {
            val completeness = (rawData.hrv.size / 7.0).coerceAtMost(1.0) // Ideally 7 days
            score += 0.3 * completeness
        }
        
        // RHR data (20% weight)
        maxScore += 0.2
        if (rawData.rhr.any { it.hasValidData() }) {
            val completeness = (rawData.rhr.size / 7.0).coerceAtMost(1.0)
            score += 0.2 * completeness
        }
        
        // Steps data (10% weight)
        maxScore += 0.1
        if (rawData.steps > 0) {
            score += 0.1
        }
        
        return if (maxScore > 0) (score / maxScore).coerceIn(0.0, 1.0) else 0.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }
    
    private fun logFeatures(features: PhysioFeaturesMTR) {
        if (!features.hasValidData) {
            aapsLogger.debug(LTag.APS, "[$TAG] No valid features extracted")
            return
        }
        
        aapsLogger.info(
            LTag.APS,
            "[$TAG] ✅ Features extracted | " +
            "Sleep: ${features.sleepDurationHours.format(1)}h (eff=${(features.sleepEfficiency * 100).toInt()}%), " +
            "HRV: ${features.hrvMeanRMSSD.format(1)}ms, " +
            "RHR: ${features.rhrMorning} bpm, " +
            "Quality: ${(features.dataQuality * 100).toInt()}%"
        )
    }
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
