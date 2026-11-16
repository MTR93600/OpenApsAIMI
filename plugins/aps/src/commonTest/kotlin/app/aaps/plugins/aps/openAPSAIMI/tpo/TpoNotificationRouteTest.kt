package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.interfaces.IntentPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.PendingNavigation
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiPreferenceTree
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * A tap on a TPO notification must open the page that shows the session and its revert button
 * ("What AIMI learned"), not the top of the AIMI settings, where nothing says what TPO changed.
 *
 * The test reads the route the two posters send, parses it as the Android and iOS hosts do, and
 * looks the sub screen up in the real AIMI settings tree with the same rule as
 * `PluginPreferencesScreen.findComposeScreen`. So it fails when the route, the parser or the place of
 * the page in the tree changes in a way that would make the tap land elsewhere.
 */
class TpoNotificationRouteTest {

    private val learned = ComposeScreenContent { }
    private val other = ComposeScreenContent { }

    private val noPlatformItems = object : AimiPlatformPreferenceItems {
        override fun item(slot: AimiPlatformPreferenceSlot): PreferenceItem? = null
    }

    private val tree: PreferenceSubScreenDef = AimiPreferenceTree(
        platformItems = noPlatformItems,
        essentials = other,
        learned = learned,
        settingsAudit = other,
        nightGrowthState = other,
    ).build(icon = null)

    /** The same walk as the shared renderer: sub screens, then a key that carries Compose content. */
    private fun PreferenceItem.findComposeScreen(key: String): ComposeScreenContent? = when (this) {
        is PreferenceSubScreenDef -> items.firstNotNullOfOrNull { it.findComposeScreen(key) }
        is IntentPreferenceKey    -> if (this.key == key) composeScreen as? ComposeScreenContent else null
        else                      -> null
    }

    @Test
    fun `a tap on a TPO notification opens the What AIMI learned page`() {
        val request = assertIs<NavigationRequest.PluginPreferences>(PendingNavigation.parseRoute(TPO_NOTIFICATION_ROUTE))

        assertEquals("OpenAPSAIMIPlugin", request.pluginKey)
        val screenKey = assertNotNull(request.composeScreenKey, "the route must name a sub screen")
        assertSame(learned, tree.findComposeScreen(screenKey), "the route must open the learned page")
    }
}
