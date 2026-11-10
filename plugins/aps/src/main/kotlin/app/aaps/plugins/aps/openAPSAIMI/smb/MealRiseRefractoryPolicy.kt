package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.RiseConfirmation
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import kotlin.math.max
import kotlin.math.min

/**
 * Prevents a confirmed undeclared-meal rise from being quantized into the same tiny SMB forever.
 * This can only relax the minor refractory multiplier; upstream hypo/prediction caps, max SMB,
 * max IOB and the pump interval remain authoritative.
 */
object MealRiseRefractoryPolicy {
    data class Decision(val factor: Double, val active: Boolean, val reason: String)

    fun factor(
        progressiveFactor: Double,
        mealPriority: Boolean,
        phase: MealAbsorptionPhase?,
        riseLevel: RiseConfirmation.Level?,
        bgMgdl: Double,
        targetMgdl: Double,
        deltaMgdl5m: Double,
        shortAvgDeltaMgdl5m: Double,
        minPredMgdl: Double?,
        iobU: Double,
        maxIobU: Double,
    ): Decision {
        val base = progressiveFactor.coerceIn(0.0, 1.0)
        val mealRisePhase = phase == MealAbsorptionPhase.FIRST_WAVE ||
            phase == MealAbsorptionPhase.SECOND_WAVE ||
            phase == MealAbsorptionPhase.LATE_FAT
        if (!mealPriority || !mealRisePhase) return Decision(base, false, "not_meal_rise")
        if (riseLevel != RiseConfirmation.Level.CONFIRMED) return Decision(base, false, "rise_not_confirmed")
        if (bgMgdl < max(100.0, targetMgdl + 12.0)) return Decision(base, false, "bg_not_clear")
        if (deltaMgdl5m < 8.0 || shortAvgDeltaMgdl5m < 6.0) return Decision(base, false, "rise_not_fast")
        if (minPredMgdl == null || !minPredMgdl.isFinite() || minPredMgdl < targetMgdl + 12.0) {
            return Decision(base, false, "prediction_not_safe")
        }
        val iobCeiling = min(1.0, (maxIobU * 0.20).coerceAtLeast(0.0))
        if (!iobU.isFinite() || iobU > iobCeiling) return Decision(base, false, "iob_not_low")

        val riseSignal = min(deltaMgdl5m, shortAvgDeltaMgdl5m)
        val riseScaledFloor = (0.65 + ((riseSignal - 8.0) / 8.0) * 0.25).coerceIn(0.65, 0.90)
        return Decision(max(base, riseScaledFloor), true, "confirmed_fast_meal_rise")
    }
}
