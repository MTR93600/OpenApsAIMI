package app.aaps.plugins.constraints.dstHelper

import app.aaps.core.interfaces.aps.Loop
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import kotlin.time.Instant

class DstHelperPluginTest : TestBaseWithProfile() {

    @Mock lateinit var loop: Loop

    private lateinit var plugin: DstHelperPlugin

    private val rome = TimeZone.of("Europe/Rome")

    @BeforeEach
    fun mock() {
        plugin = DstHelperPlugin(aapsLogger, rh, preferences, activePlugin, notificationManager, loop, profileFunction)
    }

    /** Local time in Rome. A time in the spring gap is moved forward, as the lenient date parser did. */
    private fun romeTime(hour: Int, minute: Int): Instant = LocalDateTime(2018, 3, 25, hour, minute).toInstant(rome)

    /** Summer time started in Rome on 2018-03-25 at 02:00, when the clock jumped to 03:00. */
    @Test
    fun runTest() {
        romeTime(1, 55).let {
            assertThat(plugin.wasDST(it, rome)).isFalse()
            assertThat(plugin.willBeDST(it, rome)).isTrue()
        }
        romeTime(3, 5).let {
            assertThat(plugin.wasDST(it, rome)).isTrue()
            assertThat(plugin.willBeDST(it, rome)).isFalse()
        }
        romeTime(2, 5).let { // Cannot happen!!!
            assertThat(plugin.wasDST(it, rome)).isTrue()
            assertThat(plugin.willBeDST(it, rome)).isFalse()
        }
        romeTime(5, 55).let {
            assertThat(plugin.wasDST(it, rome)).isTrue()
            assertThat(plugin.willBeDST(it, rome)).isFalse()
        }
        romeTime(6, 5).let {
            assertThat(plugin.wasDST(it, rome)).isFalse()
            assertThat(plugin.willBeDST(it, rome)).isFalse()
        }
    }

    /** The autumn change is found too: Rome went back from 03:00 to 02:00 on 2018-10-28. */
    @Test
    fun autumnChangeIsFound() {
        val before = LocalDateTime(2018, 10, 28, 0, 30).toInstant(rome)
        assertThat(plugin.willBeDST(before, rome)).isTrue()
        assertThat(plugin.wasDST(before, rome)).isFalse()
    }
}
