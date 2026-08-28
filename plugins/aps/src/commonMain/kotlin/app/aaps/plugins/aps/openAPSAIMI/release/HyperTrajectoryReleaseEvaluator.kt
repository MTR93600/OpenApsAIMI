package app.aaps.plugins.aps.openAPSAIMI.release

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.math.max
import kotlin.math.min

/**
 * Hyper Trajectory Release (HTR): lifts Autodrive V3 SMB when scenario + trajectory confirm hyper rise.
 */
object HyperTrajectoryReleaseEvaluator {

    data class Input(
        val enabled: Boolean,
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val highBgPreferenceMgdl: Double,
        val deltaMgdlPer5: Double,
        val shortAvgDeltaMgdlPer5: Double,
        val combinedDeltaMgdlPer5: Double,
        val floorTerminalMgdl: Double,
        val bestTerminalMgdl: Double,
        val tdd24hU: Double,
        val iobU: Double,
        val maxIobU: Double,
        val maxSmbEffectiveU: Double,
        val v3SmbU: Double,
        val dwellAboveHighBgMinutes: Int,
        val trajectoryType: TrajectoryType?,
        val minPredictedBgMgdl: Double?,
        val aggressive: Boolean = false,
        val isNight: Boolean = false,
        val exerciseLockout: Boolean = false,
        val mealCobG: Double = 0.0,
        val establishedDevOverrideMgdl: Double = 0.0,
        val deepDevOverrideMgdl: Double = 0.0,
        val behavioralRisk: BehavioralRiskPolicy? = null,
        val mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        val gapPrevMgdl: Double? = null,
    )

