package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientModeOrchestrator
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStrategyHint
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.InsulinIntent
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.risk.AimiRiskPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyRiskExportSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecursiveBeliefAuthorityGateTest {

    @Test
    fun evaluate_blocks_authority_when_predictions_are_missing() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = false,
                phaseOutput = null,
                patternSnapshot = null,
                latentState = PhysioLatentState(sensorConfidence = 0.92, source = "test"),
                hypothesisState = null,
                patientState = null,
                patientModeDecision = null,
                safetyRiskExport = null,
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
        assertThat(decision.reasonCodes).contains("PRED_MISSING")
    }

    @Test
    fun evaluate_limits_false_meal_context_to_soft_authority() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY.copy(suppressMealInterpretation = true),
                latentState = PhysioLatentState(
                    mealProb = 0.24,
                    endogenousGlucoseDrive = 0.82,
                    transientResistanceProb = 0.58,
                    sensorConfidence = 0.84,
                    falseMealSuppression = true,
                    source = "test",
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.22,
                    dawnEndogenousProb = 0.88,
                    stressProb = 0.08,
                    postHypoProb = 0.10,
                    dominant = UamHypothesisId.DAWN_ENDOGENOUS,
                    dominantConfidence = 0.88,
                    suppressMealInterpretation = true,
                ),
                patientState = null,
                patientModeDecision = null,
                safetyRiskExport = null,
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(decision.softLimited).isTrue()
        assertThat(decision.reasonCodes).contains("MEAL_SUPPRESS")
        assertThat(decision.reasonCodes).contains("NON_MEAL_DOM")
    }

    @Test
    fun evaluate_allows_full_authority_when_quality_signals_are_clean() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
                latentState = PhysioLatentState(
                    mealProb = 0.86,
                    endogenousGlucoseDrive = 0.12,
                    circadianSiFactor = 0.95,
                    transientResistanceProb = 0.18,
                    sensorConfidence = 0.96,
                    postHypoReboundProb = 0.10,
                    source = "test",
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.90,
                    dawnEndogenousProb = 0.06,
                    stressProb = 0.05,
                    postHypoProb = 0.04,
                    dominant = UamHypothesisId.MEAL,
                    dominantConfidence = 0.90,
                    suppressMealInterpretation = false,
                ),
                patientState = null,
                patientModeDecision = null,
                safetyRiskExport = SafetyRiskExportSnapshot(
                    phase = AimiRiskPhase.DECISION,
                    predictiveHypoSuppressed = false,
                    safetyGate = "clear",
                    haltRemainingPipeline = false,
                    mealContextActive = true,
                    mealRiseConfirmed = true,
                    compositeMinMgdl = 120.0,
                    predBgMgdl = 178.0,
                    eventualBgMgdl = 194.0,
                    uamTerminalMgdl = 220.0,
                    hypoThresholdMgdl = 75.0,
                ),
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.HARD)
        assertThat(decision.reasonCodes).containsExactly("READY")
        assertThat(decision.liftBlend).isEqualTo(1.0)
    }

    @Test
    fun evaluate_soft_limits_exercise_afterburn_mode_even_when_other_signals_are_clean() {
        val patientState = PatientStateSnapshot(
            timestampMs = 1_718_000_000_000L,
            mealProb = 0.22,
            endogenousGlucoseDrive = 0.12,
            transientResistanceProb = 0.18,
            sensorConfidence = 0.94,
        )
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
                latentState = PhysioLatentState(
                    mealProb = 0.22,
                    endogenousGlucoseDrive = 0.12,
                    transientResistanceProb = 0.18,
                    sensorConfidence = 0.94,
                    source = "test",
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.22,
                    dominant = UamHypothesisId.NONE,
                    dominantConfidence = 0.0,
                    suppressMealInterpretation = false,
                ),
                patientState = patientState,
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.EXERCISE_AFTERBURN,
                    confidence = 0.82,
                    strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                    mealBias = 0.18,
                    protectionBias = 0.82,
                    userIntentConfidence = 0.86,
                    reasonCodes = listOf("CTX_ACTIVITY"),
                ),
                safetyRiskExport = null,
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(decision.reasonCodes).contains("MODE_EXERCISE_AFTERBURN")
    }

    @Test
    fun evaluate_keeps_soft_authority_when_predictive_hypo_is_suppressed_but_meal_context_is_strong() {
        val patientState = PatientStateSnapshot(
            timestampMs = 1_718_000_000_000L,
            mealProb = 0.84,
            postHypoReboundProb = 0.18,
            sensorConfidence = 0.95,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 0.86,
                prolongedMealProb = 0.32,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.84,
                learningQuality = 0.90,
            ),
        )
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
                latentState = PhysioLatentState(
                    mealProb = 0.84,
                    endogenousGlucoseDrive = 0.10,
                    transientResistanceProb = 0.18,
                    sensorConfidence = 0.95,
                    postHypoReboundProb = 0.18,
                    source = "test",
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.88,
                    dawnEndogenousProb = 0.06,
                    stressProb = 0.04,
                    postHypoProb = 0.08,
                    dominant = UamHypothesisId.MEAL,
                    dominantConfidence = 0.88,
                    suppressMealInterpretation = false,
                ),
                patientState = patientState,
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.FAST_MEAL,
                    confidence = 0.86,
                    strategyHint = PatientStrategyHint.SMB_PRIORITY,
                    mealBias = 0.92,
                    protectionBias = 0.16,
                    userIntentConfidence = 0.20,
                    reasonCodes = listOf("CAUSAL_FAST_MEAL"),
                ),
                safetyRiskExport = SafetyRiskExportSnapshot(
                    phase = AimiRiskPhase.DECISION,
                    predictiveHypoSuppressed = true,
                    safetyGate = "suppressed",
                    haltRemainingPipeline = false,
                    mealContextActive = true,
                    mealRiseConfirmed = true,
                    compositeMinMgdl = 108.0,
                    predBgMgdl = 196.0,
                    eventualBgMgdl = 214.0,
                    uamTerminalMgdl = 248.0,
                    hypoThresholdMgdl = 75.0,
                ),
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(decision.reasonCodes).contains("PREDICTIVE_HYPO_MEAL_BYPASS")
        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO")
    }

    @Test
    fun evaluate_keeps_soft_authority_on_corroborated_hyper_meal_without_rise_confirmed_when_a1_enabled() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            hyperMealInput(mealHyperBypassEnabled = true, mealRiseConfirmed = false),
        )

        // A1: hallucinated PKPD hypo must not pin authority to NONE on a corroborated, non-falling hyper.
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(decision.reasonCodes).contains("PREDICTIVE_HYPO_MEAL_BYPASS_HYPER")
        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO_MEAL_BYPASS")
        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO")
    }

    @Test
    fun evaluate_does_not_hyper_bypass_when_a1_disabled() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            hyperMealInput(mealHyperBypassEnabled = false, mealRiseConfirmed = false),
        )

        // Fail-safe off → legacy behaviour: no hyper bypass code, authority stays denied by predictive hypo.
        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO_MEAL_BYPASS_HYPER")
    }

    @Test
    fun evaluate_holds_soft_on_clear_hyper_gently_falling_without_meal_corroboration() {
        // Clear hyper (BG≫threshold), gently falling, NO meal corroboration → the predictive-hypo halt
        // was merely suppressed (no imminent hypo), so authority must hold SOFT, not drop to NONE.
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            clearHyperInput(mealHyperBypassEnabled = true, deltaMgdl5m = -2.0),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(decision.reasonCodes).contains("PREDICTIVE_HYPO_HYPER_HOLD")
        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO")
    }

    @Test
    fun evaluate_holds_soft_on_hyper_descent_at_quiche_rate() {
        // P1: the quiche descent (BG 247, Δ≈−9) must now HOLD — projection stays well above hypo, so
        // authority opens at the same moment as the terminal safety exemption. Previously (flat −3
        // threshold) this collapsed to NONE and produced 14 hard-zero cycles.
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            clearHyperInput(mealHyperBypassEnabled = true, deltaMgdl5m = -9.0),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(decision.reasonCodes).contains("PREDICTIVE_HYPO_HYPER_HOLD")
        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO")
    }

    @Test
    fun evaluate_denies_on_hyper_freefall() {
        // True freefall (Δ<−15) → the shared exemption predicate closes; stay conservative (NONE).
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            clearHyperInput(mealHyperBypassEnabled = true, deltaMgdl5m = -16.0),
        )

        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO_HYPER_HOLD")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
    }

    @Test
    fun evaluate_no_hyper_hold_when_a1_disabled() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            clearHyperInput(mealHyperBypassEnabled = false, deltaMgdl5m = -9.0),
        )

        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO_HYPER_HOLD")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
    }

    /**
     * Clear-hyper cycle with the predictive-hypo halt suppressed and NO meal evidence
     * (dominant non-meal hypothesis) — isolates the A1b "clear-hyper hold" branch from A1 meal bypass.
     */
    private fun clearHyperInput(
        mealHyperBypassEnabled: Boolean,
        deltaMgdl5m: Double,
    ): RecursiveBeliefAuthorityGate.Input =
        RecursiveBeliefAuthorityGate.Input(
            authorityEnabled = true,
            requestedAuthority = ReleaseAuthority.HARD,
            predictionAvailable = true,
            phaseOutput = null,
            patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
            latentState = PhysioLatentState(
                mealProb = 0.10,
                endogenousGlucoseDrive = 0.40,
                transientResistanceProb = 0.20,
                sensorConfidence = 0.95,
                postHypoReboundProb = 0.05,
                source = "test",
            ),
            hypothesisState = UamHypothesisState(
                mealProb = 0.10,
                dawnEndogenousProb = 0.72,
                stressProb = 0.10,
                postHypoProb = 0.05,
                dominant = UamHypothesisId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.72,
                suppressMealInterpretation = false,
            ),
            patientState = null,
            patientModeDecision = null,
            safetyRiskExport = SafetyRiskExportSnapshot(
                phase = AimiRiskPhase.DECISION,
                predictiveHypoSuppressed = true,
                safetyGate = "suppressed",
                haltRemainingPipeline = false,
                mealContextActive = false,
                mealRiseConfirmed = false,
                compositeMinMgdl = 39.0,
                predBgMgdl = 39.0,
                eventualBgMgdl = 39.0,
                uamTerminalMgdl = 210.0,
                hypoThresholdMgdl = 70.0,
            ),
            bgMgdl = 213.0,
            targetBgMgdl = 112.0,
            deltaMgdl5m = deltaMgdl5m,
            mealHyperBypassEnabled = mealHyperBypassEnabled,
        )

    /** Corroborated undeclared-meal hyper (BG≫target, not falling), PKPD predictive hypo suppressed. */
    private fun hyperMealInput(
        mealHyperBypassEnabled: Boolean,
        mealRiseConfirmed: Boolean,
    ): RecursiveBeliefAuthorityGate.Input {
        val patientState = PatientStateSnapshot(
            timestampMs = 1_718_000_000_000L,
            mealProb = 0.84,
            postHypoReboundProb = 0.18,
            sensorConfidence = 0.95,
            causalPosterior = CausalStatePosterior(
                fastMealProb = 0.86,
                prolongedMealProb = 0.32,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.84,
                learningQuality = 0.90,
            ),
        )
        return RecursiveBeliefAuthorityGate.Input(
            authorityEnabled = true,
            requestedAuthority = ReleaseAuthority.HARD,
            predictionAvailable = true,
            phaseOutput = null,
            patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
            latentState = PhysioLatentState(
                mealProb = 0.84,
                endogenousGlucoseDrive = 0.10,
                transientResistanceProb = 0.18,
                sensorConfidence = 0.95,
                postHypoReboundProb = 0.18,
                source = "test",
            ),
            hypothesisState = UamHypothesisState(
                mealProb = 0.88,
                dawnEndogenousProb = 0.06,
                stressProb = 0.04,
                postHypoProb = 0.08,
                dominant = UamHypothesisId.MEAL,
                dominantConfidence = 0.88,
                suppressMealInterpretation = false,
            ),
            patientState = patientState,
            patientModeDecision = PatientModeOrchestrator.Decision(
                mode = PatientMode.FAST_MEAL,
                confidence = 0.86,
                strategyHint = PatientStrategyHint.SMB_PRIORITY,
                mealBias = 0.92,
                protectionBias = 0.16,
                userIntentConfidence = 0.20,
                reasonCodes = listOf("CAUSAL_FAST_MEAL"),
            ),
            safetyRiskExport = SafetyRiskExportSnapshot(
                phase = AimiRiskPhase.DECISION,
                predictiveHypoSuppressed = true,
                safetyGate = "suppressed",
                haltRemainingPipeline = false,
                mealContextActive = true,
                mealRiseConfirmed = mealRiseConfirmed,
                compositeMinMgdl = 108.0,
                predBgMgdl = 196.0,
                eventualBgMgdl = 39.0,
                uamTerminalMgdl = 248.0,
                hypoThresholdMgdl = 75.0,
            ),
            bgMgdl = 224.0,
            targetBgMgdl = 112.0,
            deltaMgdl5m = 1.0,
            mealHyperBypassEnabled = mealHyperBypassEnabled,
        )
    }

    @Test
    fun evaluate_soft_limits_post_hypo_rebound_episode() {
        val episode = RbtEpisodeMemory.EpisodeState(
            kind = RbtEpisodeMemory.EpisodeKind.POST_HYPO_REBOUND,
            startedAtMs = 1_718_000_000_000L,
            lastSeenAtMs = 1_718_000_000_000L,
            peakScore = 0.72,
            tickCount = 3,
        )
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
                latentState = PhysioLatentState(sensorConfidence = 0.92, source = "test"),
                hypothesisState = null,
                patientState = null,
                patientModeDecision = null,
                safetyRiskExport = null,
                episode = episode,
            ),
        )

        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(decision.reasonCodes).contains("EPISODE_POST_HYPO")
    }

    @Test
    fun evaluate_bypasses_post_hypo_episode_and_mode_on_aggressive_rise_exit() {
        val episode = RbtEpisodeMemory.EpisodeState(
            kind = RbtEpisodeMemory.EpisodeKind.POST_HYPO_REBOUND,
            startedAtMs = 1_718_000_000_000L,
            lastSeenAtMs = 1_718_000_000_000L,
            peakScore = 0.80,
            tickCount = 4,
        )
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
                latentState = PhysioLatentState(
                    mealProb = 0.86,
                    sensorConfidence = 0.95,
                    postHypoReboundProb = 0.70,
                    source = "test",
                ),
                hypothesisState = UamHypothesisState(
                    mealProb = 0.88,
                    dominant = UamHypothesisId.MEAL,
                    dominantConfidence = 0.88,
                    suppressMealInterpretation = false,
                ),
                patientState = null,
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.POST_HYPO_RECOVERY,
                    confidence = 0.90,
                    strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                    mealBias = 0.20,
                    protectionBias = 0.90,
                    userIntentConfidence = 0.0,
                    reasonCodes = listOf("POST_HYPO"),
                ),
                safetyRiskExport = null,
                episode = episode,
                bgMgdl = 160.0,
                targetBgMgdl = 100.0,
                deltaMgdl5m = 18.0,
            ),
        )

        assertThat(decision.reasonCodes).contains("POST_HYPO_AGGRESSIVE_RISE_EXIT")
        assertThat(decision.reasonCodes).contains("MODE_POST_HYPO_RECOVERY_BYPASSED")
        assertThat(decision.reasonCodes).doesNotContain("EPISODE_POST_HYPO")
        assertThat(decision.reasonCodes).doesNotContain("MODE_POST_HYPO_RECOVERY")
        assertThat(decision.effectiveAuthority).isNotEqualTo(ReleaseAuthority.NONE)
    }

    @Test
    fun evaluate_keeps_soft_authority_when_predictive_hypo_suppressed_on_aggressive_rise_exit() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
                latentState = PhysioLatentState(
                    mealProb = 0.40,
                    sensorConfidence = 0.95,
                    postHypoReboundProb = 0.55,
                    source = "test",
                ),
                hypothesisState = null,
                patientState = null,
                patientModeDecision = PatientModeOrchestrator.Decision(
                    mode = PatientMode.POST_HYPO_RECOVERY,
                    confidence = 0.90,
                    strategyHint = PatientStrategyHint.CONSERVATIVE_OBSERVE,
                    mealBias = 0.20,
                    protectionBias = 0.90,
                    userIntentConfidence = 0.0,
                    reasonCodes = listOf("POST_HYPO"),
                ),
                safetyRiskExport = SafetyRiskExportSnapshot(
                    phase = AimiRiskPhase.DECISION,
                    predictiveHypoSuppressed = true,
                    safetyGate = "suppressed",
                    haltRemainingPipeline = false,
                    mealContextActive = false,
                    mealRiseConfirmed = false,
                    compositeMinMgdl = 72.0,
                    predBgMgdl = 90.0,
                    eventualBgMgdl = 95.0,
                    uamTerminalMgdl = 100.0,
                    hypoThresholdMgdl = 75.0,
                ),
                bgMgdl = 137.0,
                targetBgMgdl = 100.0,
                deltaMgdl5m = 18.0,
            ),
        )

        assertThat(decision.reasonCodes).contains("POST_HYPO_AGGRESSIVE_RISE_EXIT")
        assertThat(decision.reasonCodes).contains("PREDICTIVE_HYPO_AGGRESSIVE_RISE")
        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO")
        assertThat(decision.reasonCodes).doesNotContain("PREDICTIVE_HYPO_MEAL_BYPASS")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
    }

    // ---- Tree meal-rise front-loader (arbre déploie NEED_MORE_INSULIN → Harmonia applique) ----

    @Test
    fun frontload_reopens_soft_when_sensor_low_clobbered_corroborated_meal_rise() {
        // sensor_confidence low (SENSOR_LOW → NONE), tree deployed NEED_MORE_INSULIN, corroborated rise,
        // BG≫target, flag ON → authority re-opened to SOFT so Harmonia can apply the early lift.
        val decision = RecursiveBeliefAuthorityGate.evaluate(frontLoadInput())

        assertThat(decision.reasonCodes).contains("SENSOR_LOW")
        assertThat(decision.reasonCodes).contains("TREE_MEAL_RISE_FRONTLOAD")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
    }

    @Test
    fun frontload_never_grants_hard() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(frontLoadInput(requested = ReleaseAuthority.HARD))
        // Restored to SOFT only, never HARD.
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.SOFT)
    }

    @Test
    fun frontload_disabled_stays_none() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(frontLoadInput(flag = false))
        assertThat(decision.reasonCodes).doesNotContain("TREE_MEAL_RISE_FRONTLOAD")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
    }

    @Test
    fun frontload_not_applied_without_need_more_insulin_intent() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(frontLoadInput(intent = InsulinIntent.MEAL_SUPPORT))
        assertThat(decision.reasonCodes).doesNotContain("TREE_MEAL_RISE_FRONTLOAD")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
    }

    @Test
    fun frontload_not_applied_without_meal_corroboration() {
        // No independent meal evidence (low mealProb, non-meal dominant hypothesis) → stays NONE.
        val decision = RecursiveBeliefAuthorityGate.evaluate(frontLoadInput(mealProb = 0.10))
        assertThat(decision.reasonCodes).doesNotContain("TREE_MEAL_RISE_FRONTLOAD")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
    }

    @Test
    fun frontload_never_overrides_prediction_missing() {
        // PRED_MISSING is a sovereign veto: front-load must NOT re-open.
        val decision = RecursiveBeliefAuthorityGate.evaluate(frontLoadInput(predictionAvailable = false))
        assertThat(decision.reasonCodes).contains("PRED_MISSING")
        assertThat(decision.reasonCodes).doesNotContain("TREE_MEAL_RISE_FRONTLOAD")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
    }

    @Test
    fun frontload_not_applied_below_target_margin() {
        // Real low-ish BG (below target+45) → excluded by construction (no override into hypo territory).
        val decision = RecursiveBeliefAuthorityGate.evaluate(frontLoadInput(bg = 120.0))
        assertThat(decision.reasonCodes).doesNotContain("TREE_MEAL_RISE_FRONTLOAD")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
    }

    @Test
    fun frontload_not_applied_on_freefall() {
        // Free-falling (Δ<−15) → shared exemption predicate closes → no override.
        val decision = RecursiveBeliefAuthorityGate.evaluate(frontLoadInput(delta = -18.0))
        assertThat(decision.reasonCodes).doesNotContain("TREE_MEAL_RISE_FRONTLOAD")
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
    }

    /**
     * SENSOR_LOW-clobbered, corroborated meal-rise with the tree deploying NEED_MORE_INSULIN — the
     * front-loader's target case. Knobs let each test flip one condition.
     */
    private fun frontLoadInput(
        flag: Boolean = true,
        intent: InsulinIntent = InsulinIntent.NEED_MORE_INSULIN,
        requested: ReleaseAuthority = ReleaseAuthority.HARD,
        bg: Double = 185.0,
        delta: Double = 2.0,
        mealProb: Double = 0.84,
        predictionAvailable: Boolean = true,
    ): RecursiveBeliefAuthorityGate.Input =
        RecursiveBeliefAuthorityGate.Input(
            authorityEnabled = true,
            requestedAuthority = requested,
            predictionAvailable = predictionAvailable,
            phaseOutput = null,
            patternSnapshot = PhysiologicalPatternSnapshot.EMPTY,
            latentState = PhysioLatentState(
                mealProb = mealProb,
                endogenousGlucoseDrive = 0.10,
                transientResistanceProb = 0.15,
                sensorConfidence = 0.32, // < 0.45 → SENSOR_LOW forces NONE
                postHypoReboundProb = 0.10,
                source = "test",
            ),
            hypothesisState = UamHypothesisState(
                mealProb = mealProb,
                dawnEndogenousProb = 0.05,
                stressProb = 0.05,
                postHypoProb = 0.05,
                dominant = UamHypothesisId.MEAL,
                dominantConfidence = mealProb,
                suppressMealInterpretation = false,
            ),
            patientState = null,
            patientModeDecision = null,
            safetyRiskExport = SafetyRiskExportSnapshot(
                phase = AimiRiskPhase.DECISION,
                predictiveHypoSuppressed = false,
                safetyGate = "pass",
                haltRemainingPipeline = false,
                mealContextActive = true,
                mealRiseConfirmed = false,
                compositeMinMgdl = bg,
                predBgMgdl = bg,
                eventualBgMgdl = bg,
                uamTerminalMgdl = bg + 30.0,
                hypoThresholdMgdl = 70.0,
            ),
            bgMgdl = bg,
            targetBgMgdl = 112.0,
            deltaMgdl5m = delta,
            treeInsulinIntent = intent,
            treeInsulinUrgency = 0.8,
            treeMealRiseFrontLoadEnabled = flag,
        )
}

