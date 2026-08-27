package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.pow
import kotlin.math.round

/** Intercept of the circadian polynomial used in `DetermineBasalaimiSMB2` SMB scaling. */
internal const val CIRCADIAN_INTERCEPT: Double = 1.38581503

/**
 * Circadian sensitivity factor vs hour-of-day (hours as fractional day clock).
 * Replaces the long inlined polynomial in `DetermineBasalaimiSMB2`.
 */
internal fun circadianSensitivityHourly(nowMinutes: Double): Double =
    0.00000379 * nowMinutes.pow(5) -
        0.00016422 * nowMinutes.pow(4) +
        0.00128081 * nowMinutes.pow(3) +
        0.02533782 * nowMinutes.pow(2) -
        0.33275556 * nowMinutes +
        CIRCADIAN_INTERCEPT

/**
 * SMB circadian scale: algebraically equivalent to scaling each term by [delta] except the intercept.
 * `round((delta * (S - c) + c) * 100) / 100` with S = [circadianSensitivityHourly], c = intercept.
 */
internal fun circadianSmbScaled(nowMinutes: Double, delta: Float): Double {
    val sens = circadianSensitivityHourly(nowMinutes)
    return round((delta.toDouble() * (sens - CIRCADIAN_INTERCEPT) + CIRCADIAN_INTERCEPT) * 100.0) / 100.0
}
