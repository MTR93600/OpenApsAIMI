package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class PatientStateEngineTest {

    @Test
    fun build_summarizes_user_intent_and_physio_signals() {
        val nowMs = 1_718_000_000_000L
        val contextSnapshot = ContextSnapshot.from(
            timestampMs = nowMs,
            allIntents = listOf(
                ContextIntent.Activity(
                    startTimeMs = nowMs - 30.minutes.inWholeMilliseconds,
                    durationMs = 2.hours.inWholeMilliseconds,
                    intensity = ContextIntent.Intensity.HIGH,
                ),
                ContextIntent.Stress(
                    startTimeMs = nowMs - 15.minutes.inWholeMilliseconds,
                    durationMs = 1.hours.inWholeMilliseconds,
                    intensity = ContextIntent.Intensity.MEDIUM,
                    confidence = 0.8f,
                ),
            ),
        )

        val snapshot = PatientStateEngine.build(
            timestampMs = nowMs,
            phaseOutput = PhysiologicalPhaseClassifier.Output(
                phase = PhysiologicalPhase.MEAL_UNDECLARED,
                confidence = 0.82,
                policy = BehavioralRiskPolicy.forPhase(
                    phase = PhysiologicalPhase.MEAL_UNDECLARED,
                    confidence = 0.82,
                    reason = "meal-like rise",
                ),
            ),
            mealAbsorptionOutput = MealAbsorptionPhaseEngine.Output(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                belief = 0.78,
                reason = "fast rise",
                deltaMgdlPer5 = 4.5,
                gapMgdl = 42.0,
                bestTerminalMgdl = 220.0,
                memoryActive = true,
                waveCount = 1,
                mealDeliveryPriority = true,
                chronoPrior = 0.74,
                kineticScore = 0.68,
                trajectoryScore = 0.66,
                physioScore = 0.28,
            ),
            patternSnapshot = null,
            latentState = PhysioLatentState(
                mealProb = 0.84,
                endogenousGlucoseDrive = 0.14,
                transientResistanceProb = 0.22,
                sleepDebtScore = 0.18,
                postHypoReboundProb = 0.10,
                sensorConfidence = 0.92,
                source = "test",
            ),
            hypothesisState = UamHypothesisState(
                mealProb = 0.86,
                dawnEndogenousProb = 0.10,
                stressProb = 0.18,
                postHypoProb = 0.06,
                lateFatProb = 0.08,
                dominant = UamHypothesisId.MEAL,
                dominantConfidence = 0.86,
                suppressMealInterpretation = false,
            ),
            contextSnapshot = contextSnapshot,
        )

        assertThat(snapshot.phase).isEqualTo(PhysiologicalPhase.MEAL_UNDECLARED)
        assertThat(snapshot.mealAbsorptionPhase).isEqualTo(MealAbsorptionPhase.FIRST_WAVE)
        assertThat(snapshot.mealProb).isWithin(1e-9).of(0.84)
        assertThat(snapshot.userIntent.hasAnyIntent()).isTrue()
        assertThat(snapshot.userIntent.dominantIntent).isEqualTo("ACTIVITY")
        assertThat(snapshot.uamDominant).isEqualTo(UamHypothesisId.MEAL)
        assertThat(snapshot.causalPosterior.dominant).isEqualTo(CausalStateId.FAST_MEAL)
        assertThat(snapshot.causalPosterior.mealConfidence)
            .isGreaterThan(snapshot.causalPosterior.protectiveConfidence)
    }
}
