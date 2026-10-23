package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyTreeState
import app.aaps.plugins.aps.openAPSAIMI.patient.MealRiseGeometry
import app.aaps.plugins.aps.openAPSAIMI.patient.MealTerminalsAgree
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetrics
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryModulation
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port of `DecisionPredictionAuthorityResolverTest` from `dev_OAPSAIMI` (`plugins/aps/src/test`).
 *
 * [DecisionPredictionAuthorityResolver] and every input it reads are in commonMain and pure, so the
 * test runs on every target. Adapted: JUnit assertions replaced by `kotlin.test` (same argument
 * order, `expected` first), names in camelCase. The cases and the numbers are the same as on dev;
 * the resolver logic is the same as on dev (only the number formatting in `reason` differs, and the
 * tests check only the parts of `reason` that carry no number).
 */
class DecisionPredictionAuthorityResolverTest {

    @Test
    fun mealRiseUpliftsDecisionEventualAbovePkpd() {
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 148.0,
            pkpdEventualMgdl = 112.0,
            scenarioProjection = scenario(floorTerminal = 104.0, bestTerminal = 184.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.FIRST_WAVE, priority = true),
            hypothesisState = UamHypothesisState(
                mealProb = 0.78,
                dominant = UamHypothesisId.MEAL,
                dominantConfidence = 0.78,
            ),
            latentState = PhysioLatentState(),
            causalStatePosterior = null,
            trajectoryAnalysis = trajectory(TrajectoryType.OPEN_DIVERGING),
            physioPolicy = null,
            uamConfidence = 0.62,
        )

