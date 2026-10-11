package app.aaps.core.ui.compose.navigation

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [PendingNavigation] is a shared object, so each test clears it afterwards. Without that a parked
 * request would leak into the next test and the order of the tests would start to matter.
 */
class PendingNavigationTest {

    @AfterTest
    fun clear() {
        PendingNavigation.consume()
    }

    @Test
    fun parsesAPluginPreferencesRoute() {
        assertEquals(
            NavigationRequest.PluginPreferences("OpenAPSAIMIPlugin"),
            PendingNavigation.parseRoute("plugin_preferences/OpenAPSAIMIPlugin")
        )
    }

    /** The key is the plugin's class simple name, so a dotted key has to survive untouched. */
    @Test
    fun keepsTheWholeKey() {
        assertEquals(
            NavigationRequest.PluginPreferences("my.plugin.key"),
            PendingNavigation.parseRoute("plugin_preferences/my.plugin.key")
        )
    }

    /**
     * The two directions must stay each other's inverse. A poster builds the route, the host parses
     * it back, and they live on different platforms - so a change to one spelling that missed the
     * other would only show up as a notification that opens nothing.
     */
    @Test
    fun buildingAndParsingARouteRoundTrips() {
        val route = PendingNavigation.pluginPreferencesRoute("OpenAPSAIMIPlugin")

        assertEquals("plugin_preferences/OpenAPSAIMIPlugin", route)
        assertEquals(NavigationRequest.PluginPreferences("OpenAPSAIMIPlugin"), PendingNavigation.parseRoute(route))
    }

    @Test
    fun answersNullForNoRouteAtAll() {
        assertNull(PendingNavigation.parseRoute(null))
    }

    /** A route this code does not know must not be guessed at. */
    @Test
    fun answersNullForAnUnknownRoute() {
        assertNull(PendingNavigation.parseRoute("some_other_screen/42"))
    }

    /** A prefix with nothing after it names no plugin, so it is not a destination. */
    @Test
    fun answersNullForAnEmptyKey() {
        assertNull(PendingNavigation.parseRoute("plugin_preferences/"))
    }

    @Test
    fun consumeClearsTheRequestSoItIsActedOnOnce() {
        PendingNavigation.request(NavigationRequest.PluginPreferences("A"))

        assertEquals(NavigationRequest.PluginPreferences("A"), PendingNavigation.consume())
        assertNull(PendingNavigation.consume())
    }

    /** Tapping a second notification before the first is taken means the user wants the second. */
    @Test
    fun aSecondRequestReplacesOneNotYetTaken() {
        PendingNavigation.request(NavigationRequest.PluginPreferences("A"))
        PendingNavigation.request(NavigationRequest.PluginPreferences("B"))

        assertEquals(NavigationRequest.PluginPreferences("B"), PendingNavigation.consume())
    }

    @Test
    fun pendingExposesTheParkedRequest() {
        assertNull(PendingNavigation.pending.value)

        PendingNavigation.request(NavigationRequest.PluginPreferences("A"))
        assertEquals(NavigationRequest.PluginPreferences("A"), PendingNavigation.pending.value)

        PendingNavigation.consume()
        assertNull(PendingNavigation.pending.value)
    }
}
