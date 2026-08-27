package app.aaps.plugins.aps.openAPSAIMI.quality

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import app.aaps.plugins.aps.openAPSAIMI.AimiDecisionContext
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefAuthorityGate
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefPreferences
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.ReleaseAuthority
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyRiskExportSnapshot

internal data class ReplayQualityExport(
    val version: Int,
    val falseMealGuardState: String,
    val mealHypothesisState: String,
    val mealHypothesisConfidence: Double?,
    val uamHypothesisDominant: String,
    val uamHypothesisDominantConfidence: Double?,
    val uamMealInterpretationSuppressed: Boolean,
    val mealInterpretationSuppressed: Boolean,
    val patientMode: String?,
    val patientModeConfidence: Double?,
    val patientStrategyHint: String?,
    val patientModeReasons: List<String>,
    val causalStateDominant: String?,
    val causalStateConfidence: Double?,
    val causalMealConfidence: Double?,
    val causalProtectiveConfidence: Double?,
    val causalLearningQuality: Double?,
    val contextIntentActive: Boolean,
    val contextIntentCount: Int?,
    val contextIntentDominant: String?,
    val patternDominant: String?,
    val stackingGuardState: String,
    val stackingGuardActive: Boolean,
    val postHypoGuardState: String,
    val postHypoGuardActive: Boolean,
    val correctionAggressionTier: String?,
    val predictiveHypoSuppressed: Boolean,
    val rbtMode: String,
    val rbtAuthorityApplied: Boolean,
    val authorityRequested: String,
    val authorityEffective: String,
    val authorityReadinessScore: Double?,
    val authorityGateReasons: List<String>,
    val loadGovernorTier: String?,
    val predictionAvailable: Boolean,
    val smbProposedU: Double?,
    val smbCappedU: Double?,
    val smbFinalU: Double?,
    val decisionSource: String,
    val safetySource: String,
    val qualityTags: List<String>,
    val tuningReference: String,
)

internal object ReplayQualityExportBuilder {

    private const val VERSION = 2
    private const val TUNING_REFERENCE =
        "AIMI_Decisions.jsonl#adjustments.replay_quality + patient_mode + iob_surveillance + safety_risk + recursive_belief"

