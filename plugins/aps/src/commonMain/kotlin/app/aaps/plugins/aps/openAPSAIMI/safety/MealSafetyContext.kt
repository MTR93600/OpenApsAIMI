package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Meal / bolus context for predictive LGS tiers.
 * When active, suppresses Tier 2/3 when BG is not falling fast and remains at or above the hypo threshold.
 */
data class MealSafetyContext(
    val mealModeActive: Boolean = false,
    val manualBolusAgeMin: Double? = null,
    val mealAdvisorCarbsFresh: Boolean = false,
    val explicitMealTrigger: Boolean = false,
    val inferredMealSignal: Boolean = false,
) {
    val hasMealIntent: Boolean
        get() = mealModeActive ||
            mealAdvisorCarbsFresh ||
            explicitMealTrigger ||
            inferredMealSignal ||
            (manualBolusAgeMin != null && manualBolusAgeMin <= MANUAL_BOLUS_SUPPRESS_WINDOW_MIN)

    companion object {
        const val MANUAL_BOLUS_SUPPRESS_WINDOW_MIN = 45.0
    }
}
