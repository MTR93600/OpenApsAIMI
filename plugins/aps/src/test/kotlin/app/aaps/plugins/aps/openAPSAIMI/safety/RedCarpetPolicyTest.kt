package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Red Carpet restores only on a meal the person declared, never on a meal guessed from a rise. */
class RedCarpetPolicyTest {

    @Test
    fun aGuessedMealGetsNoRestore() {
        assertThat(RedCarpetPolicy.allowed(explicitUserAction = false, declaredMealMode = false, declaredCarbsRise = false)).isFalse()
    }

    @Test
    fun aDeclaredMealModeKeepsTheRestore() {
        assertThat(RedCarpetPolicy.allowed(explicitUserAction = false, declaredMealMode = true, declaredCarbsRise = false)).isTrue()
    }

    @Test
    fun enteredCarbsKeepTheRestore() {
        assertThat(RedCarpetPolicy.allowed(explicitUserAction = false, declaredMealMode = false, declaredCarbsRise = true)).isTrue()
    }

    @Test
    fun aManualActionKeepsTheRestore() {
        assertThat(RedCarpetPolicy.allowed(explicitUserAction = true, declaredMealMode = false, declaredCarbsRise = false)).isTrue()
    }
}