    fun build(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
        hypothesisState: UamHypothesisState?,
        patientState: PatientStateSnapshot?,
        patientModeDecision: PatientModeOrchestrator.Decision?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
        iobSurveillanceExport: AimiDecisionContext.IobSurveillanceExport?,
        safetyRiskExport: SafetyRiskExportSnapshot?,
        recursiveBeliefSnapshot: RecursiveBeliefSnapshot?,
        authorityGateDecision: RecursiveBeliefAuthorityGate.Decision?,
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
        predictionAvailable: Boolean,
        smbProposedU: Double,
        smbCappedU: Double,
        smbFinalU: Double,
        decisionSource: String,
        safetySource: String,
        rbtPreferences: RecursiveBeliefPreferences,
    ): ReplayQualityExport {
        val falseMealGuardState = falseMealGuardState(phaseOutput, patternSnapshot)
        val mealHypothesisState = mealHypothesisState(phaseOutput, mealAbsorptionOutput)
        val mealHypothesisConfidence = mealHypothesisConfidence(phaseOutput, mealAbsorptionOutput)
        val uamHypothesisDominant = hypothesisState?.dominant?.name ?: UamHypothesisId.NONE.name
        val uamHypothesisDominantConfidence = hypothesisState?.dominantConfidence?.takeIf { it > 0.0 }
        val uamMealInterpretationSuppressed = hypothesisState?.suppressMealInterpretation == true
        val mealInterpretationSuppressed =
            patternSnapshot?.suppressMealInterpretation == true ||
                uamMealInterpretationSuppressed ||
                falseMealGuardState.startsWith("SUPPRESS_")
        val causalPosterior = patientState?.causalPosterior
        val contextIntentActive = patientState?.userIntent?.hasAnyIntent() == true
        val patientMode = patientModeDecision?.mode?.name
        val patientModeConfidence = patientModeDecision?.confidence
        val patientStrategyHint = patientModeDecision?.strategyHint?.name
        val patientModeReasons = patientModeDecision?.reasonCodes ?: emptyList()
        val stackingGuardState = stackingGuardState(
            patternSnapshot = patternSnapshot,
            iobSurveillanceExport = iobSurveillanceExport,
            recursiveBeliefSnapshot = recursiveBeliefSnapshot,
        )
        val stackingGuardActive = stackingGuardState != "CLEAR"
        val postHypoGuardState = postHypoGuardState(
            patternSnapshot = patternSnapshot,
            safetyRiskExport = safetyRiskExport,
            correctionAggressionDecision = correctionAggressionDecision,
        )
        val postHypoGuardActive = postHypoGuardState != "CLEAR"
        val predictiveHypoSuppressed = safetyRiskExport?.predictiveHypoSuppressed == true
        val authorityApplied = authorityGateDecision?.effectiveAuthority?.let {
            it != ReleaseAuthority.NONE
        } ?: false
        val authorityRequested = authorityGateDecision?.requestedAuthority?.name
            ?: recursiveBeliefSnapshot?.resolutions?.releaseAuthority?.name
            ?: ReleaseAuthority.NONE.name
        val authorityEffective = authorityGateDecision?.effectiveAuthority?.name ?: ReleaseAuthority.NONE.name
        val authorityGateReasons = authorityGateDecision?.reasonCodes ?: emptyList()
        val rbtMode = rbtMode(
            rbtPreferences = rbtPreferences,
            authorityRequested = authorityRequested,
            authorityEffective = authorityEffective,
            authorityApplied = authorityApplied,
        )
        val qualityTags = linkedSetOf<String>().apply {
            if (mealInterpretationSuppressed) add("meal_interpretation_suppressed")
            if (uamMealInterpretationSuppressed) add("uam_multi_hypothesis_guard")
            if (mealHypothesisState != "NONE") add("meal_hypothesis_active")
            if (stackingGuardActive) add("stacking_guard_active")
            if (postHypoGuardActive) add("post_hypo_guard_active")
            if (predictiveHypoSuppressed) add("predictive_hypo_suppressed")
            if (authorityApplied) add("rbt_authority_active")
            else if (authorityRequested != ReleaseAuthority.NONE.name) add("rbt_authority_blocked")
            else if (rbtPreferences.shadowEnabled) add("rbt_shadow_active")
            if (authorityGateDecision?.softLimited == true) add("rbt_authority_soft_only")
            if (!predictionAvailable) add("prediction_missing")
            if (contextIntentActive) add("user_intent_active")
            when (causalPosterior?.dominant?.name) {
                "FAST_MEAL" -> add("causal_fast_meal")
                "PROLONGED_MEAL" -> add("causal_prolonged_meal")
                "DAWN_ENDOGENOUS" -> add("causal_dawn_endogenous")
                "POST_HYPO_RECOVERY" -> add("causal_post_hypo_recovery")
                "STRESS_RESISTANCE" -> add("causal_stress_resistance")
                "EXERCISE_AFTERBURN" -> add("causal_exercise_afterburn")
                "INFLAMMATORY_DRIFT" -> add("causal_inflammatory_drift")
                "ABSORPTION_UNCERTAIN" -> add("causal_absorption_uncertain")
            }
            if ((causalPosterior?.learningQuality ?: 1.0) < CausalStatePosterior.LEARNING_QUALITY_MIN) add("causal_learning_unclean")
            if ((patientModeDecision?.mealBias ?: 0.0) >= 0.70) add("patient_mode_meal")
            if ((patientModeDecision?.protectionBias ?: 0.0) >= 0.70) add("patient_mode_protective")
            when (patientModeDecision?.mode) {
                PatientMode.DAWN_ENDOGENOUS -> add("patient_mode_dawn_endogenous")
                PatientMode.POST_HYPO_RECOVERY -> add("patient_mode_post_hypo_recovery")
                PatientMode.EXERCISE_AFTERBURN -> add("patient_mode_exercise_afterburn")
                PatientMode.ABSORPTION_UNCERTAIN -> add("patient_mode_absorption_uncertain")
                else -> Unit
            }
        }.toList()

        return ReplayQualityExport(
            version = VERSION,
            falseMealGuardState = falseMealGuardState,
            mealHypothesisState = mealHypothesisState,
            mealHypothesisConfidence = mealHypothesisConfidence,
            uamHypothesisDominant = uamHypothesisDominant,
            uamHypothesisDominantConfidence = uamHypothesisDominantConfidence,
            uamMealInterpretationSuppressed = uamMealInterpretationSuppressed,
            mealInterpretationSuppressed = mealInterpretationSuppressed,
            patientMode = patientMode,
            patientModeConfidence = patientModeConfidence,
            patientStrategyHint = patientStrategyHint,
            patientModeReasons = patientModeReasons,
            causalStateDominant = causalPosterior?.dominant?.name,
            causalStateConfidence = causalPosterior?.dominantConfidence,
            causalMealConfidence = causalPosterior?.mealConfidence,
            causalProtectiveConfidence = causalPosterior?.protectiveConfidence,
            causalLearningQuality = causalPosterior?.learningQuality,
            contextIntentActive = contextIntentActive,
            contextIntentCount = patientState?.userIntent?.intentCount,
            contextIntentDominant = patientState?.userIntent?.dominantIntent,
            patternDominant = patternSnapshot?.dominant?.name,
            stackingGuardState = stackingGuardState,
            stackingGuardActive = stackingGuardActive,
            postHypoGuardState = postHypoGuardState,
            postHypoGuardActive = postHypoGuardActive,
            correctionAggressionTier = correctionAggressionDecision?.tier?.name,
            predictiveHypoSuppressed = predictiveHypoSuppressed,
            rbtMode = rbtMode,
            rbtAuthorityApplied = authorityApplied,
            authorityRequested = authorityRequested,
            authorityEffective = authorityEffective,
            authorityReadinessScore = authorityGateDecision?.readinessScore,
            authorityGateReasons = authorityGateReasons,
            loadGovernorTier = recursiveBeliefSnapshot?.loadGovernor?.tier,
            predictionAvailable = predictionAvailable,
            smbProposedU = smbProposedU.takeIf { it.isFinite() },
            smbCappedU = smbCappedU.takeIf { it.isFinite() },
            smbFinalU = smbFinalU.takeIf { it.isFinite() },
            decisionSource = decisionSource,
            safetySource = safetySource,
            qualityTags = qualityTags,
            tuningReference = TUNING_REFERENCE,
        )
    }

