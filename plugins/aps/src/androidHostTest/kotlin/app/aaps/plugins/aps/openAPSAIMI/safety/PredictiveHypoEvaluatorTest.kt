package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PredictiveHypoEvaluatorTest {

    @Test
    fun strongNowBlocksRegardlessOfPredictions() {
        assertTrue(
            PredictiveHypoEvaluator.isBelowHypoThreshold(
                bgNow = 60.0,
                predicted = 120.0,
                eventual = 120.0,
                hypo = 70.0,
                delta = 0.0,
            ),
        )
    }

    @Test
    fun risingFastBypassesLowPredictions() {
        assertFalse(
            PredictiveHypoEvaluator.isBelowHypoThreshold(
                bgNow = 100.0,
                predicted = 50.0,
                eventual = 50.0,
                hypo = 70.0,
                delta = 4.0,
            ),
        )
    }

    @Test
    fun risingModerateBypassesStrongFuture() {
        assertFalse(
            PredictiveHypoEvaluator.isBelowHypoThreshold(
                bgNow = 100.0,
                predicted = 60.0,
                eventual = 60.0,
                hypo = 70.0,
                delta = 2.0,
            ),
        )
    }

    @Test
    fun hyperArtifactBypassesStrongFutureEvenWhenDeltaNegative() {
        assertFalse(
            PredictiveHypoEvaluator.isBelowHypoThreshold(
                bgNow = 311.0,
                predicted = 87.0,
                eventual = 239.0,
                hypo = 90.0,
                delta = -1.0,
            ),
        )
    }

    @Test
    fun mealContextSuppressesPredictiveBlockWhenBgAboveThresholdAndNotFallingFast() {
        val mealContext = MealSafetyContext(mealModeActive = true)
        assertFalse(
            PredictiveHypoEvaluator.isBelowHypoThreshold(
                bgNow = 119.0,
                predicted = 39.0,
                eventual = 39.0,
                hypo = 90.0,
                delta = 0.6,
                mealContext = mealContext,
            ),
        )
    }

    @Test
    fun fastFallWithRapidDropAndPredictionAtOrBelowHypoStillBlocks() {
        assertTrue(
            PredictiveHypoEvaluator.isBelowHypoThreshold(
                bgNow = 200.0,
                predicted = 68.0,
                eventual = 150.0,
                hypo = 70.0,
                delta = -2.5,
            ),
        )
    }

    @Test
    fun lgsTier2SuppressedWhenRisingAt137() {
        val match = LgsTierRules.resolveTier(
            bgNow = 137.0,
            predNow = 65.0,
            eventualNow = 79.0,
            lgsTh = 90.0,
            delta = 5f,
            predictiveSuppressed = true,
        )
        assertTrue(match == null)
    }

    @Test
    fun lgsTier2ActiveOnFlatPreSpikeWithoutMealContext() {
        val match = LgsTierRules.resolveTier(
            bgNow = 119.0,
            predNow = 39.0,
            eventualNow = 39.0,
            lgsTh = 90.0,
            delta = 0.6f,
            predictiveSuppressed = false,
        )
        assertTrue(match?.tier == LgsTierKind.TIER2_PRED_LOW || match?.tier == LgsTierKind.TIER3_EVENTUAL_LOW)
    }

    @Test
    fun tier2DoesNotHaltRemainingPipeline() {
        assertFalse(LgsTierRules.haltRemainingPipeline(LgsTierKind.TIER2_PRED_LOW))
    }

    @Test
    fun tier1HaltsRemainingPipeline() {
        assertTrue(LgsTierRules.haltRemainingPipeline(LgsTierKind.TIER1_BG_REAL))
    }
}
