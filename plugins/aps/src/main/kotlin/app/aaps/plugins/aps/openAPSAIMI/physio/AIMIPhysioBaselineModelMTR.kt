package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
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
@Singleton
class AIMIPhysioBaselineModelMTR internal constructor(
    private val aapsLogger: AAPSLogger,
    /**
     * The clock, so a test can decide what "now" and so what "a night" means.
     *
     * Production uses the wall clock. Without this a test loop writes several entries into the
     * same millisecond and they collapse into one.
     */
    private val now: () -> Long,
    /**
     * The time zone a night is decided in. Injected for the same reason as the clock: a night is a
     * local idea, and a test must fix its own zone, not use the zone of the machine.
     */
    private val zone: () -> ZoneId,
) {

    @Inject
    constructor(aapsLogger: AAPSLogger) : this(aapsLogger, System::currentTimeMillis, ZoneId::systemDefault)
    
    companion object {
        private const val TAG = "PhysioBaseline"
        private const val WINDOW_DAYS = 7
        private const val MAX_SAMPLES = WINDOW_DAYS * 4 // 4 samples per day max
        private const val MIN_SAMPLES_FOR_VALID_BASELINE = 3

        /** A night starts at this local hour, the evening before the date it is named after. */
        private const val NIGHT_START_HOUR = 22

        /** And ends at this local hour on that date. */
        private const val NIGHT_END_HOUR = 6

        /** How much wall time a night's readings must cover before the night counts. */
        private const val MIN_NIGHT_COVERAGE_MS = 2L * 60 * 60 * 1000
    }

    /** One lock for the update, the restore and the reset, so a night map is never half built. */
    private val historyLock = Any()

    /**
     * The median HRV of each closed night, keyed by the date of the morning the night ends on.
     * Used instead of [hrvRMSSDHistory] when the caller asks for the per-night baseline.
     *
     * The per-run map cannot describe a person's normal: it is capped at [MAX_SAMPLES] = 28 entries
     * and the pipeline writes one on every run, every 15 minutes. Twenty-eight runs is **seven
     * hours**, not seven days, and within seven hours the same HRV reading repeats - so the spread
     * collapses and a reading just below normal reads as a large deviation.
     */
    private val hrvByNight = ConcurrentHashMap<LocalDate, Double>()

    /** Whether the last [updateBaseline] asked for nights. Decides which HRV history is read. */
    @Volatile
    private var hrvPerNight: Boolean = false
    
    // Rolling history for each metric (timestamp -> value)
    private val sleepDurationHistory = ConcurrentHashMap<Long, Double>()
    private val hrvRMSSDHistory = ConcurrentHashMap<Long, Double>()
    private val morningRHRHistory = ConcurrentHashMap<Long, Int>()
    private val dailyStepsHistory = ConcurrentHashMap<Long, Int>()
    
    // Cached baseline (recalculated on update)
    @Volatile
    private var cachedBaseline: PhysioBaselineMTR = PhysioBaselineMTR.EMPTY
    
    /**
     * Updates baseline with new features
     * 
     * @param features New physiological features
     * @param hrvSamples the raw HRV readings this run fetched. Only read when [hrvPerNightBaseline]
     *   is on: the nights are rebuilt from these samples, not from the run.
     * @param hrvPerNightBaseline `true` builds the HRV baseline from one median per closed night.
     *   `false` keeps the old one-point-per-run history. See `BooleanKey.AimiHrvBaselinePerNight`.
     * @return Updated PhysioBaselineMTR
     */
    fun updateBaseline(
        features: PhysioFeaturesMTR,
        hrvSamples: List<HRVDataMTR> = emptyList(),
        hrvPerNightBaseline: Boolean = false,
    ): PhysioBaselineMTR {
        if (!features.hasValidData) {
            aapsLogger.debug(LTag.APS, "[$TAG] No valid features - skipping baseline update")
            return cachedBaseline
        }

        synchronized(historyLock) {
            val now = now()
            hrvPerNight = hrvPerNightBaseline

            // Add new data points
            if (features.sleepDurationHours > 0) {
                sleepDurationHistory[now] = features.sleepDurationHours
            }

            if (hrvPerNightBaseline) {
                // Rebuilt from the samples, not added per run. The daily pass fetches seven days,
                // so it rebuilds the whole window from source, and the result does not depend on how
                // many times the pipeline ran. Merged, not replaced, because the 15-minute pass
                // fetches only one day and must not drop the older nights.
                hrvByNight.putAll(closedNightMedians(hrvSamples, now))
            } else if (features.hrvMeanRMSSD > 0) {
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
        }

        logBaseline(cachedBaseline)
        
        return cachedBaseline
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
        synchronized(historyLock) {
            cachedBaseline = baseline
            // 🔄 RESTORE HISTORY MAPS (Critical Fix)
            sleepDurationHistory.putAll(baseline.sleepHistory)
            hrvRMSSDHistory.putAll(baseline.hrvHistory)
            morningRHRHistory.putAll(baseline.rhrHistory)
            dailyStepsHistory.putAll(baseline.stepsHistory)
        }
        aapsLogger.info(
            LTag.APS,
            "[$TAG] Baseline restored (${baseline.validDaysCount} days, " +
            "age=${(now() - baseline.lastUpdateTimestamp) / (60 * 60 * 1000)}h)"
        )
        aapsLogger.info(LTag.APS, "[$TAG] ✅ History restored: Sleep=${sleepDurationHistory.size}, HRV=${hrvRMSSDHistory.size}, RHR=${morningRHRHistory.size}, Steps=${dailyStepsHistory.size}")
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // BASELINE CALCULATION
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun calculateBaseline(timestamp: Long): PhysioBaselineMTR {
        val sleepBaseline = calculateMetricBaseline(
            "sleepDuration",
            sleepDurationHistory.values.toList()
        )

        // One value per night when the nights are in use, so `sampleCount` really counts nights and
        // the "at least 3" of `isValid` means three nights, not three runs.
        val hrvValues =
            if (hrvPerNight) hrvByNight.entries.sortedBy { it.key }.map { it.value }
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

        // The nights count as days too. Without this, someone whose only signal is HRV would have a
        // valid HRV baseline and a validDaysCount of zero.
        //
        // They are MERGED, not added. Adding the night count to an already unique set of days
        // counts a day twice when it has both a night and another reading: two real days then
        // report four, `isValid()` passes its "at least three days" test on two, and the confidence
        // score reads 4/7 instead of 2/7.
        //
        // Both sides use one date frame: the local date in the injected zone, the same zone
        // `nightOf` uses. A night is named by the date it ends on.
        val uniqueDays = if (hrvPerNight) {
            val tz = zone()
            val historyDays = allTimestamps.map { Instant.ofEpochMilli(it).atZone(tz).toLocalDate() }
            (historyDays + hrvByNight.keys).toSet().size
        } else {
            // Unchanged on purpose. With the switch off this must stay exactly what it was, UTC day
            // boundary included. Moving it to a local day is a change for everyone, and belongs in
            // its own piece of work.
            allTimestamps
                .map { it / (24 * 60 * 60 * 1000) } // Convert to days
                .toSet()
                .size
        }
        
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
        
        sleepDurationHistory.keys.removeIf { it < cutoff }
        hrvRMSSDHistory.keys.removeIf { it < cutoff }
        morningRHRHistory.keys.removeIf { it < cutoff }
        dailyStepsHistory.keys.removeIf { it < cutoff }
        
        // Also limit max samples per metric
        limitSamples(sleepDurationHistory, MAX_SAMPLES)
        limitSamples(hrvRMSSDHistory, MAX_SAMPLES)
        // Nights are pruned by date, not by run. The oldest go first, and at most WINDOW_DAYS stay.
        // This is what makes the HRV window seven days and not seven hours.
        if (hrvByNight.size > WINDOW_DAYS) {
            hrvByNight.keys.sorted().take(hrvByNight.size - WINDOW_DAYS).forEach { hrvByNight.remove(it) }
        }
        limitSamples(morningRHRHistory, MAX_SAMPLES)
        limitSamples(dailyStepsHistory, MAX_SAMPLES)
    }

    /**
     * The median HRV of every **closed** night in [samples], keyed by the night's morning date.
     *
     * A night runs from [NIGHT_START_HOUR] to [NIGHT_END_HOUR] local time and is named after the
     * morning it ends on, so a reading at 23:30 and one at 02:00 belong to the same night. Daytime
     * readings are dropped: they say nothing about a resting normal, and a reading taken during
     * effort would pull the night down and later read as stress.
     *
     * A night only counts once it is **closed** - its window has passed - so the reading being
     * judged is never part of the baseline it is judged against.
     *
     * It also needs [MIN_NIGHT_COVERAGE_MS] between its first and last reading. Coverage, not a
     * count: two readings taken minutes apart are one moment, not a night.
     *
     * All of it is done in local date and time, never by adding milliseconds, so the short night in
     * spring and the long one in autumn are each still one night.
     */
    private fun closedNightMedians(samples: List<HRVDataMTR>, nowMs: Long): Map<LocalDate, Double> {
        if (samples.isEmpty()) return emptyMap()
        val tz = zone()
        val nowLocal = Instant.ofEpochMilli(nowMs).atZone(tz).toLocalDateTime()

        return samples
            .filter { it.hasValidData() }
            .groupBy { nightOf(it.timestamp, tz) }
            .mapNotNull { (night, nightSamples) ->
                if (night == null) return@mapNotNull null
                if (!isClosed(night, nowLocal)) return@mapNotNull null
                val times = nightSamples.map { it.timestamp }
                if (times.max() - times.min() < MIN_NIGHT_COVERAGE_MS) return@mapNotNull null
                night to medianOf(nightSamples.map { it.rmssd })
            }
            .toMap()
    }

    /** The night a reading belongs to, named after its morning, or null when it is daytime. */
    private fun nightOf(timestampMs: Long, tz: ZoneId): LocalDate? {
        val local = Instant.ofEpochMilli(timestampMs).atZone(tz).toLocalDateTime()
        return when {
            local.hour >= NIGHT_START_HOUR -> local.toLocalDate().plusDays(1)
            local.hour < NIGHT_END_HOUR    -> local.toLocalDate()
            else                           -> null
        }
    }

    /** A night is closed once its morning end hour has passed. */
    private fun isClosed(night: LocalDate, nowLocal: LocalDateTime): Boolean =
        nowLocal.toLocalDate() > night || (nowLocal.toLocalDate() == night && nowLocal.hour >= NIGHT_END_HOUR)

    /**
     * The middle reading of a night.
     *
     * Median, not mean, because a night holds many readings and one odd value - a bad contact, a
     * reading taken while moving - must not move the night.
     */
    private fun medianOf(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
    
    private fun <T> limitSamples(history: ConcurrentHashMap<Long, T>, maxSamples: Int) {
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
        synchronized(historyLock) {
            sleepDurationHistory.clear()
            hrvRMSSDHistory.clear()
            hrvByNight.clear()
            morningRHRHistory.clear()
            dailyStepsHistory.clear()
            cachedBaseline = PhysioBaselineMTR.EMPTY
        }
        aapsLogger.info(LTag.APS, "[$TAG] Baseline history cleared")
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
            aapsLogger.debug(LTag.APS, "[$TAG] Baseline not yet valid (need ${MIN_SAMPLES_FOR_VALID_BASELINE}+ samples)")
            return
        }
        
        aapsLogger.info(
            LTag.APS,
            "[$TAG] ✅ Baseline updated | " +
            "Sleep: ${baseline.sleepDuration.p50.format(1)}h " +
            "(P25=${baseline.sleepDuration.p25.format(1)}, P75=${baseline.sleepDuration.p75.format(1)}), " +
            "HRV: ${baseline.hrvRMSSD.p50.format(1)}ms, " +
            "RHR: ${baseline.morningRHR.p50.toInt()} bpm, " +
            "${baseline.validDaysCount} days"
        )
    }
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
