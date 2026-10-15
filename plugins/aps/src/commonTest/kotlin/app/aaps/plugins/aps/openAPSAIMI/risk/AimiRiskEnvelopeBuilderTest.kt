package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class AimiRiskEnvelopeBuilderTest {

    @Test
    fun decisionEnvelopeUsesPostPkpdCompositeAndHypoThreshold() {
        val consensus = IobConsensus.resolve(aapsIobUnits = -0.2, pkpdIobUnits = 0.5)
        val authority = DecisionPredictionAuthority(
            predTerminalMgdl = 96.0,
            eventualTerminalMgdl = 184.0,
            pkpdEventualMgdl = 125.0,
            scenarioFloorTerminalMgdl = 96.0,
            scenarioBestTerminalMgdl = 184.0,
            source = DecisionPredictionSource.SCENARIO_MEAL_UPLIFT,
            scenarioUpliftApplied = true,
            falseMealSuppression = false,
            reason = "test",
        )
        val envelope = AimiRiskEnvelopeBuilder.buildDecision(
            bg = 118.0,
            delta = -1f,
            predTerminal = 125.0,
            eventualTerminal = 125.0,
            pathBounds = PredictionPathBounds(
                pathMinRawMgdl = 35.0,
                pathMinClampedMgdl = 39.0,
                pathMinHitNumericFloor = true,
            ),
            aapsIobUnits = -0.2,
            iobConsensus = consensus,
            lgsThreshold = 70,
            naiveEbgSignGuardApplied = false,
            predictionAuthority = authority,
        )
        assertEquals(AimiRiskPhase.DECISION, envelope.phase)
        assertEquals(96.0, envelope.compositeMinMgdl, 0.001)
        assertTrue(envelope.pathMinHitNumericFloor)
        assertEquals(0.5, envelope.iobDecisionUnits, 0.001)
        assertEquals(DecisionPredictionSource.SCENARIO_MEAL_UPLIFT, envelope.decisionSource)
        assertEquals(184.0, envelope.eventualTerminalMgdl, 0.001)
    }

    @Test
    fun decisionEnvelope_lifts_clinical_floor_pred_on_confirmed_meal_rise() {
        // Field signature (report 27): bg rising, eventual/uam high, but clinical floor pred=39
        // dominated composite_min and blocked SMB. Meal-aware adjustTerminals must lift predT.
        val authority = DecisionPredictionAuthority(
            predTerminalMgdl = 39.0,
            eventualTerminalMgdl = 166.0,
            pkpdEventualMgdl = 166.0,
            scenarioFloorTerminalMgdl = 39.0,
            scenarioBestTerminalMgdl = 166.0,
            source = DecisionPredictionSource.SCENARIO_MEAL_UPLIFT,
            scenarioUpliftApplied = true,
            falseMealSuppression = false,
            reason = "meal_evidence",
        )
        val envelope = AimiRiskEnvelopeBuilder.buildDecision(
            bg = 145.0,
            delta = 3f,
            predTerminal = 166.0,
            eventualTerminal = 166.0,
            pathBounds = PredictionPathBounds(
                pathMinRawMgdl = 39.0,
                pathMinClampedMgdl = 39.0,
                pathMinHitNumericFloor = true,
            ),
            aapsIobUnits = 1.2,
            iobConsensus = IobConsensus.resolve(aapsIobUnits = 1.2, pkpdIobUnits = null),
            lgsThreshold = 90,
            naiveEbgSignGuardApplied = false,
            predictionAuthority = authority,
            mealSafetyContext = MealSafetyContext(mealModeActive = true),
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
        )
        assertEquals(145.0, envelope.compositeMinMgdl, 0.001)
        assertTrue(envelope.predTerminalMgdl > 100.0)
        assertTrue(envelope.compositeMinMgdl > 90.0)
    }

    @Test
    fun decisionEnvelope_skips_floor_uplift_when_false_meal_suppression() {
        val authority = DecisionPredictionAuthority(
            predTerminalMgdl = 39.0,
            eventualTerminalMgdl = 166.0,
            pkpdEventualMgdl = 166.0,
            scenarioFloorTerminalMgdl = 39.0,
            scenarioBestTerminalMgdl = 166.0,
            source = DecisionPredictionSource.SCENARIO_SUPPRESSED_NON_MEAL,
            scenarioUpliftApplied = false,
            falseMealSuppression = true,
            reason = "non_meal_guard",
        )
        val envelope = AimiRiskEnvelopeBuilder.buildDecision(
            bg = 145.0,
            delta = 3f,
            predTerminal = 166.0,
            eventualTerminal = 166.0,
            pathBounds = PredictionPathBounds(null, 39.0, true),
            aapsIobUnits = 0.5,
            iobConsensus = IobConsensus.resolve(aapsIobUnits = 0.5, pkpdIobUnits = null),
            lgsThreshold = 90,
            naiveEbgSignGuardApplied = false,
            predictionAuthority = authority,
            mealSafetyContext = MealSafetyContext(mealModeActive = true),
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
        )
        assertEquals(39.0, envelope.compositeMinMgdl, 0.001)
    }

    @Test
    fun earlyEnvelopeMatchesSanityTerminals() {
        val envelope = AimiRiskEnvelopeBuilder.buildEarly(
            bg = 120.0,
            delta = 0f,
            predTerminal = 110.0,
            eventualTerminal = 115.0,
            predBGs = null,
            lgsThreshold = 70,
        )
        assertEquals(AimiRiskPhase.EARLY, envelope.phase)
        assertEquals(110.0, envelope.compositeMinMgdl, 0.001)
        assertEquals(115.0, envelope.eventualTerminalMgdl, 0.001)
    }
}
