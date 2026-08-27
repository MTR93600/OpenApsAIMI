package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Centralised hypo / prediction guard used by `app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2`
 * and [HighBgOverride]. Delegates to [PredictiveHypoEvaluator] for a single source of truth.
 */
object HypoGuard {

    /**
     * @param hypo effective hypo guard threshold (mg/dL), not the raw floor; caller supplies [HypoThresholdMath] output when needed.
     */
    fun isBelowHypoThreshold(
        bgNow: Double,
        predicted: Double,
        eventual: Double,
        hypo: Double,
        delta: Double,
        mealContext: MealSafetyContext = MealSafetyContext(),
    ): Boolean = PredictiveHypoEvaluator.isBelowHypoThreshold(
        bgNow = bgNow,
        predicted = predicted,
        eventual = eventual,
        hypo = hypo,
        delta = delta,
        mealContext = mealContext,
    )
}
