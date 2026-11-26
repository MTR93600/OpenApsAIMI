package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Red Carpet restores only on a meal the person declared, never on a meal guessed from a rise. */
class RedCarpetPolicyTest {

    @Test
    fun aGuessedMealGetsNoRestore() {
        assertFalse(RedCarpetPolicy.allowed(explicitUserAction = false, declaredMealMode = false, declaredCarbsRise = false))
    }

    @Test
    fun aDeclaredMealModeKeepsTheRestore() {
        assertTrue(RedCarpetPolicy.allowed(explicitUserAction = false, declaredMealMode = true, declaredCarbsRise = false))
    }

    @Test
    fun enteredCarbsKeepTheRestore() {
        assertTrue(RedCarpetPolicy.allowed(explicitUserAction = false, declaredMealMode = false, declaredCarbsRise = true))
    }

    @Test
    fun aManualActionKeepsTheRestore() {
        assertTrue(RedCarpetPolicy.allowed(explicitUserAction = true, declaredMealMode = false, declaredCarbsRise = false))
    }
}
