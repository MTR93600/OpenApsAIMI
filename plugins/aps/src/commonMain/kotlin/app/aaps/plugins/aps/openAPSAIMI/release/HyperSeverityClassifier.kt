package app.aaps.plugins.aps.openAPSAIMI.release

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.math.max

/**
 * Classifies hyper severity from projection + CGM (see `docs/AIMI_HYPER_TRAJECTORY_RELEASE.md` §4).
 */
object HyperSeverityClassifier {

    data class Input(
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val highBgPreferenceMgdl: Double,
        val deltaMgdlPer5: Double,
        val shortAvgDeltaMgdlPer5: Double,
        val combinedDeltaMgdlPer5: Double,
        val floorTerminalMgdl: Double,
        val bestTerminalMgdl: Double,
        val tdd24hU: Double,
        val dwellAboveHighBgMinutes: Int,
        val trajectoryType: TrajectoryType?,
        val establishedDevOverrideMgdl: Double = 0.0,
        val deepDevOverrideMgdl: Double = 0.0,
        val mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
        val gapPrevMgdl: Double? = null,
    )

    data class Output(
        val tier: HyperSeverityTier,
        val devAboveTargetMgdl: Double,
        val projectedDevMgdl: Double,
        val terminalGapMgdl: Double,
        val highBgBandMgdl: Double,
        val establishedDevMgdl: Double,
        val deepDevMgdl: Double,
        val riseActive: Boolean,
        val projectionHyper: Boolean,
        val bestCredible: Boolean,
        /** Hyper installed (dwell/dev) but projection/Δ quiet — keep correction tier, not OFF/DEEP throttle. */
        val plateauSustain: Boolean,
    )

    fun classify(input: Input): Output {
        val target = input.targetBgMgdl
        val highBand = HyperTrajectoryHypoCredibility.highBgBandMgdl(target, input.highBgPreferenceMgdl)
        val establishedDev = if (input.establishedDevOverrideMgdl > 1.0) {
            input.establishedDevOverrideMgdl
        } else {
            establishedDevMgdl(input.tdd24hU, highBand)
        }
        val deepDev = if (input.deepDevOverrideMgdl > 1.0) {
            input.deepDevOverrideMgdl
        } else {
            deepDevMgdl(input.tdd24hU, highBand)
        }
        val dev = input.bgMgdl - target
        val projectedDev = input.bestTerminalMgdl - target
        val gap = input.bestTerminalMgdl - input.floorTerminalMgdl

        var riseActive = input.deltaMgdlPer5 >= 1.8 ||
            input.shortAvgDeltaMgdlPer5 >= 1.5 ||
            input.combinedDeltaMgdlPer5 >= 3.0
        if (input.mealAbsorptionPhase.forcesHtrRise) {
            riseActive = true
        }

        val gapMin = gapMinMgdl(input.tdd24hU)
        val projectionLeading = input.bestTerminalMgdl >= input.bgMgdl + highBand * 0.15
        val projectionHyper = input.bestTerminalMgdl.isFinite() &&
            input.floorTerminalMgdl.isFinite() &&
            projectionLeading &&
            gap >= gapMin &&
            (
                projectedDev >= highBand * 1.15 ||
                    input.bestTerminalMgdl >= input.bgMgdl + highBand * 0.45
                )

        val bestCredible = input.bestTerminalMgdl.isFinite() &&
            input.bestTerminalMgdl > input.floorTerminalMgdl + 15.0 &&
            input.bestTerminalMgdl <= 450.0

        val strongProjectedRise = projectionHyper && riseActive &&
            input.bestTerminalMgdl >= input.bgMgdl + highBand * 0.45

        val projectionQuiet = !riseActive && !projectionHyper
        val sustainedHyper = dev >= establishedDev || input.dwellAboveHighBgMinutes >= 30
        val gapWidening = input.gapPrevMgdl != null && gap > input.gapPrevMgdl + 8.0
        val suppressPlateau = input.mealAbsorptionPhase.forcesHtrRise ||
            input.mealAbsorptionPhase == MealAbsorptionPhase.PEAK_CORRECTION ||
            (input.mealAbsorptionPhase.isActive && gapWidening)
        val plateauSustain = sustainedHyper && projectionQuiet && !suppressPlateau

        var tier = when {
            plateauSustain -> HyperSeverityTier.ESTABLISHED
            !bestCredible || projectionQuiet -> HyperSeverityTier.OFF
            dev >= deepDev && strongProjectedRise -> HyperSeverityTier.ESTABLISHED
            dev >= deepDev -> HyperSeverityTier.DEEP
            dev >= establishedDev || input.dwellAboveHighBgMinutes >= 30 -> HyperSeverityTier.ESTABLISHED
            dev >= highBand && riseActive -> HyperSeverityTier.EMERGING
            projectionHyper && riseActive -> HyperSeverityTier.ANTICIPATORY
            projectionHyper -> HyperSeverityTier.ANTICIPATORY
            else -> HyperSeverityTier.OFF
        }

        val stackingSpiral = input.trajectoryType == TrajectoryType.TIGHT_SPIRAL &&
            tier.isReleaseEligible &&
            !projectionHyper &&
            dev < establishedDev
        if (stackingSpiral) {
            tier = HyperSeverityTier.OFF
        }

        return Output(
            tier = tier,
            devAboveTargetMgdl = dev,
            projectedDevMgdl = projectedDev,
            terminalGapMgdl = gap,
            highBgBandMgdl = highBand,
            establishedDevMgdl = establishedDev,
            deepDevMgdl = deepDev,
            riseActive = riseActive,
            projectionHyper = projectionHyper,
            bestCredible = bestCredible,
            plateauSustain = plateauSustain && tier == HyperSeverityTier.ESTABLISHED,
        )
    }

    internal fun gapMinMgdl(tdd24hU: Double): Double {
        if (!tdd24hU.isFinite() || tdd24hU <= 0.0) return 35.0
        return (tdd24hU * 0.07).coerceIn(25.0, 45.0)
    }

    internal fun establishedDevMgdl(tdd24hU: Double, highBgBandMgdl: Double): Double {
        val fromTdd = if (tdd24hU.isFinite() && tdd24hU > 0.0) {
            tdd24hU * 0.12
        } else {
            80.0
        }
        return max(highBgBandMgdl * 1.75, fromTdd).coerceIn(65.0, 95.0)
    }

    internal fun deepDevMgdl(tdd24hU: Double, highBgBandMgdl: Double): Double {
        val fromTdd = if (tdd24hU.isFinite() && tdd24hU > 0.0) {
            tdd24hU * 0.18
        } else {
            120.0
        }
        return max(highBgBandMgdl * 2.8, fromTdd).coerceIn(95.0, 140.0)
    }
}
