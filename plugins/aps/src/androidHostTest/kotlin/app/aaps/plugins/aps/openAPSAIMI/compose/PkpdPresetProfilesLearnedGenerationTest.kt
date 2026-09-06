package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.shared.tests.TestBase
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Generation-bump cases from reference `PkpdPresetProfilesTest` (`origin/dev_OAPSAIMI` @ `c5db5a0333`).
 * Adapted to study's mockito host-test stack.
 */
class PkpdPresetProfilesLearnedGenerationTest : TestBase() {

    private val stored = mutableMapOf<DoubleKey, Double>()

    private fun preferences(): Preferences {
        val preferences = mock<Preferences>()
        whenever(preferences.put(any<DoubleNonPreferenceKey>(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<DoubleNonPreferenceKey>(0)
            if (key is DoubleKey) stored[key] = invocation.getArgument(1)
            null
        }
        whenever(preferences.get(any<DoubleKey>())).thenAnswer { invocation ->
            val key = invocation.getArgument<DoubleKey>(0)
            stored[key] ?: key.defaultValue
        }
        return preferences
    }

    @Test
    fun resetPkpdLearnedStateToInitial_bumps_the_generation() {
        stored.clear()
        val preferences = preferences()
        resetPkpdLearnedStateToInitial(preferences)
        verify(preferences, times(1)).inc(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)
    }

    @Test
    fun reclampPkpdLearnedStateToBounds_bumps_the_generation() {
        stored.clear()
        val preferences = preferences()
        reclampPkpdLearnedStateToBounds(preferences)
        verify(preferences, times(1)).inc(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)
    }

    @Test
    fun applyPkpdInsulinPreset_bumps_the_generation_once() {
        for (preset in listOf(PkpdInsulinPreset.ULTRA_FAST, PkpdInsulinPreset.RAPID, PkpdInsulinPreset.STANDARD)) {
            stored.clear()
            val preferences = preferences()
            applyPkpdInsulinPreset(preferences, preset)
            verify(preferences, times(1)).inc(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)
        }
        stored.clear()
        val customPreferences = preferences()
        applyPkpdInsulinPreset(customPreferences, PkpdInsulinPreset.CUSTOM)
        verify(customPreferences, never()).inc(LongNonKey.OApsAIMIPkpdLearnedStateGeneration)
    }
}
