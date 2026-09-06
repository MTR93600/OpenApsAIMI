package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock

/**
 * Measures the insulin sensitivity that the **outcomes** imply, as `-dBG / insulin absorbed` over
 * clean falls. It is an instrument, not a controller.
 *
 * ## What the number is worth, and what it is not worth
 *
 * Endogenous glucose production is **not** modelled. The liver keeps releasing glucose while the
 * glucose falls, so part of the insulin that was absorbed paid for that release instead of moving
 * the measured glucose down. The measured number is therefore a **lower bound** on the true
 * sensitivity — but only under one condition: the profile basal must not be too high. The window
 * credits the insulin above the profile basal, so if the profile basal itself is too high, the
 * excess insulin it carries is never counted as a cost while its effect is still counted as a fall.
 * The number then reads **too high**, not too low. Read it next to `command_isf_mgdl`, and read the
 * two together, never one alone.
 *
 * There is no carbohydrate model, no meal model and no exercise model here. Windows with carbs on
 * board, or with a meal in the run-up, or with a measurable rate of glucose appearance, are simply
 * refused. The class prefers to answer nothing rather than to answer with a guess.
 *
 * ## Strictly passive
 *
 * Nothing in the dosing chain may read this. To check that, run:
 *
 * ```
 * grep -rn "ObservedSensitivityMeter\|isf_obs_" plugins/aps/src/commonMain plugins/aps/src/androidMain
 * ```
 *
 * It must return exactly four zones and nothing else:
 *  1. this class,
 *  2. the import and the field declaration in `DetermineBasalAIMI2`,
 *  3. the one feeding block in `runAimiSnapshotMedicalJsonAndHormonitorExportStage`,
 *  4. the `BaselineState` field declarations and their `put` lines in `toMedicalJson`.
 *
 * Any other line is a violation: it would mean a dose depends on a passive instrument.
 *
 * ## Where the filters come from
 *
 * The thresholds are the ones the offline corpus study used, and they are written down in
 * [DynamicSensitivityPolicy]: at least 30 minutes, at least a 25 mg/dL fall, no carbs on board,
 * a mean rate of appearance below 0.30 mg/dL/min, and at least 0.8 U absorbed. Here they are
 * applied continuously, tick after tick, inside the app, so the same rule that produced the offline
 * table also runs live.
 *
 * The appearance-rate filter is **fail-closed**: a window in which the rate of appearance was never
 * known is refused, not accepted. An unverifiable filter is not a passed filter.
 *
 * ## No persistence
 *
 * Windows live in memory only. A process restart empties them, and this is a deliberate choice: the
 * measure is cheap to rebuild and a stale file would be worse than an empty one. `isf_obs_window_count`
 * is the witness — when it is small or zero, the medians simply have not been rebuilt yet.
 *
 * Source: `origin/dev_OAPSAIMI` @ `eb84e078f5` (unchanged on tip `c5db5a0333`). Study wiring matches
 * [DynIsfCache]: commonMain, no Metro `@Inject` (one tick-owned instance, constructed as a field on
 * `DetermineBasalaimiSMB2`). KMP adaptation only: [AapsLock] instead of `@Synchronized`
 * (`@Synchronized` is JVM-only). Clinical formulas and filters are unchanged.
 *
 * @param windowRetentionMs how long a closed window is kept.
 * @param maxWindows hard cap on the number of kept windows.
 */
