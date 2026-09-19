package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.GV
import app.aaps.core.data.time.T
import kotlin.math.abs
import kotlin.math.exp

const val TIME_DECAY_TAU_DAYS = 2L

// Slope bounds match xDrip+'s LiParametersNonFixed (its widest mainstream profile) —
// accommodates compression patterns like Syai's without admitting absurd single-fingerstick
// fits. xDrip's tiered limits (size=2 vs size>2) are not modelled here.
const val SLOPE_MIN = 0.55
const val SLOPE_MAX = 1.6

// Reference BG (mg/dL) used to clamp the calibration line's correction magnitude.
// Picked as a typical mid-range BG so the clamp value below maps directly to
// "the most this calibration can shift the sensor at typical BG".
const val CENTER_MGDL = 100.0
const val CORRECTION_AT_CENTER_MAX = 30.0

// The centre check alone does NOT bound the line: it fixes one point, and a slope still swings the
// ends far away from it. Two fingersticks (sensor 110 -> 135, sensor 180 -> 175) fit slope 0.571,
// offset 72.1 — correction at 100 is +29, so the centre check passes, yet a sensor reading 55 is
// handed to the loop as 104, and the hypo alarm never fires.
//
// Both ends are therefore checked as well, and ONE-SIDED, because only one direction is dangerous:
// a line that reads LOWER than the sensor makes the loop more careful, while a line that reads
// HIGHER hides a hypo at the low end and invents a hyper at the high end. Same asymmetry as xDrip+'s
// Libre offset window [-40, +20].
const val LOW_MGDL = 40.0

/** Most a calibration may ADD to a reading of [LOW_MGDL] — a bigger lift can hide a hypo. */
const val CORRECTION_AT_LOW_MAX = 20.0

/**
 * Most a calibration may TAKE OFF a reading of [LOW_MGDL].
 *
 * The downward direction has no bound at the centre, because reading lower than the sensor is the
 * careful direction: the loop gives less insulin, not more. A sensor that over-reads by a third is a
 * real case (blood 209 while the sensor says 309) and the loop dosing for 309 is the harm we are
 * trying to stop.
 *
 * It still needs a bound somewhere, and the low end is where the damage shows. The fit that has to
 * be refused is the FLAT one: two fingersticks 100 mg/dL apart give slope 1 and offset −100, which
 * turns a reading of 60 into −40. The loop would see the floor value for ever and stop dosing
 * altogether — not a hypo, but a real harm in the other direction.
 *
 * Checking at [LOW_MGDL] separates the two cases by itself. A MULTIPLICATIVE correction — what an
 * over-reading sensor actually does, the error growing with the reading — is small down here and
 * passes; a flat offset is just as big down here as it is at 300 and is refused. Same shape of
 * reasoning as [MAX_RATIO_AT_HIGH] on the other side, mirrored.
 *
 * The number is set by the two cases it has to separate, not picked for roundness. It has to keep
 * the steepest line this plugin already accepts — the clamped compression fit at slope [SLOPE_MAX]
 * with offset −55.4, which takes 31.4 mg/dL off a reading of 40 — and it has to refuse a flat offset
 * past about the old two-sided centre bound of 30. Anything in between does both; −35 leaves the
 * first a small margin. For a slope of 1 it is very nearly the old behaviour, which is the point:
 * nothing gets looser for flat fits, only for fits whose correction grows with the reading.
 */
const val CORRECTION_AT_LOW_MIN = -35.0

const val HIGH_MGDL = 300.0

/**
 * Most a calibration may multiply a reading of [HIGH_MGDL] by.
 *
 * A ratio, not a number of mg/dL: with a legitimate steep slope the correction grows with the
 * reading, so a fixed mg/dL cap here would reject sensors that exaggerate (the ones a slope is for).
 * It still stops the extreme — slope 1.6 with offset −30 would turn 300 into 450.
 */
const val MAX_RATIO_AT_HIGH = 1.45

const val MIN_ENTRIES_FOR_FIT = 2