    fun toJsonObject(export: ReplayQualityExport): JsonObject =
        buildJsonObject {
            put("version", export.version)
            put("false_meal_guard_state", export.falseMealGuardState)
            put("meal_hypothesis_state", export.mealHypothesisState)
            put("meal_hypothesis_confidence", export.mealHypothesisConfidence)
            put("uam_hypothesis_dominant", export.uamHypothesisDominant)
            put("uam_hypothesis_dominant_confidence", export.uamHypothesisDominantConfidence)
            put("uam_meal_interpretation_suppressed", export.uamMealInterpretationSuppressed)
            put("meal_interpretation_suppressed", export.mealInterpretationSuppressed)
            put("patient_mode", export.patientMode)
            put("patient_mode_confidence", export.patientModeConfidence)
            put("patient_strategy_hint", export.patientStrategyHint)
            put("patient_mode_reasons", JsonArray(export.patientModeReasons.map { JsonPrimitive(it) }))
            put("causal_state_dominant", export.causalStateDominant)
            put("causal_state_confidence", export.causalStateConfidence)
            put("causal_meal_confidence", export.causalMealConfidence)
            put("causal_protective_confidence", export.causalProtectiveConfidence)
            put("causal_learning_quality", export.causalLearningQuality)
            put("context_intent_active", export.contextIntentActive)
            put("context_intent_count", export.contextIntentCount)
            put("context_intent_dominant", export.contextIntentDominant)
            put("pattern_dominant", export.patternDominant)
            put("stacking_guard_state", export.stackingGuardState)
            put("stacking_guard_active", export.stackingGuardActive)
            put("post_hypo_guard_state", export.postHypoGuardState)
            put("post_hypo_guard_active", export.postHypoGuardActive)
            put("correction_aggression_tier", export.correctionAggressionTier)
            put("predictive_hypo_suppressed", export.predictiveHypoSuppressed)
            put("rbt_mode", export.rbtMode)
            put("rbt_authority_applied", export.rbtAuthorityApplied)
            put("authority_requested", export.authorityRequested)
            put("authority_effective", export.authorityEffective)
            put("authority_readiness_score", export.authorityReadinessScore)
            put("authority_gate_reasons", JsonArray(export.authorityGateReasons.map { JsonPrimitive(it) }))
            put("load_governor_tier", export.loadGovernorTier)
            put("prediction_available", export.predictionAvailable)
            put("smb_proposed_u", export.smbProposedU)
            put("smb_capped_u", export.smbCappedU)
            put("smb_final_u", export.smbFinalU)
            put("decision_source", export.decisionSource)
            put("safety_source", export.safetySource)
            put("quality_tags", JsonArray(export.qualityTags.map { JsonPrimitive(it) }))
            put("tuning_reference", export.tuningReference)
        }

