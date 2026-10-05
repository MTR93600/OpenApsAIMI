package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath

/**
 * Rate text and the rounded basal the engine asks for.
 * `overrideSafety` stays on the Android shell: the existing port omits it, so the default is false.
 */
internal fun decideCalculateRate(
    basal: Double,
    currentBasal: Double,
    multiplier: Double,
    reason: String,
    currentTemp: CurrentTemp,
    rT: RT,
    overrideSafety: Boolean,
): Double {
    rT.reason.append("${currentTemp.duration}m@${aimiFmt2(currentTemp.rate)} $reason")
    val rawRate = if (overrideSafety || basal == 0.0) currentBasal * multiplier else AimiTickPolicyMath.roundBasal(basal * multiplier)
    return rawRate.coerceAtLeast(0.0)
}

/**
 * Drift terminator: sustained plateau above target with weak rise, positive deviation vs IOB prediction, no recent bolus.
 */
internal fun decideDriftTerminatorCondition(
    bg: Float,
    targetBg: Float,
    delta: Float,
    avgDelta: Float,
    combinedDelta: Float,
    minDeviation: Double,
    lastBolusVolume: Double,
    reason: StringBuilder,
): Boolean {
    // 1. Slow Creep (Target + 15)
    if (bg <= targetBg + 15) return false

    // 2. Nature of the Drift: Must be Flat or Rising Slow (CONFIRMED BY 15m AVG & DEVIATION)
    // [FIX] Plateau/Hovering Detection with Deep Analysis:
    // - Instant Delta must be > -1.5 (Not falling)
    // - Avg Delta (15m) must be > -1.5 (Sustained not falling)
    // - Both must be < 6.0 (Not a spike)

    if (delta < -1.5 || avgDelta < -1.5) return false // Falling real (instant or trend)
    if (delta > 6.0 || avgDelta > 6.0) return false // Rising fast (Not a creep)

    // 3. Confirmation by MinDeviation (Are we stuck *worse* than IOB allows?)
    // If deviation is positive, it means BG > IOB prediction -> Resistance/Drift
    // If combinedDelta is also weak (-1 to +2), it confirms the "stuck" nature.
    val isStuck = minDeviation > 0 && combinedDelta > -1.0 && combinedDelta < 3.0

    if (!isStuck) {
        // Fallback: If deviation isn't available/positive, ensure delta is strictly flat
        if (delta < -0.5) return false
    }

    // 4. No recent bolus activity (Clean slate)
    if (lastBolusVolume > 0.1) return false

    reason.append("🧹 Drift Terminator: Plateau detected (Δ${aimiFmt1(delta)} Avg${aimiFmt1(avgDelta)} Dev${aimiFmt0(minDeviation)}) -> ENGAGED\n")
    return true
}

/**
 * Late fat/protein rise. Meal flags stay on the Android shell. `nowMs` is explicit:
 * the shell keeps `dateUtil.now()` as the default.
 */
internal fun decideLateFatProteinRise(
    bg: Double,
    predictedBg: Double,
    delta: Double,
    shortAvgDelta: Double,
    longAvgDelta: Double,
    iob: Double,
    cob: Double,
    maxSMB: Double,
    lastBolusTimeMs: Long?,
    mealTime: Boolean,
    bfastTime: Boolean,
    lunchTime: Boolean,
    dinnerTime: Boolean,
    highCarbTime: Boolean,
    nowMs: Long,
): Boolean {
    val hoursSinceBolus = lastBolusTimeMs?.let { (nowMs - it) / 3_600_000.0 } ?: Double.POSITIVE_INFINITY
    val rising = delta >= 1.0 && (shortAvgDelta >= 0.5 || longAvgDelta >= 0.3)
    val highish = bg > 130 || predictedBg > 140
    val lowIOB = iob < maxSMB
    val noMeal = !(mealTime || bfastTime || lunchTime || dinnerTime || highCarbTime)
    return noMeal && hoursSinceBolus in 2.0..7.0 && rising && highish && lowIOB && cob <= 1.0
}
