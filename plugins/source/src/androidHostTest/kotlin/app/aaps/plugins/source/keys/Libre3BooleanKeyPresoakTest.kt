package app.aaps.plugins.source.keys

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The two pre-soak switches stay engineering, off, and out of an export, as on the reference.
 */
class Libre3BooleanKeyPresoakTest {

    @Test
    fun `presoak and keep alive are engineering switches that default to off and are not exported`() {
        val presoak = Libre3BooleanKey.PresoakEnabled
        val keepAlive = Libre3BooleanKey.KeepSessionAlive

        assertThat(presoak.key).isEqualTo("libre3_presoak_enabled")
        assertThat(presoak.defaultValue).isFalse()
        assertThat(presoak.engineeringModeOnly).isTrue()
        assertThat(presoak.exportable).isFalse()

        assertThat(keepAlive.key).isEqualTo("libre3_keep_session_alive")
        assertThat(keepAlive.defaultValue).isFalse()
        assertThat(keepAlive.engineeringModeOnly).isTrue()
        assertThat(keepAlive.exportable).isFalse()
    }
}
