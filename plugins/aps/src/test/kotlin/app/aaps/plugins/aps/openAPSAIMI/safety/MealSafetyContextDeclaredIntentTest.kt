package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The meal-absorption belief must only hear what the person declared. A recent SMB of the loop and the
 * engine's own previous phase both made `hasMealIntent` true, so the belief fed itself.
 */
class MealSafetyContextDeclaredIntentTest {

    @Test
    fun loopSmbAndOwnPhaseAreNotADeclaredMeal() {
        val ctx = MealSafetyContext(manualBolusAgeMin = 10.0, inferredMealSignal = true)
        assertThat(ctx.hasMealIntent).isTrue()
        assertThat(ctx.declaredMealIntent).isFalse()
    }

    @Test
    fun mealModeAdvisorCarbsAndExplicitTriggerAreDeclared() {
        assertThat(MealSafetyContext(mealModeActive = true).declaredMealIntent).isTrue()
        assertThat(MealSafetyContext(mealAdvisorCarbsFresh = true).declaredMealIntent).isTrue()
        assertThat(MealSafetyContext(explicitMealTrigger = true).declaredMealIntent).isTrue()
    }
}
