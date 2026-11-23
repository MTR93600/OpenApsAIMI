package app.aaps.plugins.aps.openAPSAIMI.extensions

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.Round
import app.aaps.plugins.aps.openAPSAIMI.AimiBgFeatures

fun GlucoseStatusAIMI.log(decimalFormatter: DecimalFormatter): String =
    "Glucose: ${decimalFormatter.to0Decimal(glucose)} mg/dl " +
        "Noise: ${decimalFormatter.to2Decimal(noise)} " +
        "Delta: ${decimalFormatter.to2Decimal(delta)} mg/dl " +
        "Short avg. delta: ${decimalFormatter.to2Decimal(shortAvgDelta)} mg/dl " +
        "Long avg. delta: ${decimalFormatter.to2Decimal(longAvgDelta)} mg/dl " +
        "Dura ISF minutes: ${decimalFormatter.to2Decimal(duraISFminutes)} m " +
        "Dura ISF average: ${decimalFormatter.to2Decimal(duraISFaverage)} mg/dl " +
        "Parabola minutes: ${decimalFormatter.to2Decimal(parabolaMinutes)} m " +
        "Parabola correlation: ${decimalFormatter.to2Decimal(corrSqu)} " +
        "Parabola fit a0: ${decimalFormatter.to2Decimal(a0)} mg/dl " +
        "a1: ${decimalFormatter.to2Decimal(a1)} mg/dl/5m " +
        "a2: ${decimalFormatter.to2Decimal(a2)} mg/dl/(5m)^2"

fun GlucoseStatusAIMI.asRounded() = copy(
    glucose = Round.roundTo(glucose, 0.1),
    noise = Round.roundTo(noise, 0.01),
    delta = Round.roundTo(delta, 0.01),
    shortAvgDelta = Round.roundTo(shortAvgDelta, 0.01),
    longAvgDelta = Round.roundTo(longAvgDelta, 0.01),
    duraISFminutes = Round.roundTo(duraISFminutes, 0.1),
    duraISFaverage = Round.roundTo(duraISFaverage, 0.1),
    parabolaMinutes = Round.roundTo(parabolaMinutes, 0.1),
    corrSqu = Round.roundTo(corrSqu, 0.0001),
    a0 = Round.roundTo(a0, 0.1),
    a1 = Round.roundTo(a1, 0.01),
    a2 = Round.roundTo(a2, 0.01)
)

/**
 * Copies the 5 % stable band of [features] into the status: the minutes glucose stayed inside the
 * band (`duraISFminutes`) and the average glucose of the band in mg/dL (`duraISFaverage`). Without
 * features both are 0, as before.
 *
 * Every caller of `DetermineBasalaimiSMB2.determine_basal` that builds its own status uses this, so
 * the band means the same thing on Android and on iOS. The models do not see the average: the basal
 * and T3C learners hold their `duraAvg` input at 0 (they were trained with 0 there).
 */
fun GlucoseStatusAIMI.withStableBand(features: AimiBgFeatures?): GlucoseStatusAIMI = copy(
    duraISFminutes = features?.stable5pctMinutes ?: 0.0,
    duraISFaverage = features?.stable5pctAverage ?: 0.0,
)

/**
 * The status `OpenAPSAIMIPlugin` gives to `determine_basal`, built from the calculator status [gs]
 * and its [features].
 *
 * Left at 0 on purpose: `parabolaMinutes`, `a0`, `a1` and `a2`. `parabolaMinutes` would reach
 * `BasalDecisionEngine` (the `BasalPlanner` snapshot and the kicker of `AIMIAdaptiveBasal`), which
 * raises the basal; it stays 0 until those consumers are reviewed. `a0` to `a2` have no consumer
 * other than the log line.
 */
fun aimiTickGlucoseStatus(gs: GlucoseStatusAIMI, features: AimiBgFeatures?): GlucoseStatusAIMI =
    GlucoseStatusAIMI(
        glucose = gs.glucose,
        noise = gs.noise,
        delta = gs.delta,
        shortAvgDelta = gs.shortAvgDelta,
        longAvgDelta = gs.longAvgDelta,
        date = gs.date,
        deltaPl = features?.delta5Prev ?: 0.0,
        deltaPn = features?.delta5Next ?: 0.0,
        bgAcceleration = features?.accel ?: 0.0,
        corrSqu = features?.corrR2 ?: 0.0,
        // Propagate CGM source (G6/G7/One+/…) so AIMI lead stays G6-only
        sourceSensor = gs.sourceSensor,
    ).withStableBand(features)