class ObservedSensitivityMeter(
    private val windowRetentionMs: Long = 7L * 24 * 3_600_000L,
    private val maxWindows: Int = 256,
) {

    /**
     * One tick of state, as the loop saw it.
     *
     * @param timestampMs time of the tick.
     * @param localHourOfDay local hour, 0..23, used only to pick a [Stratum].
     * @param bgMgdl glucose of the tick.
     * @param iobU insulin on board at the tick, **before** the SMB this tick decides.
     * @param cobG carbs on board at the tick.
     * @param smbU SMB decided at this tick. It acts over the interval that follows the tick.
     * @param deliveredBasalUph basal that was actually running over the interval that ended here.
     * @param profileBasalUph profile basal rate for this time of day.
     * @param raMgdlPerMin estimated rate of glucose appearance, or `null` when it is not known.
     * @param lastBolusMs time of the last bolus of any origin.
     */
    data class Sample(
        val timestampMs: Long,
        val localHourOfDay: Int,
        val bgMgdl: Double,
        val iobU: Double,
        val cobG: Double,
        val smbU: Double,
        val deliveredBasalUph: Double,
        val profileBasalUph: Double,
        val raMgdlPerMin: Double?,
        val lastBolusMs: Long,
    )

    /** Time of day a window belongs to, chosen from the hour at the middle of the window. */
    enum class Stratum { NIGHT, DAY }

    /**
     * One closed measurement window.
     *
     * @param startMs time of the first sample of the window.
     * @param endMs time of the last sample of the window.
     * @param minutes length of the window in minutes.
     * @param startBgMgdl glucose at the start.
     * @param dropMgdl fall in glucose over the window, always positive.
     * @param absorbedU insulin credited to the window: the fall in IOB, plus the basal above profile,
     *   plus the SMBs decided inside it.
     * @param isfMgdlPerU the measured sensitivity, `dropMgdl / absorbedU`.
     * @param stratum night or day, from the middle of the window.
     */
    data class Window(
        val startMs: Long,
        val endMs: Long,
        val minutes: Double,
        val startBgMgdl: Double,
        val dropMgdl: Double,
        val absorbedU: Double,
        val isfMgdlPerU: Double,
        val stratum: Stratum,
    )

    /**
     * What the instrument reports for a look-back period.
     *
     * A median is `null` when there are fewer than [MIN_WINDOWS_FOR_MEDIAN] windows. It is never
     * `0.0`: zero would be read as a real sensitivity of zero.
     *
     * @param medianMgdlPerU median over all strata.
     * @param windowCount how many windows the period holds.
     * @param nightMedianMgdlPerU median of the night windows.
     * @param nightCount how many night windows, reported even when the median is `null`.
     * @param dayMedianMgdlPerU median of the day windows.
     * @param dayCount how many day windows, reported even when the median is `null`.
     * @param lastWindow the most recently closed window in the period.
     */
    data class Reading(
        val medianMgdlPerU: Double?,
        val windowCount: Int,
        val nightMedianMgdlPerU: Double?,
        val nightCount: Int,
        val dayMedianMgdlPerU: Double?,
        val dayCount: Int,
        val lastWindow: Window?,
    ) {

        companion object {

            /** The reading of an instrument that has measured nothing. */
            val EMPTY = Reading(
                medianMgdlPerU = null,
                windowCount = 0,
                nightMedianMgdlPerU = null,
                nightCount = 0,
                dayMedianMgdlPerU = null,
                dayCount = 0,
                lastWindow = null,
            )
        }
    }

    // Dedicated lock: @Synchronized is JVM-only. Same monitor contract as the reference methods.
    private val lock = AapsLock()

    private val samples = ArrayList<Sample>()
    private val windows = ArrayList<Window>()
    private var lastWindowEndMs: Long = Long.MIN_VALUE

    /**
     * Feeds one tick and closes a window when one is complete.
     *
     * @return the window that just closed, or `null`. The return value exists for the tests; no
     *   caller in the dosing chain may use it.
     */
    fun observe(sample: Sample): Window? = lock.withLock {
        if (!sample.bgMgdl.isFinite() || sample.bgMgdl <= 0.0) return@withLock null
        if (!sample.iobU.isFinite() || !sample.cobG.isFinite()) return@withLock null
        if (!sample.smbU.isFinite() || !sample.deliveredBasalUph.isFinite() || !sample.profileBasalUph.isFinite()) return@withLock null
        if (sample.raMgdlPerMin != null && !sample.raMgdlPerMin.isFinite()) return@withLock null
        if (sample.timestampMs <= 0L) return@withLock null

        val previous = samples.lastOrNull()
        if (previous != null) {
            // A replay, a duplicate or a clock that stepped back. Accepting it would break the
            // ordering every accumulator below relies on.
            if (sample.timestampMs <= previous.timestampMs) return@withLock null
            // A hole means the basal integral over the hole is unknown, so every window that would
            // span it is wrong. Start again from here.
            if (sample.timestampMs - previous.timestampMs > MAX_GAP_MS) samples.clear()
        }

        samples.add(sample)
        val oldestKept = sample.timestampMs - BUFFER_MS
        while (samples.isNotEmpty() && samples[0].timestampMs < oldestKept) samples.removeAt(0)
        while (samples.size > MAX_SAMPLES) samples.removeAt(0)

        val window = closeWindow() ?: return@withLock null
        windows.add(window)
        lastWindowEndMs = window.endMs
        val oldestWindowKept = window.endMs - windowRetentionMs
        while (windows.isNotEmpty() && windows[0].endMs < oldestWindowKept) windows.removeAt(0)
        while (windows.size > maxWindows) windows.removeAt(0)
        window
    }

    /**
     * Reads the instrument over `(nowMs - lookbackMs, nowMs]`.
     *
     * Returns [Reading.EMPTY] when the period holds no window.
     */
    fun read(nowMs: Long, lookbackMs: Long = DEFAULT_LOOKBACK_MS): Reading = lock.withLock {
        val from = nowMs - lookbackMs
        val all = ArrayList<Double>()
        val night = ArrayList<Double>()
        val day = ArrayList<Double>()
        var last: Window? = null
        for (window in windows) {
            if (window.endMs <= from || window.endMs > nowMs) continue
            all.add(window.isfMgdlPerU)
            if (window.stratum == Stratum.NIGHT) night.add(window.isfMgdlPerU) else day.add(window.isfMgdlPerU)
            if (last == null || window.endMs >= last.endMs) last = window
        }
        if (all.isEmpty()) return@withLock Reading.EMPTY
        Reading(
            medianMgdlPerU = medianOrNull(all),
            windowCount = all.size,
            nightMedianMgdlPerU = medianOrNull(night),
            nightCount = night.size,
            dayMedianMgdlPerU = medianOrNull(day),
            dayCount = day.size,
            lastWindow = last,
        )
    }

    /** Drops every sample and every window. */
    fun reset() {
        lock.withLock {
            samples.clear()
            windows.clear()
            lastWindowEndMs = Long.MIN_VALUE
        }
    }

    /**
     * Walks backwards from the newest sample and returns the **shortest** window that passes every
     * filter, or `null`.
     *
     * The shortest one is kept on purpose: a longer window would average the local slope with older
     * behaviour and report a sensitivity that no moment actually showed.
     *
     * The accumulators grow as the walk goes back, so no sub-list is ever allocated.
     */
    private fun closeWindow(): Window? {
        if (samples.size < 2) return null
        val end = samples[samples.size - 1]

        var absorbedFromBasal = 0.0
        var smbSum = 0.0
        var raSum = 0.0
        var raCount = 0

        for (i in samples.size - 2 downTo 0) {
            val a = samples[i]
            val b = samples[i + 1]
            // Half-open interval (a, b]. The basal that ran over it is the one recorded at b, and
            // the SMB that was added over it is the one decided at a — that SMB is not yet inside
            // a.iobU, which is why it is credited separately.
            absorbedFromBasal += (b.timestampMs - a.timestampMs) / 3_600_000.0 * (b.deliveredBasalUph - b.profileBasalUph)
            smbSum += a.smbU
            val ra = a.raMgdlPerMin
            if (ra != null) {
                raSum += ra
                raCount++
            }

            // Windows must not overlap: two windows sharing samples would count the same insulin
            // twice and report the same fall twice.
            if (a.timestampMs < lastWindowEndMs) return null
            if (a.cobG > 0.0 || b.cobG > 0.0) return null

            val span = (end.timestampMs - a.timestampMs) / 60_000.0
            if (span > MAX_WINDOW_MINUTES) return null
            if (span < MIN_WINDOW_MINUTES) continue
            if (hasCarbsInRunUp(i)) continue

            val drop = a.bgMgdl - end.bgMgdl
            if (drop < MIN_DROP_MGDL) continue
            // Fail-closed: with no known appearance rate the meal filter cannot be checked, so the
            // window is refused rather than trusted.
            if (raCount == 0) continue
            if (raSum / raCount >= MAX_RA_MGDL_PER_MIN) continue

            val absorbed = (a.iobU - end.iobU) + absorbedFromBasal + smbSum
            if (absorbed < MIN_ABSORBED_U) continue

            val isf = drop / absorbed
            if (!isf.isFinite() || isf < MIN_PLAUSIBLE_ISF || isf > MAX_PLAUSIBLE_ISF) continue

            return Window(
                startMs = a.timestampMs,
                endMs = end.timestampMs,
                minutes = span,
                startBgMgdl = a.bgMgdl,
                dropMgdl = drop,
                absorbedU = absorbed,
                isfMgdlPerU = isf,
                stratum = stratumOf(a.timestampMs, end.timestampMs),
            )
        }
        return null
    }

    /**
     * True when a sample in `[start - RUN_UP_MINUTES, start)` had carbs on board.
     *
     * `startIndex` is the index of the sample that would open the window.
     */
    private fun hasCarbsInRunUp(startIndex: Int): Boolean {
        val startMs = samples[startIndex].timestampMs
        val from = startMs - RUN_UP_MINUTES * 60_000L
        for (j in startIndex - 1 downTo 0) {
            val s = samples[j]
            if (s.timestampMs < from) break
            if (s.cobG > 0.0) return true
        }
        return false
    }

    /**
     * Picks the stratum from the sample nearest the middle of the window.
     *
     * A window that straddles the night boundary is classified by its middle, not by its start, so
     * a 07:40 to 08:40 window counts as day.
     */
    private fun stratumOf(startMs: Long, endMs: Long): Stratum {
        val middleMs = startMs + (endMs - startMs) / 2
        var best: Sample? = null
        var bestDistance = Long.MAX_VALUE
        for (s in samples) {
            if (s.timestampMs < startMs || s.timestampMs > endMs) continue
            val distance = if (s.timestampMs > middleMs) s.timestampMs - middleMs else middleMs - s.timestampMs
            if (distance < bestDistance) {
                bestDistance = distance
                best = s
            }
        }
        val hour = best?.localHourOfDay ?: return Stratum.DAY
        return if (hour >= NIGHT_START_HOUR && hour < NIGHT_END_HOUR) Stratum.NIGHT else Stratum.DAY
    }

    /**
     * Classic median, `null` below [MIN_WINDOWS_FOR_MEDIAN] values.
     *
     * With an even count it is the mean of the two middle values, like `numpy.median`.
     */
    private fun medianOrNull(values: List<Double>): Double? {
        if (values.size < MIN_WINDOWS_FOR_MEDIAN) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }

    companion object {

        /** Shortest window that may be measured, in minutes. */
        const val MIN_WINDOW_MINUTES: Double = 30.0

        /** Longest window that may be measured, in minutes. */
        const val MAX_WINDOW_MINUTES: Double = 120.0

        /** Smallest fall in glucose a window must show, in mg/dL. */
        const val MIN_DROP_MGDL: Double = 25.0

        /** Highest mean rate of glucose appearance a window may show, in mg/dL/min. */
        const val MAX_RA_MGDL_PER_MIN: Double = 0.30

        /** Smallest amount of insulin a window must have absorbed, in U. */
        const val MIN_ABSORBED_U: Double = 0.8

        /** How far before the window start a meal still disqualifies it, in minutes. */
        private const val RUN_UP_MINUTES: Long = 30L

        /** Longest hole between two ticks that still keeps the buffer usable. */
        private const val MAX_GAP_MS: Long = 12L * 60_000L

        /** How much recent history the sample buffer keeps. */
        private const val BUFFER_MS: Long = 150L * 60_000L

        /** Hard cap on the sample buffer. */
        private const val MAX_SAMPLES: Int = 256

        /** First hour of the night stratum, inclusive. */
        private const val NIGHT_START_HOUR: Int = 0

        /** First hour that is no longer night, exclusive. */
        private const val NIGHT_END_HOUR: Int = 8

        /** Below this many windows a median is reported as `null`, never as a number. */
        private const val MIN_WINDOWS_FOR_MEDIAN: Int = 3

        /** Default look-back of [read]. */
        private const val DEFAULT_LOOKBACK_MS: Long = 24L * 3_600_000L

        /** Lowest sensitivity that can be a measurement rather than noise, in mg/dL/U. */
        private const val MIN_PLAUSIBLE_ISF: Double = 2.0

        /** Highest sensitivity that can be a measurement rather than noise, in mg/dL/U. */
        private const val MAX_PLAUSIBLE_ISF: Double = 400.0
    }
}
