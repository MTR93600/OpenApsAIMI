package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

class PatientStateRuntimeRefresherTest {

    @AfterEach
    fun tearDown() {
        PatientStateRuntimeRepository.clear()
    }

    @Test
    fun refreshFromHealthSnapshot_rebuilds_patient_mode_from_loop_cache() {
        val nowMs = 1_718_000_000_000L
        val phaseOutput = PhysiologicalPhaseClassifier.Output(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            confidence = 0.84,
            policy = BehavioralRiskPolicy.forPhase(
                phase = PhysiologicalPhase.DAWN_CORTISOL,
                confidence = 0.84,
                reason = "dawn",
            ),
        )
        val mealOutput = MealAbsorptionPhaseEngine.Output(
            phase = MealAbsorptionPhase.NONE,
            belief = 0.0,
            reason = "none",
            deltaMgdlPer5 = 0.0,
            gapMgdl = 0.0,
            bestTerminalMgdl = 120.0,
            memoryActive = false,
            waveCount = 0,
            mealDeliveryPriority = false,
            chronoPrior = 0.0,
            kineticScore = 0.0,
            trajectoryScore = 0.0,
            physioScore = 0.0,
        )
        val cache = PatientStateLoopCache(
            phaseOutput = phaseOutput,
            mealAbsorptionOutput = mealOutput,
            patternSnapshot = null,
            contextSnapshot = null,
            sourceSensor = null,
            correctionAggressionDecision = null,
            chronicInflammation = null,
            physioContext = null,
            physioTrace = null,
            hypothesisState = null,
            uamConfidence = 0.0,
        )
        val initialState = PatientStateEngine.build(
            timestampMs = nowMs,
            phaseOutput = phaseOutput,
            mealAbsorptionOutput = mealOutput,
            patternSnapshot = null,
            latentState = null,
            hypothesisState = null,
            contextSnapshot = null,
        )
        val initialMode = PatientModeOrchestrator.evaluate(initialState)
        val initialTree = PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = initialState,
            patientModeDecision = initialMode,
        )
        PatientStateRuntimeRepository.publish(
            patientState = initialState,
            patientModeDecision = initialMode,
            updatedAtMs = nowMs,
            physiologicalTree = initialTree,
            harmoniaDecision = HarmoniaDecisionEngine.evaluate(
                tree = initialTree,
                environment = HarmoniaDecisionEnvironment(
                    currentBgMgdl = 145.0,
                    deltaMgdl5m = 1.0,
                    iobU = 1.0,
                    cobG = 5.0,
                    currentBasalUph = 1.0,
                    maxBasalUph = 5.0,
                    maxSmbU = 1.0,
                    maxIobU = 5.0,
                ),
            ),
            loopCache = cache,
        )

        val refreshed = PatientStateRuntimeRefresher.refreshFromHealthSnapshot(
            healthSnapshot = HealthContextSnapshot(
                stepsLast15m = 420,
                hrNow = 112,
                hrAvg15m = 98,
                activityState = "WALKING",
                timestamp = nowMs,
                confidence = 0.8,
                source = "Test",
                isValid = true,
            ),
            nowMs = nowMs + 120_000L,
        )

        assertThat(refreshed).isNotNull()
        assertThat(refreshed?.refreshSource).isEqualTo(PatientRefreshSource.PHYSIO_SIGNAL)
        assertThat(refreshed?.physioLive?.stepsLast15m).isEqualTo(420)
        assertThat(refreshed?.physioLive?.hrNowBpm).isEqualTo(112)
        assertThat(refreshed?.physiologicalTree?.compactSummary).contains("Tree:")
        assertThat(refreshed?.harmoniaDecision?.compactSummary).contains("Harmonia sim:")
    }

    @Test
    fun refreshFromContextIntents_updates_mode_without_loop_cache_when_only_intent_present() {
        val nowMs = 1_718_000_000_000L
        val contextSnapshot = ContextSnapshot.from(
            timestampMs = nowMs,
            allIntents = listOf(
                ContextIntent.Stress(
                    startTimeMs = nowMs - 30 * 60_000L,
                    durationMs = 1.hours.inWholeMilliseconds,
                    intensity = ContextIntent.Intensity.HIGH,
                    confidence = 0.9f,
                ),
            ),
        )
        val refreshed = PatientStateRuntimeRefresher.refreshFromContextIntents(
            contextSnapshot = contextSnapshot,
            nowMs = nowMs,
        )

        assertThat(refreshed).isNotNull()
        assertThat(refreshed?.refreshSource).isEqualTo(PatientRefreshSource.CONTEXT_INTENT)
        assertThat(refreshed?.patientState?.userIntent?.hasAnyIntent()).isTrue()
        // R1: context refresh must still deploy the tree (not force null).
        assertThat(refreshed?.physiologicalTree).isNotNull()
        assertThat(refreshed?.physiologicalTree?.compactSummary).contains("Tree:")
    }

    @Test
    fun refreshFromHealthSnapshot_buildsTreeEvenWithoutPriorTree() {
        // Chicken-egg regression: old code required getLatest()?.physiologicalTree != null.
        val nowMs = 1_718_000_100_000L
        val cache = PatientStateLoopCache(
            phaseOutput = null,
            mealAbsorptionOutput = null,
            patternSnapshot = null,
            contextSnapshot = null,
            sourceSensor = null,
            correctionAggressionDecision = null,
            chronicInflammation = null,
            physioContext = null,
            physioTrace = null,
            hypothesisState = null,
            uamConfidence = 0.0,
        )
        val emptyState = PatientStateEngine.build(
            timestampMs = nowMs,
            phaseOutput = null,
            mealAbsorptionOutput = null,
            patternSnapshot = null,
            latentState = null,
            hypothesisState = null,
            contextSnapshot = null,
        )
        PatientStateRuntimeRepository.publish(
            patientState = emptyState,
            patientModeDecision = PatientModeOrchestrator.evaluate(emptyState),
            updatedAtMs = nowMs,
            physiologicalTree = null,
            harmoniaDecision = null,
            loopCache = cache,
        )

        val refreshed = PatientStateRuntimeRefresher.refreshFromHealthSnapshot(
            healthSnapshot = HealthContextSnapshot(
                stepsLast15m = 50,
                hrNow = 72,
                hrAvg15m = 70,
                activityState = "IDLE",
                timestamp = nowMs + 60_000L,
                confidence = 0.7,
                source = "Test",
                isValid = true,
            ),
            nowMs = nowMs + 60_000L,
        )

        assertThat(refreshed?.physiologicalTree).isNotNull()
        assertThat(refreshed?.physiologicalTree?.trunk).isNotNull()
    }
}
