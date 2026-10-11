package app.aaps.ios.shell.ui

import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.PendingNavigation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A tapped notification has to reach the same screen on iPhone as on Android.
 *
 * Android reads the route from an intent extra and parks it; iOS reads it from the notification's
 * `userInfo` and parks it the same way. Both sides use one key and one parser, so what is left to
 * check here is the iOS half: that the route is found, that a notification without one is left
 * alone, and that a request made while the app is still locked is not thrown away.
 *
 * `PendingNavigation` is a shared object, so each test clears it. Without that a parked request
 * would leak into the next test and the order of the tests would start to matter.
 */
class NotificationDeepLinkTest {

    @BeforeTest
    fun clear() {
        PendingNavigation.consume()
    }

    @AfterTest
    fun clearAfter() {
        PendingNavigation.consume()
    }

    @Test
    fun `a notification carrying a route parks it`() {
        parkNavigationFromNotification(mapOf(PendingNavigation.EXTRA_NAVIGATE_ROUTE to "plugin_preferences/OpenAPSAIMIPlugin"))

        assertEquals(NavigationRequest.PluginPreferences("OpenAPSAIMIPlugin"), PendingNavigation.consume())
    }

    /** Most notifications say nothing about a screen. Those must open the app and leave it alone. */
    @Test
    fun `a notification carrying nothing parks nothing`() {
        parkNavigationFromNotification(emptyMap())

        assertNull(PendingNavigation.consume())
    }

    /** Anything else in `userInfo` belongs to whoever posted the notification. */
    @Test
    fun `other values in the notification are ignored`() {
        parkNavigationFromNotification(mapOf("something-else" to "plugin_preferences/OpenAPSAIMIPlugin"))

        assertNull(PendingNavigation.consume())
    }

    /** A route this version does not know is not guessed at. */
    @Test
    fun `an unknown route parks nothing`() {
        parkNavigationFromNotification(mapOf(PendingNavigation.EXTRA_NAVIGATE_ROUTE to "some_other_screen/42"))

        assertNull(PendingNavigation.consume())
    }

    // ---------------------------------------------------------------------------------------------
    // Holding the request until there is a navigator
    // ---------------------------------------------------------------------------------------------

    /** Records where it was asked to go, so a test can tell "navigated" from "lost". */
    private class Target {

        val went = mutableListOf<NavigationRequest>()

        fun go(request: NavigationRequest) {
            went += request
        }
    }

    @Test
    fun `a request made with a navigator attached goes straight there`() {
        val target = Target()
        val relay = NavigationRelay()
        relay.attach(target::go)

        relay.offer(NavigationRequest.PluginPreferences("A"))

        assertEquals(listOf(NavigationRequest.PluginPreferences("A")), target.went)
    }

    /**
     * The case this class exists for: the tap happens while the app is still behind its password
     * prompt, so there is no navigator yet. Losing the request here would land the user on the
     * overview instead of on the screen they tapped.
     */
    @Test
    fun `a request made before the navigator arrives waits for it`() {
        val target = Target()
        val relay = NavigationRelay()

        relay.offer(NavigationRequest.PluginPreferences("A"))
        assertEquals(emptyList<NavigationRequest>(), target.went)

        relay.attach(target::go)
        assertEquals(listOf(NavigationRequest.PluginPreferences("A")), target.went)
    }

    /** It must be acted on once. A second navigator must not repeat the first one's journey. */
    @Test
    fun `a waiting request is delivered only once`() {
        val target = Target()
        val relay = NavigationRelay()

        relay.offer(NavigationRequest.PluginPreferences("A"))
        relay.attach(target::go)
        relay.attach(null)
        relay.attach(target::go)

        assertEquals(listOf(NavigationRequest.PluginPreferences("A")), target.went)
    }

    /** Two taps before the app is open mean the user wants the second one. */
    @Test
    fun `the last waiting request wins`() {
        val target = Target()
        val relay = NavigationRelay()

        relay.offer(NavigationRequest.PluginPreferences("A"))
        relay.offer(NavigationRequest.PluginPreferences("B"))
        relay.attach(target::go)

        assertEquals(listOf(NavigationRequest.PluginPreferences("B")), target.went)
    }

    /** The app locks itself when it goes to the background, and then there is nowhere to send one. */
    @Test
    fun `a request made after the navigator goes away waits for the next one`() {
        val target = Target()
        val relay = NavigationRelay()
        relay.attach(target::go)
        relay.attach(null)

        relay.offer(NavigationRequest.PluginPreferences("A"))
        assertEquals(emptyList<NavigationRequest>(), target.went)

        relay.attach(target::go)
        assertEquals(listOf(NavigationRequest.PluginPreferences("A")), target.went)
    }
}