    private fun falseMealGuardState(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        patternSnapshot: PhysiologicalPatternSnapshot?,
    ): String {
        if (patternSnapshot?.suppressMealInterpretation == true) {
            return patternSnapshot.dominant?.let { "SUPPRESS_${it.name}" } ?: "SUPPRESS_PATTERN"
        }
        return when (phaseOutput?.phase) {
            PhysiologicalPhase.DAWN_CORTISOL,
            PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL,
            PhysiologicalPhase.FEMALE_CYCLE_HORMONAL,
            -> "SUPPRESS_${phaseOutput.phase.name}"

            PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY -> "SUPPRESS_ENDOGENOUS_COUNTER_REGULATORY"
            PhysiologicalPhase.STRESS_CORTISOL -> "CAUTION_STRESS_CORTISOL"
            else -> "CLEAR"
        }
    }

    private fun mealHypothesisState(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
    ): String =
        when {
            phaseOutput?.phase == PhysiologicalPhase.MEAL_DECLARED -> PhysiologicalPhase.MEAL_DECLARED.name
            mealAbsorptionOutput?.phase?.isActive == true -> mealAbsorptionOutput.phase.name
            phaseOutput?.phase == PhysiologicalPhase.MEAL_UNDECLARED -> PhysiologicalPhase.MEAL_UNDECLARED.name
            else -> "NONE"
        }

    private fun mealHypothesisConfidence(
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        mealAbsorptionOutput: MealAbsorptionPhaseEngine.Output?,
    ): Double? =
        when {
            mealAbsorptionOutput?.phase?.isActive == true -> mealAbsorptionOutput.belief
            phaseOutput?.phase == PhysiologicalPhase.MEAL_DECLARED ||
                phaseOutput?.phase == PhysiologicalPhase.MEAL_UNDECLARED ->
                phaseOutput.confidence
            else -> null
        }

    private fun stackingGuardState(
        patternSnapshot: PhysiologicalPatternSnapshot?,
        iobSurveillanceExport: AimiDecisionContext.IobSurveillanceExport?,
        recursiveBeliefSnapshot: RecursiveBeliefSnapshot?,
    ): String {
        val hasActiveReason = !iobSurveillanceExport?.active_reason.isNullOrBlank()
        val loadGovernor = recursiveBeliefSnapshot?.loadGovernor
        if (loadGovernor != null && (loadGovernor.applied || loadGovernor.multiplierG < 0.999)) {
            return "LOAD_GOVERNOR_${loadGovernor.tier}"
        }
        if (patternSnapshot.hasPattern(PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE)) {
            return "PATTERN_IOB_STACKING_SURVEILLANCE"
        }
        if (
            iobSurveillanceExport?.stacking_reduced_smb == true ||
            iobSurveillanceExport?.signal_trajectory_stack == true ||
            hasActiveReason
        ) {
            val kind = iobSurveillanceExport?.kind ?: "UNKNOWN"
            return "IOB_SURVEILLANCE_$kind"
        }
        return "CLEAR"
    }

    private fun postHypoGuardState(
        patternSnapshot: PhysiologicalPatternSnapshot?,
        safetyRiskExport: SafetyRiskExportSnapshot?,
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
    ): String {
        if (correctionAggressionDecision?.tier == CorrectionAggressionGate.Tier.REBOUND_GUARD) {
            return "CORRECTION_REBOUND_GUARD"
        }
        if (patternSnapshot.hasPattern(PhysiologicalPatternId.POST_HYPO_REBOUND)) {
            return "PATTERN_POST_HYPO_REBOUND"
        }
        if (safetyRiskExport?.predictiveHypoSuppressed == true) {
            return "PREDICTIVE_HYPO_SUPPRESSED"
        }
        return "CLEAR"
    }

    private fun rbtMode(
        rbtPreferences: RecursiveBeliefPreferences,
        authorityRequested: String,
        authorityEffective: String,
        authorityApplied: Boolean,
    ): String {
        return when {
            authorityApplied -> "AUTHORITY_$authorityEffective"
            authorityRequested != ReleaseAuthority.NONE.name && rbtPreferences.authorityEnabled -> "GATE_BLOCKED"
            rbtPreferences.shadowEnabled && !rbtPreferences.authorityEnabled -> "EXPORT_ONLY"
            rbtPreferences.authorityEnabled -> "AUTHORITY_PREF_ON"
            RecursiveBeliefPreferences.isActive(rbtPreferences) -> "BUILD_ONLY"
            else -> "OFF"
        }
    }

    private fun PhysiologicalPatternSnapshot?.hasPattern(id: PhysiologicalPatternId): Boolean =
        this?.active?.any { it.id == id } == true
}
