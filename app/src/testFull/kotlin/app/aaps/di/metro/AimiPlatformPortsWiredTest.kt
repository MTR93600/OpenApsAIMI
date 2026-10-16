package app.aaps.di.metro

import app.aaps.plugins.aps.openAPSAIMI.OpenAPSAIMIPlugin
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The AIMI plugin moved to commonMain, and the Android-only parts it used to call directly now
 * arrive as ports. This checks the graph really hands those ports in on Android.
 *
 * ## Why this test exists
 * A port that is not bound does not break the build. The plugin keeps running and the feature
 * behind the port simply stops happening, with no error anywhere. That is the failure this file is
 * here to catch, and it has already happened more than once in this code base.
 *
 * `tickCaptureHook` is the sharp case: it is declared `AimiTickCaptureHook? = null`, so if Metro
 * ever prefers the default over the binding, the parity recorder dies in silence on Android. No
 * other `@Inject` class in this repo takes a nullable parameter with a default, so the behaviour is
 * worth pinning rather than assuming.
 *
 * Read through the real root graph, not through a hand-built plugin, because only the graph can
 * show what a phone would get.
 */
class AimiPlatformPortsWiredTest {

    @Test
    fun `android graph hands the AIMI plugin its platform ports`() {
        val plugin = testRoot().contributedPlugins[AIMI_PLUGIN_ORDER]
        assertThat(plugin).isInstanceOf(OpenAPSAIMIPlugin::class.java)

        // Private, so reflection is the only way in. Naming the fields here is deliberate: renaming
        // one without thinking about its binding should fail this test, not pass it quietly.
        for (field in PLATFORM_PORT_FIELDS) {
            val value = OpenAPSAIMIPlugin::class.java.getDeclaredField(field)
                .apply { isAccessible = true }
                .get(plugin)
            assertThat(value).isNotNull()
        }
    }

    companion object {

        /** `@MetroIntKey(250)` on `OpenAPSAIMIPlugin`. */
        private const val AIMI_PLUGIN_ORDER = 250

        /**
         * The constructor parameters that are ports onto an Android-only helper. Each one is bound
         * in `androidMain` and absent on iOS by design.
         */
        private val PLATFORM_PORT_FIELDS = listOf(
            "physioManager",
            "stepsManager",
            "aimiMlTrainingScheduler",
            "storageHelper",
            "auditorNotificationManager",
            "platformItems",
            "tickCaptureHook",
        )
    }
}
