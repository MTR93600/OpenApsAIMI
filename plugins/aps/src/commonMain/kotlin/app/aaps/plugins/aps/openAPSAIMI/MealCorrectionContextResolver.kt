package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaAction
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import kotlin.math.max

internal object MealCorrectionContextResolver {

    data class Input(
        val bgMgdl: Double,
        val deltaMgdlPer5: Double,
        val shortAvgDeltaMgdlPer5: Double,
        val explicitMealMode: Boolean,
        val mealCobG: Double,
        val mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        val hypothesisState: UamHypothesisState?,
        val latentState: PhysioLatentState?,
        val patientModeDecision: PatientModeOrchestrator.Decision?,
        val patientState: PatientStateSnapshot?,
        val postHypoDelivery: PostHypoDeliveryAuthority.Decision = PostHypoDeliveryAuthority.INACTIVE,
        val harmoniaAction: HarmoniaAction? = null,
        val harmoniaEligible: Boolean = false,
    )

    data class Output(
        val mealPriorityEligible: Boolean,
        val redCarpetEligible: Boolean,
        val falseMealSuppression: Boolean,
        val reasonCodes: List<String>,
    ) {
        fun summary(): String = reasonCodes.joinToString(",")
    }

    fun resolve(input: Input): Output {
        val phase = input.mealAbsorptionOutput?.phase ?: MealAbsorptionPhase.NONE
        val belief = input.mealAbsorptionOutput?.belief ?: 0.0
        val mealDeliveryPriority = input.mealAbsorptionOutput?.mealDeliveryPriority == true
        val mealCompatibleProb = input.hypothesisState?.mealCompatibleProb() ?: 0.0
        val competingNonMealProb = max(
            input.hypothesisState?.competingNonMealProb() ?: 0.0,
            input.patientState?.causalPosterior?.protectiveConfidence ?: 0.0,
        )
        val patientMealConfidence = input.patientState?.causalPosterior?.mealConfidence ?: 0.0
        val falseMealSuppression =
            input.postHypoDelivery.forceMealInterpretationSuppressed ||
                input.hypothesisState?.suppressMealInterpretation == true ||
                input.latentState?.falseMealSuppression == true ||
                input.patientState?.falseMealSuppression == true

        val explicitMealEvidence = input.explicitMealMode || input.mealCobG >= 6.0
        val strongMealPhase =
            phase == MealAbsorptionPhase.FIRST_WAVE ||
                phase == MealAbsorptionPhase.SECOND_WAVE ||
                phase == MealAbsorptionPhase.PEAK_CORRECTION
        val interWaveSupport = phase == MealAbsorptionPhase.INTER_WAVE && belief >= 0.45
        val phaseMealEvidence = strongMealPhase || interWaveSupport
        val fastMealMode =
            input.patientModeDecision?.mode == PatientMode.FAST_MEAL &&
                input.patientModeDecision.confidence >= 0.60 &&
                input.patientModeDecision.mealBias >= 0.75
        val prolongedMealMode =
            input.patientModeDecision?.mode == PatientMode.PROLONGED_MEAL &&
                input.patientModeDecision.confidence >= 0.64 &&
                input.patientModeDecision.mealBias >= 0.68
        val patientModeMealEvidence = fastMealMode || prolongedMealMode
        val hypothesisMealEvidence =
            mealCompatibleProb >= 0.55 ||
                (
                    input.hypothesisState?.dominant == UamHypothesisId.MEAL &&
                        input.hypothesisState.dominantConfidence >= 0.58 &&
                        mealCompatibleProb >= 0.45
                    )
        val patientStateMealEvidence =
            (input.patientState?.mealProb ?: 0.0) >= 0.72 ||
                (
                    input.patientState?.uamDominant == UamHypothesisId.MEAL &&
                        (input.patientState.uamDominantConfidence >= 0.60)
                    ) ||
                (input.patientState?.causalPosterior?.supportsMealInterpretation(minConfidence = 0.55, mealMargin = 0.04) == true)

        val nonMealBlocks =
            !explicitMealEvidence &&
                !strongMealPhase &&
                !mealDeliveryPriority &&
                competingNonMealProb >= max(mealCompatibleProb, patientMealConfidence) + 0.10 &&
                competingNonMealProb >= 0.60

        val riseForPriority =
            input.deltaMgdlPer5 >= 1.8 ||
                input.shortAvgDeltaMgdlPer5 >= 1.5 ||
                phaseMealEvidence
        val riseForRedCarpet =
            input.deltaMgdlPer5 >= 1.5 ||
                input.shortAvgDeltaMgdlPer5 >= 1.2 ||
                phaseMealEvidence

        val mealEvidence =
            explicitMealEvidence ||
                mealDeliveryPriority ||
                phaseMealEvidence ||
                patientModeMealEvidence ||
                hypothesisMealEvidence ||
                patientStateMealEvidence ||
                (
                    input.harmoniaEligible &&
                        input.harmoniaAction == HarmoniaAction.MEAL_SUPPORT &&
                        input.deltaMgdlPer5 >= 1.0
                    )

        val harmoniaOpposesMealPriority =
            input.mealCobG <= 0.0 &&
                (
                    input.harmoniaAction == HarmoniaAction.STABILIZE ||
                        input.harmoniaAction == HarmoniaAction.PROTECTIVE_REDUCTION ||
                        input.harmoniaAction == HarmoniaAction.BLOCKED ||
                        (
                            input.harmoniaEligible &&
                                input.harmoniaAction == HarmoniaAction.OBSERVE &&
                                input.deltaMgdlPer5 < 2.5
                            )
                    )

        val mealPriorityEligible =
            !falseMealSuppression &&
                !nonMealBlocks &&
                !harmoniaOpposesMealPriority &&
                input.bgMgdl >= 145.0 &&
                riseForPriority &&
                mealEvidence

        val redCarpetEligible =
            !falseMealSuppression &&
                !nonMealBlocks &&
                input.bgMgdl >= 140.0 &&
                riseForRedCarpet &&
                (
                    explicitMealEvidence ||
                        strongMealPhase ||
                        mealDeliveryPriority ||
                        patientModeMealEvidence ||
                        patientStateMealEvidence ||
                        (
                            input.hypothesisState?.dominant == UamHypothesisId.MEAL &&
                                input.hypothesisState.dominantConfidence >= 0.58 &&
                                mealCompatibleProb >= 0.55
                            ) ||
                        (interWaveSupport && mealCompatibleProb >= 0.45)
                    )

        val reasons = mutableListOf<String>()
        if (input.postHypoDelivery.active) reasons += "POST_HYPO_DELIVERY_GUARD"
        if (falseMealSuppression) reasons += "FALSE_MEAL_SUPPRESS"
        if (explicitMealEvidence) reasons += "EXPLICIT_OR_COB"
        if (mealDeliveryPriority) reasons += "MEAL_DELIVERY_PRIORITY"
        if (strongMealPhase) reasons += "PHASE_${phase.name}"
        if (interWaveSupport) reasons += "INTER_WAVE_SUPPORT"
        if (fastMealMode) reasons += "PATIENT_MODE_FAST_MEAL"
        if (prolongedMealMode) reasons += "PATIENT_MODE_PROLONGED_MEAL"
        if (hypothesisMealEvidence) reasons += "UAM_MEAL_EVIDENCE"
        if (patientStateMealEvidence) reasons += "PATIENT_STATE_MEAL"
        if (nonMealBlocks) reasons += "NON_MEAL_BLOCK"
        if (harmoniaOpposesMealPriority) reasons += "HARMONIA_MEAL_VETO"
        if (input.harmoniaEligible && input.harmoniaAction == HarmoniaAction.MEAL_SUPPORT) {
            reasons += "HARMONIA_MEAL_SUPPORT"
        }
        if (mealPriorityEligible) reasons += "MEAL_PRIORITY_ELIGIBLE"
        if (redCarpetEligible) reasons += "RED_CARPET_ELIGIBLE"

        return Output(
            mealPriorityEligible = mealPriorityEligible,
            redCarpetEligible = redCarpetEligible,
            falseMealSuppression = falseMealSuppression,
            reasonCodes = reasons,
        )
    }
}
