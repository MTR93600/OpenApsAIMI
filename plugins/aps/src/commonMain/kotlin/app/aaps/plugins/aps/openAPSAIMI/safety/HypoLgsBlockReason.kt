package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.safety.PredictiveHypoEvaluator.floor

/**
 * Explains which signal triggered predictive LGS (Phase 0 HTR).
 */
enum class HypoLgsBlockReason {
    BG_NOW,
    PREDICTED_AND_EVENTUAL,
    PREDICTED_MIN_CURVE,
    FAST_FALL,
    ;

    companion object {
        fun detect(
            bgNow: Double,
            predicted: Double,
            eventual: Double,
            minPredictedCurve: Double?,
            hypo: Double,
            delta: Double,
            mealContext: MealSafetyContext = MealSafetyContext(),
            ignoreMinPredictedCurve: Boolean = false,
        ): HypoLgsBlockReason? {
            if (bgNow <= floor(hypo)) return BG_NOW

            val input = PredictiveHypoInput(
                bgNow = bgNow,
                predicted = predicted,
                eventual = eventual,
                hypoThreshold = hypo,
                delta = delta,
                mealContext = mealContext,
            )
            val suppression = PredictiveHypoEvaluator.evaluateSuppression(input)
            if (suppression.risingFast) return null

            val hypoFloor = floor(hypo)
            val strongFuture = predicted <= hypoFloor && eventual <= hypoFloor
            if (strongFuture && !suppression.suppressed) {
                return PREDICTED_AND_EVENTUAL
            }
            if (!ignoreMinPredictedCurve &&
                minPredictedCurve != null && minPredictedCurve.isFinite() &&
                minPredictedCurve <= hypoFloor &&
                bgNow > hypo + 15.0 &&
                !suppression.suppressed
            ) {
                return PREDICTED_MIN_CURVE
            }
            if (delta <= PredictiveHypoConstants.FAST_FALL_DELTA && predicted <= hypo) {
                return FAST_FALL
            }
            return null
        }
    }
}
