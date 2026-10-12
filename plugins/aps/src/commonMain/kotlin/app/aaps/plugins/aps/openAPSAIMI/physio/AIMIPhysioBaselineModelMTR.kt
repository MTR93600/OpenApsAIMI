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
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime

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
    private val aapsLogger: AAPSLogger,
    /**
     * The clock, so a test can decide what "now" and therefore what "a day" means.
     *
     * Production never passes it. Without a seam here this class cannot be tested at all in the
     * one dimension that defines it: every entry is stamped with the wall clock, so a test loop
     * writes several entries into the same millisecond and they collapse into one.
     */
    private val now: () -> Long = ::aimiWallClockMs,
) {
    
    companion object {
        private const val TAG = "PhysioBaseline"
        private const val WINDOW_DAYS = 7
        private const val MAX_SAMPLES = WINDOW_DAYS * 4 // 4 samples per day max
        private const val MIN_SAMPLES_FOR_VALID_BASELINE = 3
    }
    
    // One lock for every history map (`synchronized` is JVM only).
    private val historyLock = AapsLock()

    /** Which HRV metric the HRV history currently holds. See the guard in [updateBaseline]. */
    private var lastHrvMetric: HrvMetric = HrvMetric.RMSSD

    /**
     * HRV readings bucketed by local day, used instead of [hrvRMSSDHistory] when the caller asks
     * for the per-day baseline. One bucket per day, and the day contributes its median.
     *
     * The per-run map it replaces cannot describe a person's normal: it is capped at
     * [MAX_SAMPLES] = 28 entries and the pipeline writes one on every run, every 15 minutes on
     * both platforms. Twenty-eight runs is **seven hours**, not seven days, and within seven hours
     * the same HRV reading repeats - so the spread collapses and a reading barely below normal
     * divides out as a large one. A day is the unit this measurement was always meant to use.
     */
    private val hrvByDay = mutableMapOf<Long, MutableList<Double>>()

    /** Whether the last [updateBaseline] asked for day buckets. Decides which history is read. */
    private var hrvPerDay: Boolean = false

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
    fun updateBaseline(
        features: PhysioFeaturesMTR,
        hrvMetric: HrvMetric = HrvMetric.RMSSD,
        hrvPerDayBaseline: Boolean = false,
    ): PhysioBaselineMTR {
        if (!features.hasValidData) {
            aapsLogger.debug(LTag.AIMI, "[$TAG] No valid features - skipping baseline update")
            return cachedBaseline
        }

        return historyLock.withLock {
            val now = now()

            // Add new data points
            if (features.sleepDurationHours > 0) {
                sleepDurationHistory[now] = features.sleepDurationHours
            }

            // One person's HRV history must never hold both metrics. RMSSD and SDNN live in
            // different ranges, so a mixed history would give a mean and a spread that describe
            // neither, and every person-relative reading taken from it would be wrong. This is
            // not hypothetical: someone moving from an Android phone to an iPhone keeps their
            // AAPS data and starts receiving SDNN where RMSSD used to arrive.
            //
            // So the history is dropped when the metric changes. The cost is a cold start - a few
            // days with no HRV contribution, which the dose path treats as neutral - and that is
            // much cheaper than silently reading the new numbers against the old distribution.
            hrvPerDay = hrvPerDayBaseline

            if (hrvMetric != lastHrvMetric) {
                if (hrvRMSSDHistory.isNotEmpty() || hrvByDay.isNotEmpty()) {
                    aapsLogger.info(
                        LTag.AIMI,
                        "[$TAG] HRV metric changed $lastHrvMetric -> $hrvMetric, " +
                            "dropping ${hrvRMSSDHistory.size} samples of the old one"
                    )
                }
                hrvRMSSDHistory.clear()
                hrvByDay.clear()
                lastHrvMetric = hrvMetric
            }

            if (features.hrvMeanRMSSD > 0) {
                if (hrvPerDayBaseline) {
                    hrvByDay.getOrPut(localDayStartMs(now)) { mutableListOf() }.add(features.hrvMeanRMSSD)
                } else {
                    hrvRMSSDHistory[now] = features.hrvMeanRMSSD
                }
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
        val ageHours = (now() - baseline.lastUpdateTimestamp) / (60 * 60 * 1000)
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
        
        // One value per day when the day buckets are in use, so `sampleCount` really is a count
        // of days and `isValid`'s "at least 3" means three days rather than three runs.
        val hrvValues =
            if (hrvPerDay) hrvByDay.toList().sortedBy { it.first }.map { medianOf(it.second) }
            else hrvRMSSDHistory.values.toList()
        val hrvBaseline = calculateMetricBaseline("hrvRMSSD", hrvValues)
        
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
        // Day buckets are pruned by day, not by run: at most one per day, at most WINDOW_DAYS of
        // them. This is what makes the window seven days rather than seven hours.
        hrvByDay.keys.removeAll { it < cutoff }
        limitSamples(hrvByDay, WINDOW_DAYS)
        limitSamples(morningRHRHistory, MAX_SAMPLES)
        limitSamples(dailyStepsHistory, MAX_SAMPLES)
    }
    
    /** Midnight of [timestampMs] in the phone's own time zone, which is where a "day" is decided. */
    private fun localDayStartMs(timestampMs: Long): Long {
        val zone = TimeZone.currentSystemDefault()
        return Instant.fromEpochMilliseconds(timestampMs)
            .toLocalDateTime(zone).date
            .atStartOfDayIn(zone)
            .toEpochMilliseconds()
    }

    /**
     * The middle reading of a day.
     *
     * Median rather than mean, because a day holds many readings taken minutes apart and one odd
     * value - a bad contact, a reading taken mid-effort - should not move the day.
     */
    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
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
            hrvByDay.clear()
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
