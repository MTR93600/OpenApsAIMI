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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
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
    /**
     * The time zone a night is decided in. Injected for the same reason as the clock: a night is a
     * local idea, and the iOS CI runs under `TZ=Europe/Prague` while a test needs to fix its own.
     */
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    
    companion object {
        private const val TAG = "PhysioBaseline"
        private const val WINDOW_DAYS = 7
        private const val MAX_SAMPLES = WINDOW_DAYS * 4 // 4 samples per day max
        private const val MIN_SAMPLES_FOR_VALID_BASELINE = 3

        /** A night starts at this local hour the evening before the date it is named after. */
        private const val NIGHT_START_HOUR = 22

        /** And ends at this local hour on that date. */
        private const val NIGHT_END_HOUR = 6

        /** How much wall time a night's readings must span before it counts. */
        private const val MIN_NIGHT_COVERAGE_MS = 2L * 60 * 60 * 1000
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
    private val hrvByNight = mutableMapOf<LocalDate, Double>()

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
        hrvSamples: List<HRVDataMTR> = emptyList(),
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
                if (hrvRMSSDHistory.isNotEmpty() || hrvByNight.isNotEmpty()) {
                    aapsLogger.info(
                        LTag.AIMI,
                        "[$TAG] HRV metric changed $lastHrvMetric -> $hrvMetric, " +
                            "dropping ${hrvRMSSDHistory.size} samples of the old one"
                    )
                }
                hrvRMSSDHistory.clear()
                hrvByNight.clear()
                lastHrvMetric = hrvMetric
            }

            if (hrvPerDayBaseline) {
                // Rebuilt from the samples, not appended to. The daily pass fetches seven days, so
                // this reconstructs the whole window from source and the result does not depend on
                // how many times the pipeline happened to run. Merged rather than replaced, because
                // the 15-minute pass only fetches one day and must not drop the older nights.
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
            // A date this version cannot parse is dropped rather than failing the whole restore:
            // one bad entry must not cost the user every other night.
            baseline.hrvNights.forEach { (iso, value) ->
                runCatching { LocalDate.parse(iso) }.getOrNull()?.let { hrvByNight[it] = value }
            }
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
            if (hrvPerDay) hrvByNight.toList().sortedBy { it.first }.map { it.second }
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
        
        // The nights count as days too. Without this someone whose only physiological signal is HRV
        // would have a valid HRV baseline and a validDaysCount of zero, and the model would
        // disagree with itself about whether it knows this person.
        //
        // They have to be MERGED, not added. This used to add the night count to an already
        // deduplicated set, so a day that had both a night and any other reading counted twice.
        // Two real days then reported four, `isValid()` passed its "at least three days" test on
        // two, and the confidence score read 4/7 instead of 2/7.
        //
        // Merging also needs one date frame for both sides. The histories are stamped with the wall
        // clock, the nights are already local dates, so both are reduced to a local date in the
        // injected zone - the same zone `nightOf` uses. A night is named by the date it ends on.
        val uniqueDays = if (hrvPerDay) {
            val tz = zone()
            val historyDays = allTimestamps.map {
                Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date
            }
            (historyDays + hrvByNight.keys).toSet().size
        } else {
            // Unchanged on purpose. With the switch off this must stay exactly what it was, down to
            // the UTC day boundary. Moving that to a local day is a visible behaviour change for
            // everyone and belongs in its own piece of work.
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
            stepsHistory = dailyStepsHistory.toMap(),
            // The night map is what the per-night baseline reads, so it has to survive a restart
            // too. Keyed by the night's local date, written as ISO text.
            hrvNights = hrvByNight.entries.associate { (date, value) -> date.toString() to value }
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
        // Nights are pruned by date. The oldest ones go first, and at most WINDOW_DAYS remain.
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
     * readings are dropped: they say nothing about a resting normal, and a reading taken mid-effort
     * would drag the night down and read later as stress.
     *
     * A night only counts once it is **closed** - the window has passed - so the reading being
     * judged is never part of the baseline it is judged against.
     *
     * It also needs [MIN_NIGHT_COVERAGE_MS] between its first and last reading. Coverage, not a
     * count: two readings taken minutes apart are one moment, and counting them as a night is the
     * mistake the per-run history made at a larger scale.
     *
     * All of it is computed in `LocalDateTime`, never by adding milliseconds, so the short night in
     * spring and the long one in autumn are each still one night.
     */
    private fun closedNightMedians(samples: List<HRVDataMTR>, nowMs: Long): Map<LocalDate, Double> {
        if (samples.isEmpty()) return emptyMap()
        val tz = zone()
        val nowLocal = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(tz)

        return samples
            .filter { it.hasValidData() }
            .groupBy { nightOf(it.timestamp, tz) }
            .mapNotNull { (night, nightSamples) ->
                if (night == null) return@mapNotNull null
                if (!isClosed(night, nowLocal)) return@mapNotNull null
                val times = nightSamples.map { it.timestamp }
                if (times.max() - times.min() < MIN_NIGHT_COVERAGE_MS) return@mapNotNull null
                night to medianOf(nightSamples.map { it.value })
            }
            .toMap()
    }

    /** The night a reading belongs to, named after its morning, or null when it is daytime. */
    private fun nightOf(timestampMs: Long, tz: TimeZone): LocalDate? {
        val local = Instant.fromEpochMilliseconds(timestampMs).toLocalDateTime(tz)
        return when {
            local.hour >= NIGHT_START_HOUR -> local.date.plus(1, DateTimeUnit.DAY)
            local.hour < NIGHT_END_HOUR    -> local.date
            else                           -> null
        }
    }

    /** A night is closed once its morning end hour has passed. */
    private fun isClosed(night: LocalDate, nowLocal: LocalDateTime): Boolean =
        nowLocal.date > night || (nowLocal.date == night && nowLocal.hour >= NIGHT_END_HOUR)

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
            hrvByNight.clear()
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
