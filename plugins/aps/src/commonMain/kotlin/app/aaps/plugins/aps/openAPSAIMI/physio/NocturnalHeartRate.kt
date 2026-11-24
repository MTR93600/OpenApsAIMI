package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * The heart rate of the last night, compared with the same person's usual night.
 *
 * ## Why this exists
 *
 * A night heart rate a few beats above the person's own usual value is a known sign of poor
 * recovery: short sleep, stress, an infection starting, alcohol. HRV carries the same kind of
 * information, but HRV needs the time between single beats. Many devices do not give it to AAPS
 * (a Garmin watch face cannot read it, and Garmin Connect does not write it to Apple Health), while
 * every one of them writes heart rate into the AAPS heart-rate table. So this works with any
 * device that feeds that table: the Garmin watch face, a Wear OS watch, Health Connect, Apple Health.
 *
 * ## What it changes in the dose
 *
 * The night says *that* something changed, not *which way*: an infection that starts makes a person
 * need more insulin, alcohol or a hard effort the day before make them need less. So the night does
 * not choose a direction. On a day after an **elevated** night ([isElevated]), the short-term part of
 * `UnifiedReactivityLearner` - the part that follows the last two hours of glucose, up after highs,
 * down after a low - gets more weight, until [ALERT_END_HOUR]. The glucose chooses the direction; the
 * night only says to listen to it more closely. Everything else is shown and logged only.
 *
 * An elevated night must be rare to mean something, so the rule asks a lot: at least
 * [MIN_ALERT_BASELINE_NIGHTS] earlier nights of the same device, at least [MIN_ALERT_DELTA_BPM] above
 * the usual night, and at least [MIN_ALERT_Z] times the usual night-to-night spread.
 *
 * ## The rules, and why each one is here
 *
 * - **A fixed clock window, 00:00 to 06:00 local.** These are hours of sleep for most people. A
 *   sleep session from Health Connect or Apple Health would be more exact, but it exists only for
 *   some devices and only for the last night. The baseline compares nights with each other, so every
 *   night must be measured the same way.
 * - **Samples taken while walking are dropped** when the step count of that moment is known. A walk
 *   to the bathroom is not sleep.
 * - **Ten-minute slots.** The samples of one slot give one value (their median), and the night value
 *   is the median of the slots. So a device that writes one sample per minute does not weigh more
 *   than one that writes every five minutes, and one bad reading does not move the result.
 * - **At least three hours covered**, or the night has no value. A missing night is an honest
 *   answer; a value built from 40 minutes is not.
 * - **One device per night**: the one that covers the most slots. The same heartbeats can arrive by
 *   two paths (a Garmin watch face, and Garmin Connect copied into Apple Health), and must not be
 *   counted twice.
 * - **A baseline per device.** A ring and a watch do not read exactly the same night heart rate. A
 *   change of device must not look like a bad night, so the night is only compared with earlier
 *   nights of the same device: the median of up to [BASELINE_NIGHTS] nights, and no baseline before
 *   [MIN_BASELINE_NIGHTS] nights.
 *
 * This object is pure: it holds no state, reads no clock and touches no preference.
 */
object NocturnalHeartRate {

    /** First hour of the night window, local, included. */
    const val NIGHT_FIRST_HOUR: Int = 0

    /** First hour after the night window, local. Also the hour from which "last night" is complete. */
    const val NIGHT_END_HOUR: Int = 6

    /** Length of one slot. */
    const val SLOT_MS: Long = 10L * 60L * 1000L

    /** Fewest slots with a reading for a night to have a value: 18 slots are 3 hours. */
    const val MIN_COVERED_SLOTS: Int = 18

    /** Steps in the last 15 minutes above which a reading is not a sleeping one. */
    const val MAX_STEPS_LAST_15M: Int = 50

    /** Lowest and highest reading kept. Anything outside is a sensor error. */
    const val MIN_PLAUSIBLE_BPM: Double = 25.0
    const val MAX_PLAUSIBLE_BPM: Double = 220.0

    /** How many earlier nights the baseline may use. */
    const val BASELINE_NIGHTS: Int = 14

    /** Fewest earlier nights of the same device before there is a baseline. */
    const val MIN_BASELINE_NIGHTS: Int = 5

    /** Fewest earlier nights of the same device before a night may count as elevated. */
    const val MIN_ALERT_BASELINE_NIGHTS: Int = 7

    /** Smallest rise over the usual night, in bpm, that may count as elevated. */
    const val MIN_ALERT_DELTA_BPM: Double = 4.0

