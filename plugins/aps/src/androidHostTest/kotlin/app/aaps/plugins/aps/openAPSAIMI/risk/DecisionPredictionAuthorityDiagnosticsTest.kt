package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetrics
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryModulation
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DecisionPredictionAuthorityDiagnosticsTest {

    @Test
    fun diagnosticsShowTheReleaseGateAndTailBreakers() {
        val armed = resolve(MealAbsorptionPhase.FIRST_WAVE).mcerDiagnostics!!
        assertTrue(armed.armed)
        assertTrue(armed.rising)
        assertTrue(armed.strongMealConfirmed)
        assertFalse(armed.tailByPhase)
        assertEquals(3.0, armed.combinedDeltaMgdl5m, 1e-9)

        val peak = resolve(MealAbsorptionPhase.PEAK_CORRECTION).mcerDiagnostics!!
        assertFalse(peak.armed)
        assertTrue(peak.tailByPhase)
        assertFalse(peak.tailByFall)
    }

    @Test
    fun diagnosticsAreAbsentWhenTheReleaseIsDisabled() {
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 160.0,
            pkpdEventualMgdl = 112.0,
            scenarioProjection = scenario(),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.FIRST_WAVE),
            hypothesisState = null,
            latentState = PhysioLatentState(),
            causalStatePosterior = null,
            trajectoryAnalysis = trajectory(),
            physioPolicy = null,
            uamConfidence = 0.5,
        )

        assertEquals(null, authority.mcerDiagnostics)
    }

    private fun resolve(phase: MealAbsorptionPhase) = DecisionPredictionAuthorityResolver.resolve(
        bgMgdl = 160.0,
        pkpdEventualMgdl = 112.0,
        scenarioProjection = scenario(),
        mealAbsorptionOutput = mealOutput(phase),
        hypothesisState = null,
        latentState = PhysioLatentState(),
        causalStatePosterior = null,
        trajectoryAnalysis = trajectory(),
        physioPolicy = null,
        uamConfidence = 0.5,
        mealConfirmedEarlyReleaseEnabled = true,
        combinedDeltaMgdl5m = 3.0,
        targetBgMgdl = 100.0,
        iobU = 2.0,
        maxIobU = 10.0,
    )

    private fun scenario() = ScenarioProjectionPair(
        clinicalFloor = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.CLINICAL_FLOOR,
            listOf(140.0, 104.0),
        ),
        scenarioBest = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.SCENARIO_BEST,
            listOf(140.0, 200.0),
        ),
        cobPointsMgdl = emptyList(),
        ztPointsMgdl = emptyList(),
        contributors = emptyList(),
        trajectoryType = null,
    )

    private fun mealOutput(phase: MealAbsorptionPhase) = MealAbsorptionPhaseEngine.Output(
        phase = phase,
        belief = if (phase.isActive) 0.70 else 0.20,
        reason = phase.name,
        deltaMgdlPer5 = 0.0,
        gapMgdl = 0.0,
        bestTerminalMgdl = 0.0,
        memoryActive = false,
        waveCount = 0,
        mealDeliveryPriority = true,
        chronoPrior = 0.0,
        kineticScore = 0.0,
        trajectoryScore = 0.0,
        physioScore = 0.0,
    )

    private fun trajectory() = TrajectoryAnalysis(
        classification = TrajectoryType.OPEN_DIVERGING,
        metrics = TrajectoryMetrics(
            curvature = 0.0,
            convergenceVelocity = 0.0,
            coherence = 1.0,
            energyBalance = 0.0,
            openness = 0.0,
            isConverging = false,
            isDiverging = true,
            isTightSpiral = false,
            hasLowCoherence = false,
            isStable = false,
        ),
        modulation = TrajectoryModulation.NEUTRAL,
        warnings = emptyList(),
        stableOrbitDistance = 0.0,
        predictedConvergenceTime = null,
    )
}
