package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType

/**
 * Android reads of the tight-spiral bridge.
 * Each method is the call that already existed at that line.
 * Hyper classification and the SMB cap stay Android.
 */
internal interface AimiTrajectorySpiralCalls {
    fun lastAnalysis(): TrajectoryAnalysis?
    fun shortAvgDelta(): Float
    fun maxIob(): Double
    fun uamConfidence(): Double
    fun hyperTier(rT: RT, combinedDelta: Float, tdd24hU: Double): HyperSeverityTier
    fun setPending(rateUph: Double, durationMin: Int, reason: String, tierLabel: String)
    fun applySmbCap(
        energy: Double,
        iobNow: Double,
        tdd24hU: Double,
        deltaValue: Float,
        shortAvgDeltaValue: Float,
        mealData: MealData,
        isExplicitUserAction: Boolean,
        mealClockActiveForSpiralRelax: Boolean,
    )
}

/**
 * Strict mirror of the SMB meal-priority context used by the spiral cap.
 * A strong sustained unannounced rise counts, with the same floors (bg, delta, IOB headroom).
 */
internal fun mealPriorityAlignedForSpiralSmbCap(
    bgValue: Double,
    deltaValue: Float,
    shortAvgDeltaValue: Float,
    mealData: MealData,
    iobNow: Double,
    maxIobValue: Double,
    isExplicitUserAction: Boolean,
    mealClockActiveForSpiralRelax: Boolean,
    uamConfidence: Double,
): Boolean {
    if (isExplicitUserAction) return false
    val strongConfirmedRise = deltaValue >= 8.0f && shortAvgDeltaValue >= 5.0f
    if (!(mealClockActiveForSpiralRelax || mealData.mealCOB >= 6.0 || uamConfidence >= 0.45 || strongConfirmedRise)) {
        return false
    }
    if (bgValue < 145.0) return false
    if (deltaValue < 1.8f && shortAvgDeltaValue < 1.5f) return false
    if (!maxIobValue.isFinite() || maxIobValue <= 0.0) return false
    if (iobNow >= maxIobValue * 0.75) return false
    return true
}

internal fun decideTrajectoryTightSpiralSafetyBridge(
    profile: OapsProfileAimi,
    rT: RT,
    iobNow: Double,
    bg: Double,
    delta: Float,
    physioMultipliers: PhysioMultipliersMTR,
    tdd24Hrs: Float,
    mealData: MealData,
    isExplicitUserAction: Boolean,
    mealClockActiveForSpiralRelax: Boolean,
    consoleLog: MutableList<String>,
    calls: AimiTrajectorySpiralCalls,
) {
    val lastTraj = calls.lastAnalysis()
    if (lastTraj == null || lastTraj.classification != TrajectoryType.TIGHT_SPIRAL) {
        return
    }

    val energy = lastTraj.metrics.energyBalance
    val curvature = lastTraj.metrics.curvature

    val mealPriorityAlign = mealPriorityAlignedForSpiralSmbCap(
        bgValue = bg,
        deltaValue = delta,
        shortAvgDeltaValue = calls.shortAvgDelta(),
        mealData = mealData,
        iobNow = iobNow,
        maxIobValue = calls.maxIob(),
        isExplicitUserAction = isExplicitUserAction,
        mealClockActiveForSpiralRelax = mealClockActiveForSpiralRelax,
        uamConfidence = calls.uamConfidence(),
    )
    val tdd24Bridge = if (tdd24Hrs.isFinite() && tdd24Hrs > 0f) tdd24Hrs.toDouble() else 50.0
    val bridgeCombinedDelta = (delta + calls.shortAvgDelta()) / 2f
    val bridgeHyperTier = calls.hyperTier(rT, bridgeCombinedDelta, tdd24Bridge)
    val hyperTrajectorySpiral = bridgeHyperTier >= HyperSeverityTier.EMERGING
    val stackingSpiral = !hyperTrajectorySpiral
    val spiralMealAlign = mealPriorityAlign || hyperTrajectorySpiral

    val basalFraction = when {
        spiralMealAlign && energy > 3.5 -> 0.70
        spiralMealAlign && energy > 2.5 -> 0.85
        spiralMealAlign && energy > 1.5 -> 0.95
        stackingSpiral && energy > 3.5 -> 0.25
        stackingSpiral && energy > 2.5 -> 0.50
        stackingSpiral && energy > 1.5 -> 0.70
        else -> 1.0
    }

    val cgateAmplified = physioMultipliers.isfFactor > 1.05
    val effectiveFraction = if (cgateAmplified && basalFraction > 0.25) {
        (basalFraction - 0.20).coerceAtLeast(0.25)
    } else {
        basalFraction
    }

    if (effectiveFraction >= 1.0) return

    val proactiveBasal = profile.current_basal * effectiveFraction
    val cgateNote = if (cgateAmplified) " [CGate ISF↑ → amplification]" else ""
    val spiralNote = when {
        hyperTrajectorySpiral -> " [HTR_HYPER_SPIRAL tier=${bridgeHyperTier.name}]"
        mealPriorityAlign -> " [MEAL_PRIORITY_RELAX]"
        stackingSpiral -> " [STACKING_SPIRAL]"
        else -> ""
    }
    val reason = "TRAJ_TIGHT_SPIRAL: E=${aimiFmt1(energy)}U κ=${aimiFmt2(curvature)} IOB=${aimiFmt2(iobNow)}U → Basale proactive ${(effectiveFraction * 100).toInt()}%$cgateNote$spiralNote"

    consoleLog.add("🌀🛡️ TRAJECTORY_SAFETY_BRIDGE (deferred): $reason")

    calls.setPending(
        rateUph = proactiveBasal,
        durationMin = if (energy > 3.5) 30 else 15,
        reason = reason,
        tierLabel = "TrajBridge_Tier${when { energy > 3.5 -> 1; energy > 2.5 -> 2; else -> 3 }}",
    )
    calls.applySmbCap(
        energy = energy,
        iobNow = iobNow,
        tdd24hU = tdd24Hrs.toDouble(),
        deltaValue = delta,
        shortAvgDeltaValue = calls.shortAvgDelta(),
        mealData = mealData,
        isExplicitUserAction = isExplicitUserAction,
        mealClockActiveForSpiralRelax = mealClockActiveForSpiralRelax,
    )
}