    /** Smallest rise, in units of the usual night-to-night spread, that may count as elevated. */
    const val MIN_ALERT_Z: Double = 2.0

    /**
     * Smallest spread used, in bpm. A device whose nights were all within one beat would otherwise
     * turn a 4 bpm night into a huge score. Two beats is about the night-to-night noise of a wrist
     * sensor.
     */
    const val MIN_SPREAD_BPM: Double = 2.0

    /** Converts a median absolute deviation into a standard deviation for normal data. */
    private const val MAD_TO_SD: Double = 1.4826

    /** First local hour at which the alert of the day ends: the evening, before the next night. */
    const val ALERT_END_HOUR: Int = 23

    /** How many days of heart-rate history a caller must load: the baseline nights and the last one. */
    const val HISTORY_DAYS: Int = BASELINE_NIGHTS + 2

    /**
     * One heart-rate reading.
     *
     * @param timestampMs when it was measured.
     * @param bpm the reading.
     * @param device the device that wrote it, as stored in the heart-rate table.
     * @param stepsLast15m steps in the 15 minutes around the reading, or null when not known. Null
     *   keeps the reading: the step filter is a refinement, not a condition.
     */
    data class Sample(
        val timestampMs: Long,
        val bpm: Double,
        val device: String,
        val stepsLast15m: Int? = null,
    )

    /**
     * The result for the last night.
     *
     * @param night the local date on which the night ended (the morning).
     * @param device the device used for this night, or null when no device wrote any usable reading.
     * @param coveredMinutes minutes of the night window with a reading, for that device.
     * @param bpm the night heart rate, or null when fewer than [MIN_COVERED_SLOTS] slots are covered.
     * @param baselineBpm the usual night heart rate of the same device, or null when there are fewer
     *   than [MIN_BASELINE_NIGHTS] earlier nights, or when [bpm] is null.
     * @param baselineNights how many earlier nights of that device were found (at most [BASELINE_NIGHTS]).
     * @param spreadBpm the usual night-to-night spread of that device (robust standard deviation, at
     *   least [MIN_SPREAD_BPM]), or null when there is no baseline.
     */
    data class Report(
        val night: LocalDate,
        val device: String?,
        val coveredMinutes: Int,
        val bpm: Double?,
        val baselineBpm: Double?,
        val baselineNights: Int,
        val spreadBpm: Double? = null,
    ) {

        /** Night minus baseline, in bpm. Positive means a faster heart than usual. Null when either is missing. */
        val deltaBpm: Double?
            get() = if (bpm != null && baselineBpm != null) bpm - baselineBpm else null

        /** [deltaBpm] in units of [spreadBpm]. Null when either is missing. */
        val zScore: Double?
            get() {
                val delta = deltaBpm ?: return null
                val spread = spreadBpm ?: return null
                return delta / spread
            }
    }

    /**
     * The report for the last complete night.
     *
     * Before [NIGHT_END_HOUR] the night of today is still running, so the report is about the night
     * that ended yesterday morning.
     *
     * @param samples the readings of at least the last [HISTORY_DAYS] days, in any order.
     * @param nowMs the current time.
     * @param zone the local time zone.
     */
    fun report(samples: List<Sample>, nowMs: Long, zone: TimeZone): Report {
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)
        val lastNight = if (now.hour >= NIGHT_END_HOUR) now.date else now.date.minus(DatePeriod(days = 1))
        val firstNight = lastNight.minus(DatePeriod(days = BASELINE_NIGHTS))

        // night -> device -> slot index -> readings of that slot
        val slots = HashMap<LocalDate, HashMap<String, HashMap<Long, MutableList<Double>>>>()
        for (sample in samples) {
            if (!isUsable(sample)) continue
            val local = Instant.fromEpochMilliseconds(sample.timestampMs).toLocalDateTime(zone)
            if (local.hour !in NIGHT_FIRST_HOUR until NIGHT_END_HOUR) continue
            if (local.date < firstNight || local.date > lastNight) continue
            slots.getOrPut(local.date) { HashMap() }
                .getOrPut(sample.device) { HashMap() }
                .getOrPut(sample.timestampMs.floorDiv(SLOT_MS)) { mutableListOf() }
                .add(sample.bpm)
        }

