package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier

/**
 * Dosing constraints derived from [PhysiologicalPhase] (behavioral risk envelope).
 */
data class BehavioralRiskPolicy(
    val phase: PhysiologicalPhase,
    val confidence: Double,
    val reason: String,
    val maxHtrTier: HyperSeverityTier,
    val smbFloorCapU: Double,
    val capScenarioBestAboveBgMgdl: Double?,
    val extendedDawnGuard: Boolean,
    val mpcInsulinCostMultiplier: Double,
    val mpcMaxSmbFraction: Double,
    val suppressMealLikeScenario: Boolean,
) {
    fun capsHtrRelease(): Boolean =
        phase.isHormonalRisk ||
            phase == PhysiologicalPhase.STRESS_CORTISOL ||
            phase.isEndogenousRisk

    companion object {
        fun effectiveForHtr(
            physiologicalPhase: PhysiologicalPhase,
            mealAbsorptionPhase: MealAbsorptionPhase,
            confidence: Double,
            reason: String,
        ): BehavioralRiskPolicy {
            if (mealAbsorptionPhase.usesMealHtrPolicy &&
                physiologicalPhase == PhysiologicalPhase.STRESS_CORTISOL
            ) {
                return forPhase(
                    PhysiologicalPhase.MEAL_UNDECLARED,
                    confidence,
                    "meal absorption overrides stress: $reason",
                )
            }
            return forPhase(physiologicalPhase, confidence, reason)
        }

        fun forPhase(phase: PhysiologicalPhase, confidence: Double, reason: String): BehavioralRiskPolicy =
            when (phase) {
                PhysiologicalPhase.OFF -> BehavioralRiskPolicy(
                    phase = phase,
                    confidence = confidence,
                    reason = reason,
                    maxHtrTier = HyperSeverityTier.DEEP,
                    smbFloorCapU = Double.POSITIVE_INFINITY,
                    capScenarioBestAboveBgMgdl = null,
                    extendedDawnGuard = false,
                    mpcInsulinCostMultiplier = 1.0,
                    mpcMaxSmbFraction = 1.0,
                    suppressMealLikeScenario = false,
                )
                PhysiologicalPhase.DAWN_CORTISOL,
                PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL,
                PhysiologicalPhase.FEMALE_CYCLE_HORMONAL,
                -> BehavioralRiskPolicy(
                    phase = phase,
                    confidence = confidence,
                    reason = reason,
                    maxHtrTier = HyperSeverityTier.OFF,
                    smbFloorCapU = 0.55,
                    capScenarioBestAboveBgMgdl = 50.0,
                    extendedDawnGuard = true,
                    mpcInsulinCostMultiplier = 4.0,
                    mpcMaxSmbFraction = 0.45,
                    suppressMealLikeScenario = true,
                )
                PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY -> BehavioralRiskPolicy(
                    phase = phase,
                    confidence = confidence,
                    reason = reason,
                    maxHtrTier = HyperSeverityTier.OFF,
                    smbFloorCapU = 0.30,
                    capScenarioBestAboveBgMgdl = 45.0,
                    extendedDawnGuard = true,
                    mpcInsulinCostMultiplier = 3.0,
                    mpcMaxSmbFraction = 0.25,
                    suppressMealLikeScenario = true,
                )
                PhysiologicalPhase.STRESS_CORTISOL -> BehavioralRiskPolicy(
                    phase = phase,
                    confidence = confidence,
                    reason = reason,
                    maxHtrTier = HyperSeverityTier.EMERGING,
                    smbFloorCapU = 0.75,
                    capScenarioBestAboveBgMgdl = 70.0,
                    extendedDawnGuard = false,
                    mpcInsulinCostMultiplier = 2.5,
                    mpcMaxSmbFraction = 0.65,
                    suppressMealLikeScenario = false,
                )
                PhysiologicalPhase.MEAL_DECLARED -> BehavioralRiskPolicy(
                    phase = phase,
                    confidence = confidence,
                    reason = reason,
                    maxHtrTier = HyperSeverityTier.DEEP,
                    smbFloorCapU = Double.POSITIVE_INFINITY,
                    capScenarioBestAboveBgMgdl = null,
                    extendedDawnGuard = false,
                    mpcInsulinCostMultiplier = 1.0,
                    mpcMaxSmbFraction = 1.0,
                    suppressMealLikeScenario = false,
                )
                PhysiologicalPhase.MEAL_UNDECLARED -> BehavioralRiskPolicy(
                    phase = phase,
                    confidence = confidence,
                    reason = reason,
                    maxHtrTier = HyperSeverityTier.DEEP,
                    smbFloorCapU = Double.POSITIVE_INFINITY,
                    capScenarioBestAboveBgMgdl = null,
                    extendedDawnGuard = false,
                    mpcInsulinCostMultiplier = 1.0,
                    mpcMaxSmbFraction = 1.0,
                    suppressMealLikeScenario = false,
                )
                PhysiologicalPhase.HYPER_INSTALLED -> BehavioralRiskPolicy(
                    phase = phase,
                    confidence = confidence,
                    reason = reason,
                    maxHtrTier = HyperSeverityTier.DEEP,
                    smbFloorCapU = Double.POSITIVE_INFINITY,
                    capScenarioBestAboveBgMgdl = null,
                    extendedDawnGuard = false,
                    mpcInsulinCostMultiplier = 1.0,
                    mpcMaxSmbFraction = 1.0,
                    suppressMealLikeScenario = false,
                )
            }
    }
}
