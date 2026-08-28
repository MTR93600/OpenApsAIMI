package app.aaps.plugins.aps.openAPSAIMI.activity

import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryHypoCredibility
import kotlin.math.max

/**
 * When sport / AIMI Context activity is active, default policy is hypo-safe (SMB off, basal cut).
 * If glycemia is already high and rising (common: walk + stress + thyroid EGP), allow basal correction
 * instead of stacking a hyper from under-dosing.
 */
object ExerciseHyperOverridePolicy {

    data class Input(
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val highBgPreferenceMgdl: Double,
        val deltaMgdlPer5: Double,
        val shortAvgDeltaMgdlPer5: Double,
        val combinedDeltaMgdlPer5: Double,
        /** Thyroid EGP multiplier from `ThyroidEffectModel` (1.0 = euthyroid). */
        val thyroidEgpMultiplier: Double = 1.0,
    )

    fun isHyperRisingDuringExercise(input: Input): Boolean {
        val highBand = HyperTrajectoryHypoCredibility.highBgBandMgdl(
            input.targetBgMgdl,
            input.highBgPreferenceMgdl,
        )
        val dev = input.bgMgdl - input.targetBgMgdl
        val rising = input.deltaMgdlPer5 >= 1.8 ||
            input.combinedDeltaMgdlPer5 >= 2.2 ||
            input.shortAvgDeltaMgdlPer5 >= 2.0
        if (!rising) return false

        val clearlyHyper = input.bgMgdl >= input.highBgPreferenceMgdl * 0.92 ||
            dev >= highBand * 0.55
        val strongRise = input.deltaMgdlPer5 >= 3.0 ||
            input.combinedDeltaMgdlPer5 >= 3.0
        val thyroidHyperBias = input.thyroidEgpMultiplier >= 1.10 &&
            dev >= highBand * 0.35 &&
            input.bgMgdl >= input.targetBgMgdl + 25.0

        return clearlyHyper || strongRise || thyroidHyperBias
    }

    /**
     * Replaces activity basal reduction (0.6–0.8) with at least nominal basal, slight boost if intense rise.
     */
    fun resolveBasalFactor(activityBasalFactor: Float, overrideActive: Boolean, strongRise: Boolean): Float {
        if (!overrideActive) return activityBasalFactor
        val floor = if (strongRise) 1.10f else 1.02f
        return max(activityBasalFactor, floor).coerceAtMost(1.28f)
    }

    fun buildInput(
        bgMgdl: Double,
        targetBgMgdl: Double,
        highBgPreferenceMgdl: Double,
        deltaMgdlPer5: Double,
        shortAvgDeltaMgdlPer5: Double,
        combinedDeltaMgdlPer5: Double,
        thyroidEgpMultiplier: Double,
    ): Input = Input(
        bgMgdl = bgMgdl,
        targetBgMgdl = targetBgMgdl,
        highBgPreferenceMgdl = highBgPreferenceMgdl,
        deltaMgdlPer5 = deltaMgdlPer5,
        shortAvgDeltaMgdlPer5 = shortAvgDeltaMgdlPer5,
        combinedDeltaMgdlPer5 = combinedDeltaMgdlPer5,
        thyroidEgpMultiplier = thyroidEgpMultiplier,
    )
}
