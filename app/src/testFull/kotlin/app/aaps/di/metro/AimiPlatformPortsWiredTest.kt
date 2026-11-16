package app.aaps.di.metro

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.PendingNavigation
import app.aaps.plugins.aps.openAPSAIMI.OpenAPSAIMIPlugin
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
 *
 * The same ports are bound on iOS too (`IosAppGraph`, checked by the iOS tests of each port), so
 * "Android-only" here means the helper behind the port, not that iOS lacks the port.
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
            assertWithMessage("port '%s' was not injected, so the feature behind it is dead", field)
                .that(value)
                .isNotNull()
        }
    }

    /**
     * Step A1 (S4 review): the Essentials page that the AIMI settings tree opens gets a real profile
     * link from the graph's `AimiPlatformActions`, and a tap on it asks the navigator for the profile
     * screen. Without the link the targets would show no tap at all, and nothing else would fail.
     *
     * The Essentials link is the first row of the AIMI settings (`AimiPreferenceTree.rootItems`). Its
     * page class is internal to the plugin module, so its `onOpenProfile` is read by reflection.
     */
    @Test
    fun `android graph gives the AIMI Essentials page a working profile link`() {
        val plugin = testRoot().contributedPlugins[AIMI_PLUGIN_ORDER] as OpenAPSAIMIPlugin
        val essentialsLink = plugin.getPreferenceScreenContent().items.first() as IntentPreferenceKey
        val page = requireNotNull(essentialsLink.composeScreen) { "the Essentials link opens no page" }

        @Suppress("UNCHECKED_CAST")
        val openProfile = page.javaClass.getMethod("getOnOpenProfile").invoke(page) as (() -> Unit)?
        assertWithMessage("the Essentials page got no profile link from AimiPlatformActions").that(openProfile).isNotNull()

        PendingNavigation.consume()
        openProfile?.invoke()
        assertThat(PendingNavigation.consume()).isEqualTo(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT))
    }

    companion object {

        /** `@MetroIntKey(250)` on `OpenAPSAIMIPlugin`. */
        private const val AIMI_PLUGIN_ORDER = 250

        /**
         * The constructor parameters that are ports onto a platform helper. Each one is bound in
         * `androidMain` here; the iOS side binds its own class for each (`IosAppGraph`).
         */
        private val PLATFORM_PORT_FIELDS = listOf(
            "physioManager",
            "stepsManager",
            "aimiMlTrainingScheduler",
            "storageHelper",
            "auditorNotificationManager",
            "platformItems",
            "platformActions",
            "tickCaptureHook",
        )
    }
}
