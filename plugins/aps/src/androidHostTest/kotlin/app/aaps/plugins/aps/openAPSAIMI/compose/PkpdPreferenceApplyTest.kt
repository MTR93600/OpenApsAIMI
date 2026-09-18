package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * The key types [applyPkpdPreferenceUpdate] and [readPreferenceValueAsString] must cover.
 * `UnitDoublePreferenceKey` and `LongPreferenceKey` used to fall through silently.
 */
class PkpdPreferenceApplyTest : TestBase() {

    private fun update(key: PreferenceKey, newValue: Any) = AimiAction.PreferenceUpdate(
        key = key,
        newValue = newValue,
        reason = "test",
    )

    @Test
    fun a_unit_double_key_is_written() {
        val preferences = mock<Preferences>()
        val key = mock<UnitDoublePreferenceKey>()
        assertThat(applyPkpdPreferenceUpdate(preferences, update(key, 110.0))).isTrue()
        verify(preferences).put(key, 110.0)
    }

    @Test
    fun a_long_key_is_written() {
        val preferences = mock<Preferences>()
        val key = mock<LongPreferenceKey>()
        assertThat(applyPkpdPreferenceUpdate(preferences, update(key, 42L))).isTrue()
        verify(preferences).put(key, 42L)
    }

    @Test
    fun a_double_key_is_still_written() {
        val preferences = mock<Preferences>()
        assertThat(applyPkpdPreferenceUpdate(preferences, update(DoubleKey.OApsAIMIMaxSMB, 1.6))).isTrue()
        verify(preferences).put(DoubleKey.OApsAIMIMaxSMB, 1.6)
    }

    @Test
    fun a_value_of_the_wrong_type_is_refused() {
        val preferences = mock<Preferences>()
        assertThat(applyPkpdPreferenceUpdate(preferences, update(DoubleKey.OApsAIMIMaxSMB, "1.6"))).isFalse()
    }

    @Test
    fun the_old_value_of_a_double_key_is_read() {
        val preferences = mock<Preferences>()
        whenever(preferences.get(DoubleKey.OApsAIMIMaxSMB)).thenReturn(2.0)
        assertThat(readPreferenceValueAsString(preferences, DoubleKey.OApsAIMIMaxSMB)).isEqualTo("2.0")
    }

    @Test
    fun the_old_value_of_a_unit_double_key_is_read() {
        val preferences = mock<Preferences>()
        val key = mock<UnitDoublePreferenceKey>()
        whenever(preferences.get(key)).thenReturn(100.0)
        assertThat(readPreferenceValueAsString(preferences, key)).isEqualTo("100.0")
    }

    @Test
    fun the_old_value_of_a_long_key_is_read() {
        val preferences = mock<Preferences>()
        val key = mock<LongPreferenceKey>()
        whenever(preferences.get(key)).thenReturn(7L)
        assertThat(readPreferenceValueAsString(preferences, key)).isEqualTo("7")
    }

    @Test
    fun an_unreadable_key_type_gives_an_empty_string() {
        val preferences = mock<Preferences>()
        val key = mock<PreferenceKey>()
        assertThat(readPreferenceValueAsString(preferences, key)).isEmpty()
    }
}
