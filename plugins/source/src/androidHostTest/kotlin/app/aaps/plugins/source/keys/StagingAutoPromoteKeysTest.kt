package app.aaps.plugins.source.keys

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The automatic switch from a pre-soak sensor stops the sensor in use, so it must stay off until
 * the user turns it on. These defaults match `dev_OAPSAIMI` at `a88a0e588c`.
 */
class StagingAutoPromoteKeysTest {

    @Test
    fun `the automatic switch is off by default for both sensors`() {
        assertThat(DexcomOnePlusBooleanKey.AutoPromote.defaultValue).isFalse()
        assertThat(Libre3BooleanKey.AutoPromote.defaultValue).isFalse()
    }

    @Test
    fun `the default delay is 12 hours for ONE plus and 10 hours for Libre 3`() {
        assertThat(DexcomOnePlusIntKey.AutoPromoteHours.defaultValue).isEqualTo(12)
        assertThat(Libre3IntKey.AutoPromoteHours.defaultValue).isEqualTo(10)
    }

    @Test
    fun `the delay is hidden until the switch is on`() {
        assertThat(DexcomOnePlusIntKey.AutoPromoteHours.dependency).isEqualTo(DexcomOnePlusBooleanKey.AutoPromote)
        assertThat(Libre3IntKey.AutoPromoteHours.dependency).isEqualTo(Libre3BooleanKey.AutoPromote)
        assertThat(Libre3BooleanKey.AutoPromote.dependency).isEqualTo(Libre3BooleanKey.PresoakEnabled)
    }
}
