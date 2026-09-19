package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import app.aaps.core.data.time.T
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
const val CORRECTION_AT_CENTER_MIN = -30.0
const val CORRECTION_AT_CENTER_MAX = 30.0

const val MIN_ENTRIES_FOR_FIT = 2

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
    val correctionAtCenter: Double get() = (slope - 1) * CENTER_MGDL + offset

    val slopeInRange: Boolean get() = slope in SLOPE_MIN..SLOPE_MAX
    val correctionInRange: Boolean get() = correctionAtCenter in CORRECTION_AT_CENTER_MIN..CORRECTION_AT_CENTER_MAX
    val isApplicable: Boolean get() = slopeInRange && correctionInRange
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
    if (sensorRange < MIN_SENSOR_RANGE_FOR_SLOPE) {
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
