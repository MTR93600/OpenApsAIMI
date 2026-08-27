package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.advancedFilteringSupported
import app.aaps.plugins.aps.openAPSAIMI.inflammatory.InflammationAdjuster
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate

/**
 * Shared latent physiological state reused across Autodrive, RBT, replay export and PKPD-oriented flows.
 *
 * The goal is not to replace the deterministic modules; it is to provide one compact causal summary
 * of the current body-state interpretation for the tick.
 */
data class PhysioLatentState(
    val mealProb: Double = 0.0,
    val endogenousGlucoseDrive: Double = 0.0,
    val circadianSiFactor: Double = 1.0,
    val transientResistanceProb: Double = 0.0,
    val sleepDebtScore: Double = 0.0,
    val sensorConfidence: Double = 0.0,
    val autonomicStress: Double = 0.0,
    val inflammationRecovery: Double = 0.0,
    val hormonalCircadian: Double = 0.0,
    val postHypoReboundProb: Double = 0.0,
    val falseMealSuppression: Boolean = false,
    val source: String = "latent_v1",
) {
    fun toAttentionMask(): DoubleArray = doubleArrayOf(
        autonomicStress,
        inflammationRecovery,
        hormonalCircadian,
    )

    fun isActive(threshold: Double = 0.05): Boolean =
        mealProb >= threshold ||
            endogenousGlucoseDrive >= threshold ||
            transientResistanceProb >= threshold ||
            sleepDebtScore >= threshold ||
            autonomicStress >= threshold ||
            inflammationRecovery >= threshold ||
            hormonalCircadian >= threshold ||
            postHypoReboundProb >= threshold ||
            falseMealSuppression

    fun toJsonObject(): JsonObject = buildJsonObject {
        put("meal_prob", mealProb)
        put("endogenous_glucose_drive", endogenousGlucoseDrive)
        put("circadian_si_factor", circadianSiFactor)
        put("transient_resistance_prob", transientResistanceProb)
        put("sleep_debt_score", sleepDebtScore)
        put("sensor_confidence", sensorConfidence)
        put("autonomic_stress", autonomicStress)
        put("inflammation_recovery", inflammationRecovery)
        put("hormonal_circadian", hormonalCircadian)
        put("post_hypo_rebound_prob", postHypoReboundProb)
        put("false_meal_suppression", falseMealSuppression)
        put("source", source)
    }

    fun toDebugString(): String =
        "meal=${fmt(mealProb)} endo=${fmt(endogenousGlucoseDrive)} siCirc=${fmt(circadianSiFactor)} " +
            "resist=${fmt(transientResistanceProb)} sleep=${fmt(sleepDebtScore)} sensor=${fmt(sensorConfidence)}"

    fun toAttentionDebugString(): String =
        "auto=${fmt(autonomicStress)} inflam=${fmt(inflammationRecovery)} hormonal=${fmt(hormonalCircadian)}"

    private fun fmt(value: Double): String = aimiFmt2(value)
}

internal object PhysioLatentStateBuilder {

