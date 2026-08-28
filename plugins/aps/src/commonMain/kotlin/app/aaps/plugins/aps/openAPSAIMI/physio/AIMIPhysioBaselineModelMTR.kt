package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/**
 * 📊 AIMI Physiological Baseline Model - MTR Implementation
 * 
 * Maintains 7-day rolling baseline for all physiological metrics.
 * Calculates percentiles, Z-scores, and detects significant deviations.
 * 
 * Storage:
 * - In-memory: Thread-safe concurrent maps
 * - Persistent: JSON file (handled by ContextStore)
 * 
 * Update frequency: Every 6 hours
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@SingleIn(AppScope::class)
class AIMIPhysioBaselineModelMTR @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "PhysioBaseline"
        private const val WINDOW_DAYS = 7
        private const val MAX_SAMPLES = WINDOW_DAYS * 4 // 4 samples per day max
        private const val MIN_SAMPLES_FOR_VALID_BASELINE = 3
    }
    
    // One lock for every history map (`synchronized` is JVM only).
    private val historyLock = AapsLock()

    // Rolling history for each metric (timestamp -> value)
    private val sleepDurationHistory = mutableMapOf<Long, Double>()
    private val hrvRMSSDHistory = mutableMapOf<Long, Double>()
    private val morningRHRHistory = mutableMapOf<Long, Int>()
    private val dailyStepsHistory = mutableMapOf<Long, Int>()
    
    // Cached baseline (recalculated on update)
    @Volatile
    private var cachedBaseline: PhysioBaselineMTR = PhysioBaselineMTR.EMPTY
    
    /**
     * Updates baseline with new features
     * 
     * @param features New physiological features
     * @return Updated PhysioBaselineMTR
     */
    fun updateBaseline(features: PhysioFeaturesMTR): PhysioBaselineMTR {
        if (!features.hasValidData) {
            aapsLogger.debug(LTag.AIMI, "[$TAG] No valid features - skipping baseline update")
            return cachedBaseline
        }

        return historyLock.withLock {
            val now = aimiWallClockMs()

            // Add new data points
            if (features.sleepDurationHours > 0) {
                sleepDurationHistory[now] = features.sleepDurationHours
            }

            if (features.hrvMeanRMSSD > 0) {
                hrvRMSSDHistory[now] = features.hrvMeanRMSSD
            }

            if (features.rhrMorning > 0) {
                morningRHRHistory[now] = features.rhrMorning
            }

            if (features.stepsDailyAverage > 0) {
                dailyStepsHistory[now] = features.stepsDailyAverage
            }

            // Clean old data (> 7 days)
            cleanOldData(now)

            // Recalculate baseline
            cachedBaseline = calculateBaseline(now)

            logBaseline(cachedBaseline)

            cachedBaseline
        }
    }
    
    /**
     * Gets current baseline without updating
     * 
     * @return Current PhysioBaselineMTR
     */
    fun getCurrentBaseline(): PhysioBaselineMTR = cachedBaseline
    
    /**
     * Loads baseline from persisted data
     * 
     * @param baseline Previously saved baseline
     */
    fun restoreBaseline(baseline: PhysioBaselineMTR) {
        val ageHours = (aimiWallClockMs() - baseline.lastUpdateTimestamp) / (60 * 60 * 1000)
        var sleepSize = 0
        var hrvSize = 0
        var rhrSize = 0
        var stepsSize = 0
        historyLock.withLock {
            cachedBaseline = baseline
            sleepDurationHistory.putAll(baseline.sleepHistory)
            hrvRMSSDHistory.putAll(baseline.hrvHistory)
            morningRHRHistory.putAll(baseline.rhrHistory)
            dailyStepsHistory.putAll(baseline.stepsHistory)
            sleepSize = sleepDurationHistory.size
            hrvSize = hrvRMSSDHistory.size
            rhrSize = morningRHRHistory.size
            stepsSize = dailyStepsHistory.size
        }
        aapsLogger.info(
            LTag.AIMI,
            "[$TAG] Baseline restored (${baseline.validDaysCount} days, age=${ageHours}h)"
        )
        aapsLogger.info(
            LTag.AIMI,
            "[$TAG] History restored: Sleep=$sleepSize, HRV=$hrvSize, RHR=$rhrSize, Steps=$stepsSize"
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // BASELINE CALCULATION
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun calculateBaseline(timestamp: Long): PhysioBaselineMTR {
        val sleepBaseline = calculateMetricBaseline(
            "sleepDuration",
            sleepDurationHistory.values.toList()
        )
        
        val hrvBaseline = calculateMetricBaseline(
            "hrvRMSSD",
            hrvRMSSDHistory.values.toList()
        )
        
        val rhrBaseline = calculateMetricBaseline(
            "morningRHR",
            morningRHRHistory.values.map { it.toDouble() }
        )
        
        val stepsBaseline = calculateMetricBaseline(
            "dailySteps",
            dailyStepsHistory.values.map { it.toDouble() }
        )
        
        // Count valid days (unique dates with data)
        val allTimestamps = sleepDurationHistory.keys + 
                           hrvRMSSDHistory.keys + 
                           morningRHRHistory.keys + 
                           dailyStepsHistory.keys
        
        val uniqueDays = allTimestamps
            .map { it / (24 * 60 * 60 * 1000) } // Convert to days
            .toSet()
            .size
        
        return PhysioBaselineMTR(
            sleepDuration = sleepBaseline,
            hrvRMSSD = hrvBaseline,
            morningRHR = rhrBaseline,
            dailySteps = stepsBaseline,
            lastUpdateTimestamp = timestamp,
            validDaysCount = uniqueDays,
            
            // Export History
            sleepHistory = sleepDurationHistory.toMap(),
            hrvHistory = hrvRMSSDHistory.toMap(),
            rhrHistory = morningRHRHistory.toMap(),
            stepsHistory = dailyStepsHistory.toMap()
        )
    }
    
    /**
     * Calculates baseline statistics for a single metric
     */
    private fun calculateMetricBaseline(name: String, values: List<Double>): MetricBaselineMTR {
        if (values.size < MIN_SAMPLES_FOR_VALID_BASELINE) {
            return MetricBaselineMTR(name)
        }
        
        val sorted = values.sorted()
        val mean = values.average()
        val stdDev = calculateStdDev(values, mean)
        
        // Calculate percentiles
        val p25 = percentile(sorted, 0.25)
        val p50 = percentile(sorted, 0.50) // Median
        val p75 = percentile(sorted, 0.75)
        
        return MetricBaselineMTR(
            metricName = name,
            p25 = p25,
            p50 = p50,
            p75 = p75,
            mean = mean,
            stdDev = stdDev,
            sampleCount = values.size
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // DATA MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Removes data older than 7 days
     */
    private fun cleanOldData(now: Long) {
        val cutoff = now - (WINDOW_DAYS * 24 * 60 * 60 * 1000L)
        
        sleepDurationHistory.keys.removeAll { it < cutoff }
        hrvRMSSDHistory.keys.removeAll { it < cutoff }
        morningRHRHistory.keys.removeAll { it < cutoff }
        dailyStepsHistory.keys.removeAll { it < cutoff }
        
        // Also limit max samples per metric
        limitSamples(sleepDurationHistory, MAX_SAMPLES)
        limitSamples(hrvRMSSDHistory, MAX_SAMPLES)
        limitSamples(morningRHRHistory, MAX_SAMPLES)
        limitSamples(dailyStepsHistory, MAX_SAMPLES)
    }
    
    private fun <T> limitSamples(history: MutableMap<Long, T>, maxSamples: Int) {
        if (history.size > maxSamples) {
            val toRemove = history.size - maxSamples
            history.keys
                .sorted() // Oldest first
                .take(toRemove)
                .forEach { history.remove(it) }
        }
    }
    
    /**
     * Clears all history (for testing or reset)
     */
    fun clearHistory() {
        historyLock.withLock {
            sleepDurationHistory.clear()
            hrvRMSSDHistory.clear()
            morningRHRHistory.clear()
            dailyStepsHistory.clear()
            cachedBaseline = PhysioBaselineMTR.EMPTY
        }
        aapsLogger.info(LTag.AIMI, "[$TAG] Baseline history cleared")
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STATISTICS UTILITIES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        
        val index = (sorted.size - 1) * p
        val lower = index.toInt()
        val upper = lower + 1
        
        return if (upper >= sorted.size) {
            sorted[lower]
        } else {
            val weight = index - lower
            sorted[lower] * (1 - weight) + sorted[upper] * weight
        }
    }
    
    private fun calculateStdDev(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // LOGGING
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun logBaseline(baseline: PhysioBaselineMTR) {
        if (!baseline.isValid()) {
            aapsLogger.debug(LTag.AIMI, "[$TAG] Baseline not yet valid (need ${MIN_SAMPLES_FOR_VALID_BASELINE}+ samples)")
            return
        }
        
        aapsLogger.info(
            LTag.AIMI,
            "[$TAG] ✅ Baseline updated | " +
            "Sleep: ${aimiFmt1(baseline.sleepDuration.p50)}h " +
            "(P25=${aimiFmt1(baseline.sleepDuration.p25)}, P75=${aimiFmt1(baseline.sleepDuration.p75)}), " +
            "HRV: ${aimiFmt1(baseline.hrvRMSSD.p50)}ms, " +
            "RHR: ${baseline.morningRHR.p50.toInt()} bpm, " +
            "${baseline.validDaysCount} days"
        )
    }
}