/**
 * Entries needed before a SLOPE is fitted at all; below this the fit is offset-only.
 *
 * Two fingersticks define a line exactly, so every bit of their noise — and of the lag between a
 * fingerstick and the interstitial sensor — goes straight into the slope, which then extrapolates
 * far outside the two points. The sensors this plugin sits on top of (Dexcom ONE+, Libre 3) are
 * factory calibrated, so their remaining error is mostly a shift, not a wrong scale: correcting the
 * shift is the safe default, and a scale is only fitted once three sticks agree on it. xDrip+ takes
 * the same line for factory-calibrated Libre sensors, where it allows an offset and locks the slope.
 */
const val MIN_ENTRIES_FOR_SLOPE = 3

// A fit built from entries this old or newer is trusted at full strength.
const val STALE_CONFIDENCE_FULL_DAYS = 2L

// Past this age, the fit is fully blended to identity (see [blendTowardIdentity]) — a calibration
// this old is not trusted at all, regardless of how good the original fit looked. Between the two
// thresholds, trust falls off linearly. This is separate from [weightFor]/[TIME_DECAY_TAU_DAYS],
// which only weighs entries against EACH OTHER: a fit built entirely from old entries would
// otherwise keep applying its full correction indefinitely, even though none of its inputs have
// been refreshed and sensor bias is known to drift over wear time.
const val STALE_CONFIDENCE_ZERO_DAYS = 6L

// Minimum spread of sensor values (mg/dL) required to trust a slope estimate.
// Below this, leverage is too low: noise in fingerstick values dominates the slope,
// which then extrapolates wildly outside the cluster. Falls back to offset-only.
const val MIN_SENSOR_RANGE_FOR_SLOPE = 54.0

enum class FitMode {
    /** Weighted least squares — both slope and offset fitted freely. */
    Full,

    /**
     * Free slope landed outside `[SLOPE_MIN, SLOPE_MAX]`; slope was clamped to the boundary
     * and offset re-fitted holding that fixed slope. xDrip+-style "always apply something
     * bounded" behaviour for sensors with strong compression/exaggeration.
     */
    SlopeClamped,

    /** Sensor range too narrow for a reliable slope; slope locked to 1.0. */
    OffsetOnly
}

data class CalibrationFit(
    val slope: Double,
    val offset: Double,
    val mode: FitMode = FitMode.Full
) {

    /**
     * Correction (mg/dL) applied at sensor = [CENTER_MGDL].
     *
     * The line equation is `y = slope·x + offset`, so the correction at any x is
     * `y − x = (slope − 1)·x + offset`. Evaluating at [CENTER_MGDL] gives a single
     * scalar that's medically interpretable as "the calibration's shift at typical BG."
     * The applicability clamp is on this value rather than `offset` (which is the line's
     * intercept at sensor=0 — meaningless to the user when slope ≠ 1).
     */
    val correctionAtCenter: Double get() = correctionAt(CENTER_MGDL)

    /** Correction (mg/dL) this line applies to a sensor reading of [sensorMgdl]: `y − x`. */
    fun correctionAt(sensorMgdl: Double): Double = (slope - 1) * sensorMgdl + offset

    val correctionAtLow: Double get() = correctionAt(LOW_MGDL)
    val correctionAtHigh: Double get() = correctionAt(HIGH_MGDL)

    /** How much the line multiplies a reading of [HIGH_MGDL] by. */
    val ratioAtHigh: Double get() = (slope * HIGH_MGDL + offset) / HIGH_MGDL

    val slopeInRange: Boolean get() = slope in SLOPE_MIN..SLOPE_MAX

    /**
     * A lift at typical BG is bounded; a drop is not.
     *
     * One-sided, like the two end checks, and for the same reason: lifting the reading hides a hypo
     * and invents a hyper, while lowering it only makes the loop more careful. The downward
     * direction is bounded at the low end instead, by [CORRECTION_AT_LOW_MIN].
     */
    val correctionInRange: Boolean get() = correctionAtCenter <= CORRECTION_AT_CENTER_MAX

    /**
     * Both directions are checked at the low end, for two different harms: a lift hides a hypo, and
     * a big flat drop pins the reading at the floor so the loop stops dosing.
     */
    val lowEndSafe: Boolean get() = correctionAtLow in CORRECTION_AT_LOW_MIN..CORRECTION_AT_LOW_MAX

    /** A lift at the high end invents a hyper the loop then answers with insulin. */
    val highEndSafe: Boolean get() = ratioAtHigh <= MAX_RATIO_AT_HIGH

    val isApplicable: Boolean get() = slopeInRange && correctionInRange && lowEndSafe && highEndSafe
}

