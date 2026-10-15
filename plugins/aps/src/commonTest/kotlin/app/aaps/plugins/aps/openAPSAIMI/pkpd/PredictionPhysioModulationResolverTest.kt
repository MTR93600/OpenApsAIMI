package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PredictionPhysioModulationResolverTest {

    @Test
    fun resolve_usesRuntimeSensitivityAndMealSignalForUndeclaredMeal() {
        val runtime = runtime(
            fusedIsf = 43.0,
            weightKineticFactor = 0.97,
            physioAbsorptionFactor = 1.11,
            physioSiFactor = 0.92,
        )
        val mealOutput = mealOutput(
            phase = MealAbsorptionPhase.FIRST_WAVE,
            belief = 0.78,
            mealDeliveryPriority = true,
        )
        val hypothesis = UamHypothesisState(
            mealProb = 0.72,
            lateFatProb = 0.20,
            dominant = UamHypothesisId.MEAL,
            dominantConfidence = 0.72,
        )
        val latent = PhysioLatentState(
            mealProb = 0.70,
            sensorConfidence = 0.90,
            circadianSiFactor = 0.92,
            transientResistanceProb = 0.35,
        )

        val modulation = PredictionPhysioModulationResolver.resolve(
            fallbackSensitivityMgdlPerU = 50.0,
            pkpdRuntime = runtime,
            mealAbsorptionOutput = mealOutput,
            hypothesisState = hypothesis,
            latentState = latent,
            uamConfidence = 0.68,
        )

        assertEquals(43.0, modulation.effectiveSensitivityMgdlPerU, 0.001)
        assertTrue(modulation.carbImpactFactor > 1.0)
        assertTrue(modulation.uamMomentumFactor > 1.0)
        assertTrue(modulation.hybridMomentumFactor > 1.0)
        assertTrue(modulation.mealSignal > modulation.nonMealSignal)
    }

    @Test
    fun resolve_suppressesFalseMealWhenStressAndEndogenousDriveDominate() {
        val hypothesis = UamHypothesisState(
            mealProb = 0.28,
            dawnEndogenousProb = 0.72,
            stressProb = 0.74,
            dominant = UamHypothesisId.STRESS,
            dominantConfidence = 0.74,
            suppressMealInterpretation = true,
        )
        val latent = PhysioLatentState(
            mealProb = 0.20,
            endogenousGlucoseDrive = 0.76,
            autonomicStress = 0.78,
            transientResistanceProb = 0.70,
            postHypoReboundProb = 0.24,
            falseMealSuppression = true,
            sensorConfidence = 0.85,
        )

        val modulation = PredictionPhysioModulationResolver.resolve(
            fallbackSensitivityMgdlPerU = 50.0,
            pkpdRuntime = null,
            mealAbsorptionOutput = mealOutput(
                phase = MealAbsorptionPhase.INTER_WAVE,
                belief = 0.42,
                mealDeliveryPriority = false,
            ),
            hypothesisState = hypothesis,
            latentState = latent,
            uamConfidence = 0.55,
        )

        assertTrue(modulation.falseMealSuppression)
        assertTrue(modulation.nonMealSignal > modulation.mealSignal)
        assertTrue(modulation.carbImpactFactor < 1.0)
        assertTrue(modulation.uamMomentumFactor < 0.5)
        assertTrue(modulation.hybridMomentumFactor < 0.5)
    }

    private fun runtime(
        fusedIsf: Double,
        weightKineticFactor: Double,
        physioAbsorptionFactor: Double,
        physioSiFactor: Double,
    ): PkPdRuntime =
        PkPdRuntime(
            params = PkPdParams(diaHrs = 4.0, peakMin = 75.0),
            tailFraction = 0.18,
            fusedIsf = fusedIsf,
            profileIsf = 50.0,
            tddIsf = 47.0,
            pkpdScale = 1.02,
            weightKineticFactor = weightKineticFactor,
            physioAbsorptionFactor = physioAbsorptionFactor,
            physioSiFactor = physioSiFactor,
            damping = SmbDamping(),
            activity = InsulinActivityState(
                window = InsulinActivityWindow(
                    onsetMin = 20.0,
                    peakMin = 75.0,
                    offsetMin = 180.0,
                    diaMin = 240.0,
                ),
                relativeActivity = 0.56,
                normalizedPosition = 0.42,
                postWindowFraction = 0.08,
                anticipationWeight = 0.18,
                minutesUntilOnset = 0.0,
                stage = InsulinActivityStage.RISING,
            ),
        )

    private fun mealOutput(
        phase: MealAbsorptionPhase,
        belief: Double,
        mealDeliveryPriority: Boolean,
    ): MealAbsorptionPhaseEngine.Output =
        MealAbsorptionPhaseEngine.Output(
            phase = phase,
            belief = belief,
            reason = "test",
            deltaMgdlPer5 = 4.0,
            gapMgdl = 18.0,
            bestTerminalMgdl = 190.0,
            memoryActive = phase.isActive,
            waveCount = 1,
            mealDeliveryPriority = mealDeliveryPriority,
            chronoPrior = 0.60,
            kineticScore = 0.62,
            trajectoryScore = 0.64,
            physioScore = 0.55,
        )
}