    fun evaluate(input: Input): HyperTrajectoryReleaseResult {
        val classification = HyperSeverityClassifier.classify(
            HyperSeverityClassifier.Input(
                bgMgdl = input.bgMgdl,
                targetBgMgdl = input.targetBgMgdl,
                highBgPreferenceMgdl = input.highBgPreferenceMgdl,
                deltaMgdlPer5 = input.deltaMgdlPer5,
                shortAvgDeltaMgdlPer5 = input.shortAvgDeltaMgdlPer5,
                combinedDeltaMgdlPer5 = input.combinedDeltaMgdlPer5,
                floorTerminalMgdl = input.floorTerminalMgdl,
                bestTerminalMgdl = input.bestTerminalMgdl,
                tdd24hU = input.tdd24hU,
                dwellAboveHighBgMinutes = input.dwellAboveHighBgMinutes,
                trajectoryType = input.trajectoryType,
                establishedDevOverrideMgdl = input.establishedDevOverrideMgdl,
                deepDevOverrideMgdl = input.deepDevOverrideMgdl,
                mealAbsorptionPhase = input.mealAbsorptionPhase,
                gapPrevMgdl = input.gapPrevMgdl,
            ),
        )

        val hypoMinPredIgnored = !HyperTrajectoryHypoCredibility.isMinPredictedCredible(
            bgMgdl = input.bgMgdl,
            minPredictedBgMgdl = input.minPredictedBgMgdl,
            targetBgMgdl = input.targetBgMgdl,
            highBgPreferenceMgdl = input.highBgPreferenceMgdl,
            tier = classification.tier,
            deltaMgdlPer5 = input.deltaMgdlPer5,
        )

        val risk = input.behavioralRisk
        var effectiveTier = classification.tier
        if (risk != null && risk.capsHtrRelease()) {
            effectiveTier = PhysiologicalPhaseClassifier.capHtrTier(effectiveTier, risk.maxHtrTier)
        }

        if (!input.enabled || input.isNight || input.exerciseLockout || !effectiveTier.isReleaseEligible) {
            val offReason = when {
                !input.enabled -> "disabled"
                input.isNight -> "night"
                input.exerciseLockout -> "exercise"
                risk?.capsHtrRelease() == true && !classification.tier.isReleaseEligible -> "tier"
                risk?.capsHtrRelease() == true -> "physioRisk"
                else -> "tier"
            }
            return inactive(
                classification.copyTier(effectiveTier),
                input.v3SmbU,
                hypoMinPredIgnored,
                offReason,
                risk,
            )
        }

        val tierWeight = tierWeight(
            effectiveTier,
            input.deltaMgdlPer5,
            input.shortAvgDeltaMgdlPer5,
            classification.plateauSustain,
        )
        val mealRising = input.mealAbsorptionPhase.forcesHtrRise && input.deltaMgdlPer5 > 0.0
        val absorptionOffset = absorptionOffsetMgdl(
            effectiveTier,
            classification.plateauSustain && !mealRising,
        )
        val smbBaseU = smbBaseU(input.tdd24hU)
        val projectionLead = max(0.0, input.bestTerminalMgdl - input.bgMgdl)
        var projectionFactor = (0.35 + projectionLead / 55.0).coerceIn(0.65, 1.55)
        if (classification.plateauSustain) {
            projectionFactor = max(projectionFactor, plateauProjectionFactorFloor())
        }
        val riseFactor = riseUrgencyFactor(input.deltaMgdlPer5, input.shortAvgDeltaMgdlPer5)

        var smbFloorU = smbBaseU * tierWeight * projectionFactor * riseFactor
        smbFloorU *= absorptionDoseFactor(effectiveTier, classification.plateauSustain && !mealRising)
        if (classification.plateauSustain) {
            smbFloorU *= plateauDwellUrgencyFactor(input.dwellAboveHighBgMinutes)
            smbFloorU = max(
                smbFloorU,
                smbBaseU * plateauMinFloorFraction(
                    devAboveTargetMgdl = classification.devAboveTargetMgdl,
                    deepDevMgdl = classification.deepDevMgdl,
                ),
            )
        }
        if (input.aggressive && !classification.plateauSustain) {
            smbFloorU *= 1.15
        } else if (input.aggressive && classification.plateauSustain) {
            smbFloorU *= 1.08
        }
        if (input.mealCobG >= 15.0) {
            smbFloorU *= 0.88
        }
        if (risk != null && risk.smbFloorCapU.isFinite()) {
            smbFloorU = min(smbFloorU, risk.smbFloorCapU)
        }
        if (input.mealAbsorptionPhase == MealAbsorptionPhase.SECOND_WAVE && input.deltaMgdlPer5 > 0.0) {
            smbFloorU = max(smbFloorU, 1.5)
        }
        if (input.mealAbsorptionPhase == MealAbsorptionPhase.FIRST_WAVE && input.deltaMgdlPer5 >= 2.5) {
            smbFloorU = max(smbFloorU, 1.2)
        }
        val iobHeadroom = max(0.0, input.maxIobU - input.iobU)
        smbFloorU = minOf(smbFloorU, input.maxSmbEffectiveU.coerceAtLeast(0.0), iobHeadroom)

        val v3Before = input.v3SmbU.coerceAtLeast(0.0)
        val v3After = max(v3Before, smbFloorU)
        val suppressTraj = v3Before < smbFloorU * 0.5 && effectiveTier.isReleaseEligible

        val reason = buildString {
            append("HTR[${effectiveTier.name}] ")
            append("dev=+${classification.devAboveTargetMgdl.toInt()} ")
            append("proj=+${classification.projectedDevMgdl.toInt()} ")
            append("gap=${classification.terminalGapMgdl.toInt()} ")
            append("floor=${aimiFmt2(smbFloorU)}U ")
            append("v3 ${aimiFmt2(v3Before)}→${aimiFmt2(v3After)}U")
            if (absorptionOffset > 0.0) append(" absOff=${absorptionOffset.toInt()}")
            if (classification.plateauSustain) append(" plateauSustain")
            if (risk != null && risk.capsHtrRelease()) {
                append(" physio=${risk.phase.name}")
            }
            if (hypoMinPredIgnored) append(" minPredIgnored")
            if (suppressTraj) append(" suppressTrajBridge")
        }

        return HyperTrajectoryReleaseResult(
            active = smbFloorU > v3Before + 0.02,
            tier = effectiveTier,
            severityWeight = tierWeight,
            smbFloorU = smbFloorU,
            v3SmbBeforeU = v3Before,
            v3SmbAfterU = v3After,
            absorptionOffsetMgdl = absorptionOffset,
            suppressTrajBasalShift = suppressTraj,
            hypoMinPredIgnored = hypoMinPredIgnored,
            reason = reason.trim(),
        )
    }