        val lastNightDevices = slots[lastNight].orEmpty()
        // Most slots wins. maxByOrNull keeps the first of equal ones, and the names are sorted first,
        // so a tie does not depend on map order.
        val device = lastNightDevices.keys.sorted().maxByOrNull { lastNightDevices.getValue(it).size }
            ?: return Report(lastNight, device = null, coveredMinutes = 0, bpm = null, baselineBpm = null, baselineNights = 0)

        val coveredSlots = lastNightDevices.getValue(device).size
        val bpm = nightValue(lastNightDevices.getValue(device))
        val earlierNights = slots.entries
            .filter { it.key < lastNight }
            .mapNotNull { (_, devices) -> devices[device]?.let(::nightValue) }
        val baseline = if (bpm != null && earlierNights.size >= MIN_BASELINE_NIGHTS) median(earlierNights) else null
        val spread = baseline?.let { center ->
            maxOf(MAD_TO_SD * median(earlierNights.map { abs(it - center) }), MIN_SPREAD_BPM)
        }

        return Report(
            night = lastNight,
            device = device,
            coveredMinutes = (coveredSlots * SLOT_MS / 60_000L).toInt(),
            bpm = bpm,
            baselineBpm = baseline,
            baselineNights = earlierNights.size,
            spreadBpm = spread,
        )
    }

    /**
     * True when [report] is about the night that ended this morning, it is clearly above the usual
     * night of the same device, and the day is not over yet ([ALERT_END_HOUR]).
     *
     * Every condition must hold. A report that is missing, about an older night, built on too few
     * earlier nights, or only a little above normal gives false, which leaves the dose as it was.
     */
    fun isElevated(report: Report?, nowMs: Long, zone: TimeZone): Boolean {
        if (report == null) return false
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)
        if (report.night != now.date) return false
        if (now.hour < NIGHT_END_HOUR || now.hour >= ALERT_END_HOUR) return false
        if (report.baselineNights < MIN_ALERT_BASELINE_NIGHTS) return false
        val delta = report.deltaBpm ?: return false
        val z = report.zScore ?: return false
        return delta >= MIN_ALERT_DELTA_BPM && z >= MIN_ALERT_Z
    }

    /**
     * The night heart rate line of the physio status. Says whether the night acts on the dose today,
     * and says why when there is no value, so a missing number is never mistaken for a normal night.
     *
     * @param elevated what [isElevated] answered for the same report.
     */
    fun statusLine(report: Report?, elevated: Boolean = false): String {
        val prefix = "🌙 Night HR"
        if (report == null) return "$prefix: -- (not measured yet)"
        val hours = round1(report.coveredMinutes / 60.0)
        val bpm = report.bpm
            ?: return if (report.device == null) {
                "$prefix: -- (no reading between 00:00 and 06:00)"
            } else {
                "$prefix: -- (${hours} h covered, needs ${MIN_COVERED_SLOTS * SLOT_MS / 3_600_000L} h)"
            }
        val value = "$prefix: ${round1(bpm)} bpm"
        val details = "${report.device}, ${hours} h"
        val tail = if (elevated) {
            "ELEVATED: short-term reactivity weighs more until ${ALERT_END_HOUR}:00"
        } else {
            "no effect on dosing"
        }
        val baseline = report.baselineBpm
        val delta = report.deltaBpm
        return if (baseline == null || delta == null) {
            "$value | usual night after ${MIN_BASELINE_NIGHTS} nights " +
                "(${report.baselineNights}/${MIN_BASELINE_NIGHTS}) | $details | $tail"
        } else {
            val roundedDelta = round1(delta)
            val sign = if (roundedDelta > 0.0) "+" else ""
            val z = report.zScore?.let { "${round1(it)} SD, " } ?: ""
            "$value ($sign$roundedDelta vs usual ${round1(baseline)}, $z" +
                "${report.baselineNights} nights) | $details | $tail"
        }
    }

    /** One decimal, for logs and display. */
    fun round1(value: Double): Double = (value * 10.0).roundToInt() / 10.0

    private fun isUsable(sample: Sample): Boolean {
        if (!sample.bpm.isFinite() || sample.bpm < MIN_PLAUSIBLE_BPM || sample.bpm > MAX_PLAUSIBLE_BPM) return false
        val steps = sample.stepsLast15m
        return steps == null || steps <= MAX_STEPS_LAST_15M
    }

    /** Median of the slot medians, or null when too few slots are covered. */
    private fun nightValue(slotReadings: Map<Long, List<Double>>): Double? {
        if (slotReadings.size < MIN_COVERED_SLOTS) return null
        return median(slotReadings.values.map(::median))
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