        assertEquals(DecisionPredictionSource.SCENARIO_MEAL_UPLIFT, authority.source)
        assertTrue(authority.scenarioUpliftApplied)
        assertEquals(184.0, authority.eventualTerminalMgdl, 0.001)
        assertEquals(104.0, authority.predTerminalMgdl, 0.001)
    }

    @Test
    fun falseMealSuppressionKeepsPkpdAuthorityDuringCortisolLikeRise() {
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 142.0,
            pkpdEventualMgdl = 118.0,
            scenarioProjection = scenario(floorTerminal = 116.0, bestTerminal = 156.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.NONE, priority = false),
            hypothesisState = UamHypothesisState(
                dawnEndogenousProb = 0.82,
                dominant = UamHypothesisId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.82,
                suppressMealInterpretation = true,
            ),
            latentState = PhysioLatentState(falseMealSuppression = true),
            causalStatePosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.84,
                stressResistanceProb = 0.22,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.84,
                learningQuality = 0.34,
            ),
            trajectoryAnalysis = trajectory(TrajectoryType.SLOW_DRIFT),
            physioPolicy = null,
            uamConfidence = 0.18,
        )

        assertEquals(DecisionPredictionSource.SCENARIO_SUPPRESSED_NON_MEAL, authority.source)
        assertTrue(authority.falseMealSuppression)
        assertFalse(authority.scenarioUpliftApplied)
        assertEquals(118.0, authority.eventualTerminalMgdl, 0.001)
        assertEquals(116.0, authority.predTerminalMgdl, 0.001)
    }

    @Test
    fun digestionTrunkAndMealCertaintyEnableMealUpliftWithoutPhase() {
        val certainty = MealCertainty(
            level = MealCertaintyLevel.HIGH,
            treeState = MealCertaintyTreeState.DIGESTION_ACTIVE,
            absorptionPhase = MealAbsorptionPhase.NONE,
            riseGeometry = MealRiseGeometry.OK,
            terminalsAgree = MealTerminalsAgree.OK,
            effortVeto = false,
            softCorroboration = false,
        )
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 148.0,
            pkpdEventualMgdl = 112.0,
            scenarioProjection = scenario(floorTerminal = 104.0, bestTerminal = 184.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.NONE, priority = false),
            hypothesisState = UamHypothesisState(mealProb = 0.20),
            latentState = PhysioLatentState(),
            causalStatePosterior = null,
            trajectoryAnalysis = trajectory(TrajectoryType.OPEN_DIVERGING),
            physioPolicy = null,
            uamConfidence = 0.10,
            mealCertainty = certainty,
            trunkGlobalState = GlobalPhysiologicalState.DIGESTION_ACTIVE,
        )

        assertEquals(DecisionPredictionSource.SCENARIO_MEAL_UPLIFT, authority.source)
        assertTrue(authority.scenarioUpliftApplied)
        assertTrue(authority.reason.contains("mealCert=HIGH"))
        assertTrue(authority.reason.contains("trunk=DIGESTION_ACTIVE"))
        assertEquals(184.0, authority.eventualTerminalMgdl, 0.001)
    }

    @Test
    fun smallScenarioGapUsesConsensusWithoutForcingMealInterpretation() {
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 132.0,
            pkpdEventualMgdl = 109.0,
            scenarioProjection = scenario(floorTerminal = 108.0, bestTerminal = 111.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.NONE, priority = false),
            hypothesisState = UamHypothesisState(
                postHypoProb = 0.76,
                dominant = UamHypothesisId.POST_HYPO,
                dominantConfidence = 0.76,
            ),
            latentState = PhysioLatentState(),
            causalStatePosterior = null,
            trajectoryAnalysis = trajectory(TrajectoryType.CLOSING_CONVERGING),
            physioPolicy = null,
            uamConfidence = 0.10,
        )

        assertEquals(DecisionPredictionSource.SCENARIO_CONSENSUS, authority.source)
        assertFalse(authority.scenarioUpliftApplied)
        assertEquals(111.0, authority.eventualTerminalMgdl, 0.001)
        assertEquals(108.0, authority.predTerminalMgdl, 0.001)
    }

    @Test
    fun causalFastMealAllowsEarlierScenarioMealUplift() {
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 150.0,
            pkpdEventualMgdl = 118.0,
            scenarioProjection = scenario(floorTerminal = 112.0, bestTerminal = 163.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.NONE, priority = false),
            hypothesisState = UamHypothesisState(mealProb = 0.34),
            latentState = PhysioLatentState(mealProb = 0.28),
            causalStatePosterior = CausalStatePosterior(
                fastMealProb = 0.76,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.76,
                learningQuality = 0.82,
            ),
            trajectoryAnalysis = trajectory(TrajectoryType.CLOSING_CONVERGING),
            physioPolicy = null,
            uamConfidence = 0.20,
        )

        assertEquals(DecisionPredictionSource.SCENARIO_MEAL_UPLIFT, authority.source)
        assertTrue(authority.scenarioUpliftApplied)
        assertEquals(163.0, authority.eventualTerminalMgdl, 0.001)
    }

    @Test
    fun postHypoDeliveryBlocksFastMealScenarioUplift() {
        // Stay inside rebound band (bg < target+30) so REBOUND_GUARD stays active;
        // aggressive-rise exit / margin exit must not apply here.
        val aggressionInput = CorrectionAggressionGate.Input(
            bg = 119.0,
            targetBg = 100.0,
            deltaMgdl5m = 5.0,
            shortAvgDelta = 4.0,
            combinedDelta = 2.5,
            cob = 0.0,
            minBgLookback75m = 54.0,
            estimatedCarbs = 0.0,
            estimatedCarbsAgeMin = Double.MAX_VALUE,
            uamConfidence = 0.7,
            estimatedRa = 0.9,
            explicitMealMode = false,
            hasRecentMealEstimate = false,
            isConfirmedHighRise = false,
        )
        val postHypo = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = CorrectionAggressionGate.evaluate(aggressionInput),
                patientMode = PatientMode.POST_HYPO_RECOVERY,
                aggressionInput = aggressionInput,
            ),
        )
        assertTrue(postHypo.active)
        assertTrue(postHypo.forceMealInterpretationSuppressed)

        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 119.0,
            pkpdEventualMgdl = 108.0,
            scenarioProjection = scenario(floorTerminal = 102.0, bestTerminal = 163.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.FIRST_WAVE, priority = true),
            hypothesisState = UamHypothesisState(
                mealProb = 0.68,
                dominant = UamHypothesisId.MEAL,
                dominantConfidence = 0.70,
            ),
            latentState = PhysioLatentState(mealProb = 0.79, falseMealSuppression = false),
            causalStatePosterior = CausalStatePosterior(
                fastMealProb = 0.79,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 0.79,
                learningQuality = 0.82,
            ),
            trajectoryAnalysis = trajectory(TrajectoryType.OPEN_DIVERGING),
            physioPolicy = null,
            uamConfidence = 0.70,
            postHypoDelivery = postHypo,
        )

        assertEquals(DecisionPredictionSource.SCENARIO_SUPPRESSED_NON_MEAL, authority.source)
        assertTrue(authority.falseMealSuppression)
        assertFalse(authority.scenarioUpliftApplied)
        assertEquals(108.0, authority.eventualTerminalMgdl, 0.001)
    }

    private fun mcerResolve(phase: MealAbsorptionPhase) = DecisionPredictionAuthorityResolver.resolve(
        bgMgdl = 160.0,
        pkpdEventualMgdl = 112.0,
        scenarioProjection = scenario(floorTerminal = 104.0, bestTerminal = 200.0),
        mealAbsorptionOutput = mealOutput(phase, priority = true),
        hypothesisState = null,
        latentState = PhysioLatentState(),
        causalStatePosterior = null,
        trajectoryAnalysis = trajectory(TrajectoryType.OPEN_DIVERGING),
        physioPolicy = null,
        uamConfidence = 0.5,
        mealConfirmedEarlyReleaseEnabled = true,
        combinedDeltaMgdl5m = 3.0,
        targetBgMgdl = 100.0,
        iobU = 2.0,
        maxIobU = 10.0,
    )

    /** The MCER gate is exported term by term, so a field package shows which breaker held it off. */
    @Test
    fun mcerDiagnosticsShowEveryTerm() {
        val armed = mcerResolve(MealAbsorptionPhase.FIRST_WAVE).mcerDiagnostics!!
        assertTrue(armed.armed)
        assertTrue(armed.rising)
        assertTrue(armed.strongMealConfirmed)
        assertFalse(armed.tailByPhase)
        assertEquals(3.0, armed.combinedDeltaMgdl5m, 1e-9)

        val peak = mcerResolve(MealAbsorptionPhase.PEAK_CORRECTION).mcerDiagnostics!!
        assertFalse(peak.armed)
        assertTrue(peak.tailByPhase)
        assertFalse(peak.tailByFall)
    }

    @Test
    fun mcerDiagnosticsAbsentWhenTheReleaseIsOff() {
        val authority = DecisionPredictionAuthorityResolver.resolve(
            bgMgdl = 160.0, pkpdEventualMgdl = 112.0,
            scenarioProjection = scenario(floorTerminal = 104.0, bestTerminal = 200.0),
            mealAbsorptionOutput = mealOutput(MealAbsorptionPhase.FIRST_WAVE, priority = true),
            hypothesisState = null, latentState = PhysioLatentState(), causalStatePosterior = null,
            trajectoryAnalysis = trajectory(TrajectoryType.OPEN_DIVERGING), physioPolicy = null, uamConfidence = 0.5,
        )
        assertEquals(null, authority.mcerDiagnostics)
    }

    private fun scenario(
        floorTerminal: Double,
        bestTerminal: Double,
    ): ScenarioProjectionPair =
        ScenarioProjectionPair(
            clinicalFloor = ScenarioProjectionCurve.fromRawPoints(
                ScenarioProjectionKind.CLINICAL_FLOOR,
                listOf(140.0, floorTerminal),
            ),
            scenarioBest = ScenarioProjectionCurve.fromRawPoints(
                ScenarioProjectionKind.SCENARIO_BEST,
                listOf(140.0, bestTerminal),
            ),
            cobPointsMgdl = emptyList(),
            ztPointsMgdl = emptyList(),
            contributors = emptyList(),
            trajectoryType = null,
        )

    private fun mealOutput(
        phase: MealAbsorptionPhase,
        priority: Boolean,
    ): MealAbsorptionPhaseEngine.Output =
        MealAbsorptionPhaseEngine.Output(
            phase = phase,
            belief = if (phase.isActive) 0.70 else 0.20,
            reason = phase.name,
            deltaMgdlPer5 = 0.0,
            gapMgdl = 0.0,
            bestTerminalMgdl = 0.0,
            memoryActive = false,
            waveCount = 0,
            mealDeliveryPriority = priority,
            chronoPrior = 0.0,
            kineticScore = 0.0,
            trajectoryScore = 0.0,
            physioScore = 0.0,
        )

    private fun trajectory(type: TrajectoryType): TrajectoryAnalysis =
        TrajectoryAnalysis(
            classification = type,
            metrics = TrajectoryMetrics(
                curvature = 0.0,
                convergenceVelocity = 0.0,
                coherence = 1.0,
                energyBalance = 0.0,
                openness = 0.0,
                isConverging = type == TrajectoryType.CLOSING_CONVERGING,
                isDiverging = type == TrajectoryType.OPEN_DIVERGING || type == TrajectoryType.SLOW_DRIFT,
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
