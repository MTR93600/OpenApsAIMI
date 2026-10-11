package app.aaps.core.interfaces.notifications

import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationDismissActionIdentifier
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Routing between the handlers that share iOS's one delegate slot.
 *
 * The reason this class exists is that `setDelegate` replaces silently: two owners means the second
 * wins and the first one's callbacks stop, with no error anywhere. So what is tested here is that
 * every registered handler is reachable, and that one claiming a response stops the others seeing it.
 *
 * `dispatch` is exercised directly rather than through a real `UNNotificationResponse`, which cannot
 * be constructed outside a delivered notification.
 */
class IosNotificationDelegateTest {

    @BeforeTest
    fun clear() = IosNotificationDelegate.reset()

    @AfterTest
    fun clearAfter() = IosNotificationDelegate.reset()

    @Test
    fun `with no handlers nothing claims a response`() {
        assertFalse(IosNotificationDelegate.dispatch("action", "notification"))
    }

    @Test
    fun `a handler that claims a response reports it handled`() {
        IosNotificationDelegate.register(emptySet()) { _, _ -> true }

        assertTrue(IosNotificationDelegate.dispatch("action", "notification"))
    }

    /** A handler for another notification's actions must not swallow this one. */
    @Test
    fun `a handler that declines lets the next one see it`() {
        val seen = mutableListOf<String>()
        IosNotificationDelegate.register(emptySet()) { _, _ -> seen += "first"; false }
        IosNotificationDelegate.register(emptySet()) { _, _ -> seen += "second"; true }

        assertTrue(IosNotificationDelegate.dispatch("action", "notification"))
        assertEquals(listOf("first", "second"), seen)
    }

    /** Once claimed, later handlers are not consulted - that is what keeps ordering meaningful. */
    @Test
    fun `a claimed response stops at the handler that took it`() {
        val seen = mutableListOf<String>()
        IosNotificationDelegate.register(emptySet()) { _, _ -> seen += "first"; true }
        IosNotificationDelegate.register(emptySet()) { _, _ -> seen += "second"; true }

        IosNotificationDelegate.dispatch("action", "notification")

        assertEquals(listOf("first"), seen)
    }

    // ---------------------------------------------------------------------------------------------
    // Foreground presentation
    // ---------------------------------------------------------------------------------------------

    /**
     * Each flag is checked on its own rather than against one combined number, so a failure names
     * which part of the presentation was lost. Answering `willPresent` at all is what stops iOS
     * showing nothing while AAPS is frontmost; these three are what it should show.
     */
    @Test
    fun `a notification arriving while the app is in front is shown and listed and keeps its sound`() {
        val options = IosNotificationDelegate.PRESENTATION_OPTIONS

        assertEquals(
            UNNotificationPresentationOptionBanner,
            options and UNNotificationPresentationOptionBanner,
            "no banner while the app is in front"
        )
        assertEquals(
            UNNotificationPresentationOptionList,
            options and UNNotificationPresentationOptionList,
            "not kept in Notification Centre"
        )
        assertEquals(
            UNNotificationPresentationOptionSound,
            options and UNNotificationPresentationOptionSound,
            "the notification's own sound is suppressed"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Taps that open the app
    // ---------------------------------------------------------------------------------------------

    /**
     * What a notification was carrying has to reach the observer, because that is where the route
     * is. Without it a tap opens the app on whatever screen it was last on, which is what iOS did
     * while Android already navigated.
     */
    @Test
    fun `a tap hands the observer what the notification was carrying`() {
        var seen: Map<String, String>? = null
        IosNotificationDelegate.onTapped { seen = it }

        IosNotificationDelegate.dispatch(
            actionId = UNNotificationDefaultActionIdentifier,
            notificationId = "notification",
            userInfo = mapOf("extra_navigate_route" to "plugin_preferences/OpenAPSAIMIPlugin")
        )

        assertEquals(mapOf("extra_navigate_route" to "plugin_preferences/OpenAPSAIMIPlugin"), seen)
    }

    /** Every observer hears a tap. Nobody takes it away from anybody, unlike the handlers. */
    @Test
    fun `every tap observer hears the tap`() {
        var first = false
        var second = false
        IosNotificationDelegate.onTapped { first = true }
        IosNotificationDelegate.onTapped { second = true }

        IosNotificationDelegate.dispatch(UNNotificationDefaultActionIdentifier, "notification")

        assertTrue(first)
        assertTrue(second)
    }

    /** A button on the notification answers it - "ignore carbs for 15 minutes" - and opens nothing. */
    @Test
    fun `pressing a button on the notification is not a tap`() {
        var tapped = false
        IosNotificationDelegate.onTapped { tapped = true }

        IosNotificationDelegate.dispatch("aaps-ignore-15", "notification")

        assertFalse(tapped)
    }

    /** Swiping it away is the user getting rid of it, not asking to see a screen. */
    @Test
    fun `swiping the notification away is not a tap`() {
        var tapped = false
        IosNotificationDelegate.onTapped { tapped = true }

        IosNotificationDelegate.dispatch(UNNotificationDismissActionIdentifier, "notification")

        assertFalse(tapped)
    }

    /** The two paths are independent: watching for taps must not swallow anybody's response. */
    @Test
    fun `a tap still reaches the handlers`() {
        var handlerSaw = false
        IosNotificationDelegate.onTapped { }
        IosNotificationDelegate.register(emptySet()) { _, _ -> handlerSaw = true; false }

        IosNotificationDelegate.dispatch(UNNotificationDefaultActionIdentifier, "notification")

        assertTrue(handlerSaw)
    }

    /** Otherwise one test's observer would still be listening during the next one. */
    @Test
    fun `reset forgets the tap observers`() {
        var tapped = false
        IosNotificationDelegate.onTapped { tapped = true }

        IosNotificationDelegate.reset()
        IosNotificationDelegate.dispatch(UNNotificationDefaultActionIdentifier, "notification")

        assertFalse(tapped)
    }

    /** Both real users register: the notification platform and the loop notifier. */
    @Test
    fun `handlers from different modules coexist`() {
        var platformSaw = false
        var notifierSaw = false
        IosNotificationDelegate.register(emptySet()) { _, _ -> platformSaw = true; false }
        IosNotificationDelegate.register(emptySet()) { _, _ -> notifierSaw = true; false }

        IosNotificationDelegate.dispatch("action", "notification")

        assertTrue(platformSaw)
        assertTrue(notifierSaw)
    }
}
