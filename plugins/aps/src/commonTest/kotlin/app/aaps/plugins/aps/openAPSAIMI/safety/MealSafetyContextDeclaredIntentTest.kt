package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The meal-absorption belief must only hear what the person declared. A recent SMB of the loop and the
 * engine's own previous phase both made `hasMealIntent` true, so the belief fed itself.
 *
 * Source: `dev_OAPSAIMI` working tree 2026-10-21 `MealSafetyContextDeclaredIntentTest`, in `kotlin.test`.
 */
class MealSafetyContextDeclaredIntentTest {

    @Test
    fun loopSmbAndOwnPhaseAreNotADeclaredMeal() {
        val ctx = MealSafetyContext(manualBolusAgeMin = 10.0, inferredMealSignal = true)
        assertTrue(ctx.hasMealIntent)
        assertFalse(ctx.declaredMealIntent)
    }

    @Test
    fun mealModeAdvisorCarbsAndExplicitTriggerAreDeclared() {
        assertTrue(MealSafetyContext(mealModeActive = true).declaredMealIntent)
        assertTrue(MealSafetyContext(mealAdvisorCarbsFresh = true).declaredMealIntent)
        assertTrue(MealSafetyContext(explicitMealTrigger = true).declaredMealIntent)
    }
}
