package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityClassifier
import kotlin.math.min

/**
 * Detects sustained hepatic glucose / cortisol ramps (COB=0) that must not be treated as meal UAM.
 * See docs/AIMI_PHYSIOLOGICAL_PHASE.md § ENDOGENOUS_COUNTER_REGULATORY.
 */
object EndogenousCounterRegulatoryDetector {

    /** Dawn + late-morning window where counter-regulatory ramps are common. */
    const val HOUR_START = 4
    const val HOUR_END = 11

    fun isEndogenousRamp(
        input: PhysiologicalPhaseClassifier.Input,
        highBandMgdl: Double,
        devAboveTargetMgdl: Double,
        projectionLeadMgdl: Double,
    ): Boolean {
        if (input.mealCobG >= 1.0) return false
        if (input.hourOfDay !in HOUR_START..HOUR_END) return false
        if (input.deltaMgdlPer5 < 1.0) return false
        if (input.deltaMgdlPer5 >= 4.0 || input.combinedDeltaMgdlPer5 >= 4.5) return false
        if (devAboveTargetMgdl >= highBandMgdl * 1.15) return false
        if (projectionLeadMgdl < highBandMgdl * 0.72) return false

        val moderateRise = input.deltaMgdlPer5 >= 1.5 ||
            input.shortAvgDeltaMgdlPer5 >= 1.2 ||
            input.combinedDeltaMgdlPer5 >= 2.0
        if (!moderateRise) return false

        val mealBestT = capBestTerminalForMealDiscriminant(input, highBandMgdl, devAboveTargetMgdl)
        val mealLead = mealBestT - input.bgMgdl
        val uamArtifactInflation = projectionLeadMgdl >= highBandMgdl * 0.55 &&
            mealLead < highBandMgdl * 0.85
        if (!uamArtifactInflation) {
            if (PhysiologicalPhaseClassifier.isMealLikeRise(input, highBandMgdl, mealLead, mealBestT)) return false
            if (PhysiologicalPhaseClassifier.isMealDominantRise(input, highBandMgdl, mealLead, mealBestT)) return false
        }
        if (!uamArtifactInflation &&
            input.hourOfDay in 4..10 &&
            PhysiologicalPhaseClassifier.isAcuteMealSurgeAtDawn(
                input,
                highBandMgdl,
                devAboveTargetMgdl,
                projectionLeadMgdl,
            )
        ) {
            return false
        }

        val gap = input.bestTerminalMgdl - input.floorTerminalMgdl
        val gapMin = HyperSeverityClassifier.gapMinMgdl(55.0)
        val uamInflatedWithoutMealGap = projectionLeadMgdl >= highBandMgdl * 0.55 && gap < gapMin
        val projectionNotMealShaped = projectionLeadMgdl <= highBandMgdl * 1.35 ||
            input.bestTerminalMgdl <= input.bgMgdl + 55.0

        return uamArtifactInflation || uamInflatedWithoutMealGap || projectionNotMealShaped
    }

    /**
     * Caps scenario terminal used only for meal-vs-endogenous discrimination when UAM inflates bestT.
     */
    fun capBestTerminalForMealDiscriminant(
        input: PhysiologicalPhaseClassifier.Input,
        highBandMgdl: Double,
        devAboveTargetMgdl: Double,
    ): Double {
        if (input.hourOfDay !in HOUR_START..HOUR_END || input.mealCobG >= 1.0) {
            return input.bestTerminalMgdl
        }
        if (devAboveTargetMgdl >= highBandMgdl * 0.55) {
            return input.bestTerminalMgdl
        }
        val capAbove = min(highBandMgdl * 0.65, 55.0)
        return min(input.bestTerminalMgdl, input.bgMgdl + capAbove)
    }
}