    fun build(
        snapshot: HealthContextSnapshot,
        sourceSensor: SourceSensor?,
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        physioContext: PhysioContextMTR?,
        physioTrace: PhysioDecisionTraceMTR?,
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
        chronicInflammation: InflammationAdjuster.InflammationResult?,
        autonomicStress: Double,
        inflammationRecovery: Double,
        hormonalCircadian: Double,
        cgmFirstSensorConfidence: Boolean = false,
    ): PhysioLatentState {
        val mealSignal = buildMealProbability(
            phaseOutput = phaseOutput,
            mealAbsorptionOutput = mealAbsorptionOutput,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
        )
        val postHypoReboundProb = buildPostHypoProbability(
            correctionAggressionDecision = correctionAggressionDecision,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
        )
        val endogenousDrive = buildEndogenousDrive(
            phaseOutput = phaseOutput,
            hypothesisState = hypothesisState,
            patternSnapshot = patternSnapshot,
            hormonalCircadian = hormonalCircadian,
        )
        val sleepDebtScore = buildSleepDebtScore(
            snapshot = snapshot,
            physioContext = physioContext,
            patternSnapshot = patternSnapshot,
        )
        val thermalInflammation = snapshot.thermalBelief.inflammationIndex
        val thermalRecovery = snapshot.thermalBelief.recoveryBurden
        val inflammationRecoveryMerged = combineSignals(
            inflammationRecovery,
            thermalInflammation * 0.55,
            thermalRecovery * 0.35,
        )
        val transientResistanceProb = combineSignals(
            autonomicStress * 0.70,
            inflammationRecoveryMerged,
            hormonalCircadian * 0.75,
            thermalInflammation * 0.40,
            (hypothesisState?.stressProb ?: 0.0) * 0.45,
            if (physioContext?.state == PhysioStateMTR.STRESS_DETECTED) 0.45 else 0.0,
            if (physioContext?.state == PhysioStateMTR.INFECTION_RISK) 0.55 else 0.0,
            (physioTrace?.inflammationLatentIndex ?: 0.0) * 0.40,
        )
        val policyResistance = ((phaseOutput?.policy?.mpcInsulinCostMultiplier ?: 1.0) - 1.0)
            .div(3.0)
            .coerceIn(0.0, 1.0)
        val circadianSiFactor = (
            1.0 - combineSignals(
                hormonalCircadian,
                endogenousDrive * 0.85,
                policyResistance,
            ) * 0.24
            ).coerceIn(0.76, 1.0)
        val falseMealSuppression =
            hypothesisState?.suppressMealInterpretation == true ||
                patternSnapshot?.suppressMealInterpretation == true ||
                phaseOutput?.policy?.suppressMealLikeScenario == true ||
                (endogenousDrive > mealSignal + 0.15)
        val sensorConfidence = buildSensorConfidence(snapshot.confidence, sourceSensor, cgmFirstSensorConfidence)

        return PhysioLatentState(
            mealProb = if (falseMealSuppression) mealSignal.coerceAtMost(0.35) else mealSignal,
            endogenousGlucoseDrive = endogenousDrive,
            circadianSiFactor = circadianSiFactor,
            transientResistanceProb = transientResistanceProb,
            sleepDebtScore = sleepDebtScore,
            sensorConfidence = sensorConfidence,
            autonomicStress = autonomicStress.coerceIn(0.0, 1.0),
            inflammationRecovery = inflammationRecoveryMerged.coerceIn(0.0, 1.0),
            hormonalCircadian = hormonalCircadian.coerceIn(0.0, 1.0),
            postHypoReboundProb = postHypoReboundProb,
            falseMealSuppression = falseMealSuppression,
            source = "latent_v2",
        )
    }