/**
 * Time-decay weight (0..1] for an entry of age `now - timestamp`, matching the fit's τ.
 *
 * Future-dated entries (clock skew, backup import) are clamped to weight = 1.0 instead of
 * extrapolating to `exp(+large)` → `Inf`, which would propagate as `NaN` through the fit sums.
 */
internal fun weightFor(timestamp: Long, now: Long): Double {
    if (timestamp >= now) return 1.0
    val tauMs = T.days(TIME_DECAY_TAU_DAYS).msecs().toDouble()
    return exp(-(now - timestamp) / tauMs)
}

/**
 * Weighted least squares fit of (sensorMgdlAtPairing → fingerstickMgdl) pairs.
 * Each entry's weight decays exponentially with age: weight = exp(-Δt / τ).
 *
 * Returns null when fewer than [MIN_ENTRIES_FOR_FIT] entries are provided
 * or when all sensor values collapse to a single point (degenerate denominator).
 */
fun fitLinearCalibration(entries: List<CAL>, now: Long): CalibrationFit? {
    if (entries.size < MIN_ENTRIES_FOR_FIT) return null

    val sensorRange = entries.maxOf { it.sensorMgdlAtPairing } - entries.minOf { it.sensorMgdlAtPairing }
    if (entries.size < MIN_ENTRIES_FOR_SLOPE || sensorRange < MIN_SENSOR_RANGE_FOR_SLOPE) {
        // Offset-only: weighted mean of (fingerstick - sensor), slope locked to 1.
        var sumW = 0.0
        var sumWDelta = 0.0
        for (e in entries) {
            val w = weightFor(e.timestamp, now)
            sumW += w
            sumWDelta += w * (e.fingerstickMgdl - e.sensorMgdlAtPairing)
        }
        if (sumW == 0.0) return null
        return CalibrationFit(slope = 1.0, offset = sumWDelta / sumW, mode = FitMode.OffsetOnly)
    }

    var sumW = 0.0
    var sumWX = 0.0
    var sumWY = 0.0
    var sumWXX = 0.0
    var sumWXY = 0.0
    for (e in entries) {
        val w = weightFor(e.timestamp, now)
        val x = e.sensorMgdlAtPairing
        val y = e.fingerstickMgdl
        sumW += w
        sumWX += w * x
        sumWY += w * y
        sumWXX += w * x * x
        sumWXY += w * x * y
    }
    val denom = sumW * sumWXX - sumWX * sumWX
    if (denom == 0.0) return null
    val rawSlope = (sumW * sumWXY - sumWX * sumWY) / denom
    val rawOffset = (sumWXX * sumWY - sumWX * sumWXY) / denom

    // If the free slope is outside the safety clamp, hold it at the boundary and re-fit
    // the offset for that fixed slope (offset = weighted-mean-y − slope·weighted-mean-x).
    // Mirrors xDrip+'s "clamp + apply" rather than "reject + identity" behaviour.
    val clampedSlope = rawSlope.coerceIn(SLOPE_MIN, SLOPE_MAX)
    return if (clampedSlope == rawSlope) {
        CalibrationFit(rawSlope, rawOffset, mode = FitMode.Full)
    } else {
        val offsetForClampedSlope = (sumWY - clampedSlope * sumWX) / sumW
        CalibrationFit(clampedSlope, offsetForClampedSlope, mode = FitMode.SlopeClamped)
    }
}

/**
 * Confidence (0..1) for how much a fit should still be trusted, based on how long ago its NEWEST
 * entry was recorded. 1.0 while that entry is younger than [STALE_CONFIDENCE_FULL_DAYS], falling
 * off linearly to 0.0 at [STALE_CONFIDENCE_ZERO_DAYS] or beyond.
 */
