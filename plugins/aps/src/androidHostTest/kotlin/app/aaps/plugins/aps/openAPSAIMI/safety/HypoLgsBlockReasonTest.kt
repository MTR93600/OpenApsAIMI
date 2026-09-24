package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HypoLgsBlockReasonTest {

    @Test
    fun detect_ignores_min_curve_when_htr_or_rbt_already_invalidated_it() {
        val reason = HypoLgsBlockReason.detect(
            bgNow = 214.0,
            predicted = 156.0,
            eventual = 164.0,
            minPredictedCurve = 62.0,
            hypo = 75.0,
            delta = 1.8,
            ignoreMinPredictedCurve = true,
        )

        assertThat(reason).isNull()
    }

    @Test
    fun detect_keeps_min_curve_block_when_it_is_not_explicitly_invalidated() {
        val reason = HypoLgsBlockReason.detect(
            bgNow = 109.0,
            predicted = 138.0,
            eventual = 142.0,
            minPredictedCurve = 62.0,
            hypo = 75.0,
            delta = 0.1,
        )

        assertThat(reason).isEqualTo(HypoLgsBlockReason.PREDICTED_MIN_CURVE)
    }

    @Test
    fun detect_drops_min_curve_block_when_predictive_meal_suppression_is_active() {
        val reason = HypoLgsBlockReason.detect(
            bgNow = 119.0,
            predicted = 39.0,
            eventual = 39.0,
            minPredictedCurve = 62.0,
            hypo = 90.0,
            delta = 0.6,
            mealContext = MealSafetyContext(inferredMealSignal = true),
        )

        assertThat(reason).isNull()
    }
}