    private fun buildMealProbability(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
    ): Double {
        val phaseMeal = if (phaseOutput?.phase?.isMealRisk == true) phaseOutput.confidence else 0.0
        val absorptionMeal = when {
            mealAbsorptionOutput == null -> 0.0
            mealAbsorptionOutput.phase.isActive -> mealAbsorptionOutput.belief
            else -> 0.0
        }
        val patternMeal = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.MEAL_DECLARED,
            PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
            PhysiologicalPatternId.MEAL_FIRST_WAVE,
            PhysiologicalPatternId.MEAL_SECOND_WAVE,
            PhysiologicalPatternId.LATE_FAT_PROTEIN,
        )
        return combineSignals(
            hypothesisState?.mealCompatibleProb() ?: 0.0,
            phaseMeal,
            absorptionMeal,
            patternMeal,
        )
    }

    private fun buildEndogenousDrive(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        hormonalCircadian: Double,
    ): Double {
        val phaseSignal = when {
            phaseOutput == null -> 0.0
            phaseOutput.phase.isEndogenousRisk -> phaseOutput.confidence
            phaseOutput.phase.isHormonalRisk -> phaseOutput.confidence * 0.72
            else -> 0.0
        }
        val patternSignal = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.DAWN_CORTISOL,
            PhysiologicalPatternId.MALE_CIRCADIAN_HORMONAL,
            PhysiologicalPatternId.FEMALE_CYCLE_HORMONAL,
            PhysiologicalPatternId.ENDOGENOUS_COUNTER_REGULATORY,
            PhysiologicalPatternId.NGR_NIGHT_GROWTH,
            PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
        )
        return combineSignals(
            hypothesisState?.dawnEndogenousProb ?: 0.0,
            phaseSignal,
            patternSignal,
            hormonalCircadian * 0.65,
        )
    }

    private fun buildPostHypoProbability(
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
        hypothesisState: UamHypothesisState?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
    ): Double {
        val decisionSignal = when (correctionAggressionDecision?.tier) {
            CorrectionAggressionGate.Tier.REBOUND_GUARD -> 0.72
            else -> 0.0
        }
        val patternSignal = patternSnapshot.maxConfidence(PhysiologicalPatternId.POST_HYPO_REBOUND)
        return combineSignals(
            hypothesisState?.postHypoProb ?: 0.0,
            decisionSignal,
            patternSignal,
        )
    }

    private fun buildSleepDebtScore(
        snapshot: HealthContextSnapshot,
        physioContext: PhysioContextMTR?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
    ): Double {
        val debtSignal = when {
            snapshot.sleepDebtMinutes >= 120 -> 0.85
            snapshot.sleepDebtMinutes >= 90 -> 0.70
            snapshot.sleepDebtMinutes >= 45 -> 0.45
            snapshot.sleepDebtMinutes >= 20 -> 0.20
            else -> 0.0
        }
        val contextSignal = when {
            physioContext == null -> 0.0
            physioContext.poorSleepDetected -> 0.55
            physioContext.state == PhysioStateMTR.RECOVERY_NEEDED -> 0.38
            else -> 0.0
        }
        val patternSignal = patternSnapshot.maxConfidence(
            PhysiologicalPatternId.SLEEP_DEBT,
            PhysiologicalPatternId.POOR_SLEEP_MORNING_RISE,
            PhysiologicalPatternId.RECOVERY_NEEDED,
        )
        return combineSignals(
            debtSignal,
            contextSignal,
            patternSignal,
        )
    }

    /**
     * Sensor confidence for the Harmonia/RBT authority gates.
     *
     * Legacy formula weights the **health-context snapshot** (Health Connect / watch data freshness) at
     * 70% and the CGM source at 30% — so CGM trust collapses when no wearable is present, even with a
     * perfect CGM, pinning `sensor_confidence` ≈ 0.32 and forcing authority to NONE all day.
     * `cgmFirst` decouples the two: CGM trust is driven by the **CGM source** (a signal-quality proxy),
     * with the health-context as a minor freshness modifier only. A null/unknown source stays cautious
     * (SOFT-eligible, ≈0.46 — clears the 0.45 block) rather than collapsing; a filtered native CGM is
     * trusted. See memory `sensor-confidence-gates-harmonia`.
     */
    internal fun buildSensorConfidence(
        snapshotConfidence: Double,
        sourceSensor: SourceSensor?,
        cgmFirst: Boolean = false,
    ): Double {
        val snap = snapshotConfidence.coerceIn(0.0, 1.0)
        if (cgmFirst) {
            val sourceConfidence = when {
                sourceSensor == null -> 0.50
                sourceSensor.advancedFilteringSupported() -> 0.85
                else -> 0.70
            }
            return (sourceConfidence * 0.80 + snap * 0.20).coerceIn(0.0, 1.0)
        }
        val sourceFloor = when {
            sourceSensor == null -> 0.35
            sourceSensor.advancedFilteringSupported() -> 0.75
            else -> 0.55
        }
        return ((snap * 0.70) + (sourceFloor * 0.30)).coerceIn(0.0, 1.0)
    }

    private fun combineSignals(vararg values: Double): Double {
        if (values.isEmpty()) return 0.0
        var remainingNeutral = 1.0
        for (value in values) {
            remainingNeutral *= 1.0 - value.coerceIn(0.0, 1.0)
        }
        return (1.0 - remainingNeutral).coerceIn(0.0, 1.0)
    }

    private fun PhysiologicalPatternSnapshot?.maxConfidence(vararg ids: PhysiologicalPatternId): Double =
        this?.active
            ?.filter { reading -> reading.id in ids }
            ?.maxOfOrNull { reading -> reading.confidence.coerceIn(0.0, 1.0) }
            ?: 0.0
}