    private fun inactive(
        classification: HyperSeverityClassifier.Output,
        v3SmbU: Double,
        hypoMinPredIgnored: Boolean,
        offReason: String = "tier",
        risk: BehavioralRiskPolicy? = null,
    ): HyperTrajectoryReleaseResult {
        val tier = classification.tier
        val reasonSuffix = if (risk != null && risk.capsHtrRelease()) {
            " tier=${tier.name} physio=${risk.phase.name}"
        } else {
            " tier=${tier.name}"
        }
        return HyperTrajectoryReleaseResult(
            active = false,
            tier = tier,
            severityWeight = 0.0,
            smbFloorU = 0.0,
            v3SmbBeforeU = v3SmbU,
            v3SmbAfterU = v3SmbU,
            absorptionOffsetMgdl = 0.0,
            suppressTrajBasalShift = false,
            hypoMinPredIgnored = hypoMinPredIgnored,
            reason = "HTR off ($offReason)$reasonSuffix",
        )
    }

    private fun HyperSeverityClassifier.Output.copyTier(tier: HyperSeverityTier): HyperSeverityClassifier.Output =
        copy(tier = tier, plateauSustain = plateauSustain && tier == HyperSeverityTier.ESTABLISHED)

    internal fun absorptionDoseFactor(tier: HyperSeverityTier, plateauSustain: Boolean = false): Double =
        when {
            plateauSustain -> 1.0
            tier == HyperSeverityTier.DEEP -> 0.88
            tier == HyperSeverityTier.ESTABLISHED -> 0.94
            else -> 1.0
        }

    internal fun plateauProjectionFactorFloor(): Double = 0.90

    internal fun plateauMinFloorFraction(devAboveTargetMgdl: Double, deepDevMgdl: Double): Double =
        if (devAboveTargetMgdl >= deepDevMgdl) 0.72 else 0.65

    internal fun plateauDwellUrgencyFactor(dwellAboveHighBgMinutes: Int): Double =
        when {
            dwellAboveHighBgMinutes >= 90 -> 1.12
            dwellAboveHighBgMinutes >= 45 -> 1.06
            else -> 1.0
        }

    internal fun smbBaseU(tdd24hU: Double): Double {
        if (!tdd24hU.isFinite() || tdd24hU <= 0.0) return 0.85
        return (tdd24hU * 0.015).coerceIn(0.70, 1.40)
    }

    internal fun tierWeight(
        tier: HyperSeverityTier,
        delta: Double,
        shortAvg: Double,
        plateauSustain: Boolean = false,
    ): Double {
        val base = when (tier) {
            HyperSeverityTier.ANTICIPATORY -> 1.0
            HyperSeverityTier.EMERGING -> 1.1
            HyperSeverityTier.ESTABLISHED -> if (plateauSustain) 1.20 else 1.25
            HyperSeverityTier.DEEP -> if (delta < 2.5 && shortAvg < 2.5) 0.75 else 1.0
            HyperSeverityTier.OFF -> 0.0
        }
        return base * riseUrgencyFactor(delta, shortAvg).coerceAtMost(1.35)
    }

    internal fun riseUrgencyFactor(delta: Double, shortAvg: Double): Double =
        when {
            delta >= 4.5 || shortAvg >= 4.5 -> 1.25
            delta >= 1.8 || shortAvg >= 1.5 -> 1.1
            else -> 1.0
        }

    internal fun absorptionOffsetMgdl(tier: HyperSeverityTier, plateauSustain: Boolean = false): Double =
        when {
            plateauSustain -> 8.0
            tier == HyperSeverityTier.ESTABLISHED -> 15.0
            tier == HyperSeverityTier.DEEP -> 25.0
            else -> 0.0
        }
}
