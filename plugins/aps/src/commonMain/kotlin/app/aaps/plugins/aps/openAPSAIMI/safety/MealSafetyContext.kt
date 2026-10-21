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

    /**
     * A meal the person declared: a meal mode, fresh advisor carbs, or an explicit trigger.
     *
     * Unlike [hasMealIntent] it leaves out [manualBolusAgeMin] (which counts the loop's own SMBs) and
     * [inferredMealSignal] (which reads the meal-absorption phase of the previous tick). The
     * meal-absorption belief reads this one: with [hasMealIntent] it fed on its own output.
     */
    val declaredMealIntent: Boolean
        get() = mealModeActive || mealAdvisorCarbsFresh || explicitMealTrigger

    companion object {
        const val MANUAL_BOLUS_SUPPRESS_WINDOW_MIN = 45.0
    }
}
