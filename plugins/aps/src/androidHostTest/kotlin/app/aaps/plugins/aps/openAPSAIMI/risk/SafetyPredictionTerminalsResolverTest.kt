package app.aaps.plugins.aps.openAPSAIMI.risk

import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertaintyTreeState
import app.aaps.plugins.aps.openAPSAIMI.patient.MealRiseGeometry
import app.aaps.plugins.aps.openAPSAIMI.patient.MealTerminalsAgree
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafetyPredictionTerminalsResolverTest {

    @Test
    fun thomasPreSpike_replacesFloorEventualWithUamTerminal() {
        val result = SafetyPredictionTerminalsResolver.resolve(
            bg = 119.0,
            delta = 0.6f,
            sanityPred = 65.0,
            sanityEventual = 39.0,
            uamTerminal = 128.0,
            mealContext = MealSafetyContext(mealModeActive = true),
        )
        assertTrue(result.mealRiseConfirmed)
        assertEquals(128.0, result.eventualBg, 0.001)
        assertEquals(119.0, result.compositeMinMgdl, 0.001)
    }

    @Test
    fun noMealRise_keepsInsulinOnlyTerminals() {
        val result = SafetyPredictionTerminalsResolver.resolve(
            bg = 118.0,
            delta = -0.5f,
            sanityPred = 65.0,
            sanityEventual = 39.0,
            uamTerminal = 120.0,
            mealContext = MealSafetyContext(),
        )
        assertFalse(result.mealRiseConfirmed)
        assertEquals(39.0, result.eventualBg, 0.001)
        assertEquals(39.0, result.compositeMinMgdl, 0.001)
    }

    @Test
    fun risingDeltaWithoutMealContext_doesNotConfirmInStableBandWithZeroCob() {
        assertFalse(
            SafetyPredictionTerminalsResolver.isMealRiseConfirmed(
                bg = 118.0,
                delta = 2.5f,
                mealContext = MealSafetyContext(),
                cobG = 0.0,
            ),
        )
    }

    @Test
    fun risingDeltaWithoutMealContext_confirmsMealRise() {
        assertTrue(
            SafetyPredictionTerminalsResolver.isMealRiseConfirmed(
                bg = 137.0,
                delta = 5.0f,
                mealContext = MealSafetyContext(),
            ),
        )
    }

    @Test
    fun activePhaseWhileFalling_doesNotConfirmMealRise_desticky() {
        assertFalse(
            SafetyPredictionTerminalsResolver.isMealRiseConfirmed(
                bg = 220.0,
                delta = -3.0f,
                mealContext = MealSafetyContext(),
                mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                cobG = 0.0,
            ),
        )
    }

    @Test
    fun mealCertaintyMed_overridesLegacyGeometry() {
        val med = MealCertainty(
            level = MealCertaintyLevel.MED,
            treeState = MealCertaintyTreeState.MEAL_PROBABLE,
            absorptionPhase = MealAbsorptionPhase.NONE,
            riseGeometry = MealRiseGeometry.WEAK,
            terminalsAgree = MealTerminalsAgree.OK,
            effortVeto = false,
            softCorroboration = false,
        )
        assertTrue(
            SafetyPredictionTerminalsResolver.isMealRiseConfirmed(
                bg = 100.0,
                delta = -1.0f,
                mealContext = MealSafetyContext(),
                mealCertainty = med,
            ),
        )
    }

    @Test
    fun mealCertaintyNone_fallsThroughToDeclaredMealIntent() {
        val none = MealCertainty.NONE
        assertTrue(
            SafetyPredictionTerminalsResolver.isMealRiseConfirmed(
                bg = 140.0,
                delta = 2.0f,
                mealContext = MealSafetyContext(mealModeActive = true),
                mealCertainty = none,
            ),
        )
    }

    @Test
    fun mealCertaintyLow_doesNotShortCircuitFalseOnDeclaredMeal() {
        val low = MealCertainty(
            level = MealCertaintyLevel.LOW,
            treeState = MealCertaintyTreeState.NONE,
            absorptionPhase = MealAbsorptionPhase.NONE,
            riseGeometry = MealRiseGeometry.WEAK,
            terminalsAgree = MealTerminalsAgree.UNKNOWN,
            effortVeto = false,
            softCorroboration = false,
        )
        assertTrue(
            SafetyPredictionTerminalsResolver.isMealRiseConfirmed(
                bg = 150.0,
                delta = 1.0f,
                mealContext = MealSafetyContext(mealModeActive = true),
                mealCertainty = low,
            ),
        )
    }

    @Test
    fun resolveFromScenario_usesFloorForSafetyAndBestForMealUplift() {
        val floor = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.CLINICAL_FLOOR,
            listOf(119.0, 39.0),
        )
        val best = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.SCENARIO_BEST,
            listOf(119.0, 155.0),
        )
        val projection = ScenarioProjectionPair(
            clinicalFloor = floor,
            scenarioBest = best,
            contributors = emptyList(),
            cobPointsMgdl = listOf(119, 130),
            ztPointsMgdl = listOf(119, 39),
        )
        val result = SafetyPredictionTerminalsResolver.resolveFromScenario(
            bg = 119.0,
            delta = 0.6f,
            mealContext = MealSafetyContext(mealModeActive = true),
            projection = projection,
        )
        assertEquals(119.0, result.compositeMinMgdl, 0.001)
        assertTrue(result.eventualBg >= 119.0)
    }

    @Test
    fun adjustForDecisionEnvelope_lifts_floor_pred_for_meal_rise() {
        val authority = DecisionPredictionAuthority(
            predTerminalMgdl = 39.0,
            eventualTerminalMgdl = 166.0,
            pkpdEventualMgdl = 166.0,
            scenarioFloorTerminalMgdl = 39.0,
            scenarioBestTerminalMgdl = 166.0,
            source = DecisionPredictionSource.SCENARIO_MEAL_UPLIFT,
            scenarioUpliftApplied = true,
            falseMealSuppression = false,
            reason = "test",
        )
        val (adjPred, adjEventual) = SafetyPredictionTerminalsResolver.adjustForDecisionEnvelope(
            bg = 145.0,
            delta = 3f,
            predForDecision = 39.0,
            eventualForDecision = 166.0,
            predictionAuthority = authority,
            mealContext = MealSafetyContext(mealModeActive = true),
            mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
        )
        assertTrue(adjPred > 100.0)
        assertEquals(166.0, adjEventual, 0.001)
        val composite = PredictionPathMath.compositeMinMgdl(145.0, adjPred, adjEventual)
        assertEquals(145.0, composite, 0.001)
    }

    @Test
    fun resolveFromScenario_capsBestTerminalDuringPostHypoRebound() {
        val floor = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.CLINICAL_FLOOR,
            listOf(119.0, 39.0),
        )
        val best = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.SCENARIO_BEST,
            listOf(119.0, 339.0),
        )
        val projection = ScenarioProjectionPair(
            clinicalFloor = floor,
            scenarioBest = best,
            contributors = emptyList(),
            cobPointsMgdl = listOf(119, 130),
            ztPointsMgdl = listOf(119, 39),
        )
        val result = SafetyPredictionTerminalsResolver.resolveFromScenario(
            bg = 119.0,
            delta = 5.0f,
            mealContext = MealSafetyContext(),
            projection = projection,
            targetBgMgdl = 100.0,
            minBgLookback75m = 54.0,
            hasIndependentMealEvidence = false,
        )
        assertTrue(result.eventualBg < 200.0)
        assertTrue(result.eventualBg > 119.0)
    }
}
