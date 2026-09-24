package app.aaps.plugins.aps.openAPSAIMI.physio

import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

/**
 * An **awake** resting heart rate, measured from the same heart-rate samples the loop already reads.
 *
 * ## Why this exists
 *
 * `HealthContextRepository` publishes `rhrResting` as the **lowest** morning value of the last seven
 * days — a sleeping number, pinned at 50 bpm on every tick of three support packages. The stress ISF
 * floor compares an **awake** heart rate against it and calls a gap of 20 bpm a stress signature, so
 * the real trigger was "heart rate at least 70 bpm", and the floor was active on 36 % to 57 % of each
 * day. That is a baseline state, not an episode.
 *
 * Measured on 13 packages and 275 hours, an honest awake resting rate is **69 bpm** (66 to 71 per
 * day), so the same +20 bpm rule then triggers at 86 to 91 bpm and the gesture is active 3.9 % of the
 * time instead of 51 %. The estimate was stable across every definition that was tried: 69 to 70 bpm
 * whether the step filter was 0 or 50, whether the awake window started at 08:00 or 10:00, and
 * whether samples were counted once per reading or once per tick.
 *
 * ## The rules, and why each one is here
 *
 * - Night hours are dropped. The hourly median is 60 to 66 bpm between 02:00 and 07:00 against 74 to
 *   87 bpm in the day, so keeping the night would measure sleep again. The bounds are the ones
 *   `HealthContextRepository.clockIsNightHour` already uses, 23:00 to 06:00 local.
 * - Samples taken while walking are dropped when the step count of that moment is known. A recovery
 *   heart rate after a walk is not a resting one.
 * - The value is the **10th centile**, not the minimum. A minimum is one sample and one bad contact.
 * - Too little data gives **null**, and null means the gesture stands down. Substituting 50, 60 or 80
 *   is exactly the defect this object was written to remove, so there is no fallback here at all.
 *
 * This object is pure: it holds no state, reads no clock and touches no preference.
 */
object AwakeRestingHeartRate {

    /** How many days of history the estimate is built from. */
    const val WINDOW_DAYS: Int = 7

    /** First awake hour, local. Mirrors the night bounds of `HealthContextRepository`. */
    const val AWAKE_FIRST_HOUR: Int = 6

    /** First hour that counts as night again, local, i.e. the awake window ends here. */
    const val NIGHT_FIRST_HOUR: Int = 23

    /** The centile taken over the usable samples, as a fraction. */
    const val PERCENTILE: Double = 0.10

    /**
     * Steps in the last 15 minutes above which a sample is not a resting one.
     *
     * Not the 250 of `StressIsfFloor.MAX_STEPS_LAST_15M`: that one separates a cortisol rise from a
     * walk, this one selects calm moments. The replay measured 69 bpm with "no steps at all" and
     * 70 bpm with this bound, so the choice changes the answer by about one beat.
     */
    const val MAX_STEPS_LAST_15M: Int = 50

    /** Fewest usable samples that may produce a value. */
    const val MIN_SAMPLES: Int = 50

    /** Fewest distinct local days the usable samples must be spread over. */
    const val MIN_DISTINCT_DAYS: Int = 3

    /**
     * One heart-rate reading.
     *
     * @param timestampMs when it was measured.
     * @param bpm the reading itself. Zero or less is missing data and is dropped.
     * @param stepsLast15m steps counted in the 15 minutes around the reading, or null when the step
     *   history does not cover that moment. Null keeps the sample: the step filter is a refinement,
     *   not a condition.
     */
    data class Sample(
        val timestampMs: Long,
        val bpm: Double,
        val stepsLast15m: Int? = null,
    )

    /**
     * The awake resting heart rate of these samples, in bpm, or null when there is not enough data.
     *
     * @param samples every reading of the window, in any order.
     * @param zoneId the local time zone, used to tell day from night and one day from the next.
     */
    fun estimate(samples: List<Sample>, zoneId: ZoneId): Int? {
        val usable = samples.filter { isUsable(it, zoneId) }
        if (usable.size < MIN_SAMPLES) return null
        val distinctDays = usable.map { localDay(it.timestampMs, zoneId) }.distinct().size
        if (distinctDays < MIN_DISTINCT_DAYS) return null

        val sorted = usable.map { it.bpm }.sorted()
        // Nearest rank: the smallest value that at least PERCENTILE of the samples sit at or under.
        val rank = ceil(PERCENTILE * sorted.size).toInt().coerceIn(1, sorted.size)
        val value = sorted[rank - 1]
        if (!value.isFinite() || value <= 0.0) return null
        return value.toInt()
    }

    private fun isUsable(sample: Sample, zoneId: ZoneId): Boolean {
        if (!sample.bpm.isFinite() || sample.bpm <= 0.0) return false
        if (sample.stepsLast15m != null && sample.stepsLast15m > MAX_STEPS_LAST_15M) return false
        val hour = Instant.ofEpochMilli(sample.timestampMs).atZone(zoneId).hour
        return hour in AWAKE_FIRST_HOUR until NIGHT_FIRST_HOUR
    }

    private fun localDay(timestampMs: Long, zoneId: ZoneId): Long =
        Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate().toEpochDay()
}