internal fun stalenessConfidence(newestEntryTimestamp: Long, now: Long): Double {
    val ageMs = (now - newestEntryTimestamp).coerceAtLeast(0L).toDouble()
    val fullMs = T.days(STALE_CONFIDENCE_FULL_DAYS).msecs().toDouble()
    val zeroMs = T.days(STALE_CONFIDENCE_ZERO_DAYS).msecs().toDouble()
    return when {
        ageMs <= fullMs -> 1.0
        ageMs >= zeroMs -> 0.0
        else            -> (zeroMs - ageMs) / (zeroMs - fullMs)
    }
}

/**
 * Blends this fit toward identity (slope 1.0, offset 0.0) by [confidence] — 1.0 keeps it
 * unchanged, 0.0 returns pure identity. Meant to be applied AFTER the safety-range checks
 * ([isApplicable]): staleness reduces trust in an already-safe fit, it must never "age" a
 * fundamentally unsafe fit into looking safe.
 */
fun CalibrationFit.blendTowardIdentity(confidence: Double): CalibrationFit =
    copy(
        slope = 1.0 + confidence * (slope - 1.0),
        offset = confidence * offset,
    )

/**
 * How many sensor readings around a fingerstick may take part in the paired value.
 *
 * Five is chosen for a sensor that speaks once a minute, where it covers the few minutes around
 * the fingerstick. A sensor that speaks every five minutes simply has fewer readings in the same
 * window, and the median then runs over whatever is there, down to the single reading that the
 * older code always used.
 */
const val PAIR_MEDIAN_MAX_SAMPLES = 5

/**
 * Sensor value to store next to a fingerstick: the median of the readings closest to [timestamp].
 *
 * A calibration line is fitted through very few points, so the sensor side of each pair carries a
 * lot of weight. Taking one single reading makes that side as noisy as that one reading, and a
 * sensor that reports every minute is noisier per reading than one that reports every five. A
 * median over the nearest few readings takes that noise out without following it, which a mean
 * would do. The entries are only accepted while glucose is steady, so a short window cannot hide a
 * real move.
 *
 * @param readings sensor readings to choose from, in any order. Only their distance in time to
 *   [timestamp] matters.
 * @param timestamp moment of the fingerstick.
 * @param maxSamples how many of the nearest readings take part.
 * @return the paired sensor value in mg/dL, or null when there is no reading to pair with.
 */
fun sensorValueForPairing(
    readings: List<GV>,
    timestamp: Long,
    maxSamples: Int = PAIR_MEDIAN_MAX_SAMPLES
): Double? {
    if (readings.isEmpty() || maxSamples <= 0) return null
    val nearest = readings
        .sortedBy { abs(it.timestamp - timestamp) }
        .take(maxSamples)
        .map { it.value }
        .sorted()
    val middle = nearest.size / 2
    return if (nearest.size % 2 == 1) nearest[middle] else (nearest[middle - 1] + nearest[middle]) / 2.0
}

/**
 * Middle of the newest break longer than [gapThresholdMs] in [readings], or null when there is none.
 *
 * This must be fed the **stored** readings, never bucketed data: bucketing fills every break with
 * values it works out itself, so a bucketed series is always evenly spaced and no break can ever
 * be seen in it.
 *
 * @param readings sensor readings, newest first.
 * @param gapThresholdMs how long a break has to be to count as one.
 * @param notBefore stop looking once the readings are older than this, so a break that belongs to
 *   an earlier sensor is not reported again. Null looks through everything given.
 */
fun newestGapMidpoint(readings: List<GV>, gapThresholdMs: Long, notBefore: Long? = null): Long? {
    for (i in 0 until readings.size - 1) {
        val newer = readings[i].timestamp
        val older = readings[i + 1].timestamp
        if (notBefore != null && newer <= notBefore) return null
        if (newer - older > gapThresholdMs) return older + (newer - older) / 2
    }
    return null
}
