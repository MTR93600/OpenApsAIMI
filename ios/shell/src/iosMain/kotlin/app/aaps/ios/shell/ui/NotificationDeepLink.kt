package app.aaps.ios.shell.ui

import app.aaps.core.interfaces.notifications.IosNotificationDelegate
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.PendingNavigation

/**
 * Starts listening for taps on notifications, so a tap can open the screen it asked for.
 *
 * Called from app start up, just before `IosNotificationDelegate.install`: registering first means a
 * tap that launched the app is not missed in the moment between the two.
 */
internal fun installNotificationDeepLinks() {
    IosNotificationDelegate.onTapped(::parkNavigationFromNotification)
}

/**
 * Reads the screen a tapped notification asked for and parks it for the UI to pick up.
 *
 * The iOS counterpart of `ComposeMainActivity.parkNavigationFromIntent`. The route travels in the
 * notification's `userInfo` rather than in an intent extra, but the key and the string are the same
 * on both platforms, so the same parser reads it and the two sides cannot drift apart. A
 * notification that carries no route, or one this app does not know, parks nothing.
 *
 * This cannot navigate by itself. The tap may have started the app, so there is no nav graph yet;
 * all it can do is record what was wanted. [PendingNavigation] holds it until the UI takes it.
 */
internal fun parkNavigationFromNotification(userInfo: Map<String, String>) {
    val route = userInfo[PendingNavigation.EXTRA_NAVIGATE_ROUTE]
    PendingNavigation.parseRoute(route)?.let { PendingNavigation.request(it) }
}

/**
 * Holds a navigation request until there is a navigator to give it to.
 *
 * iOS needs this and Android does not. The iOS shell draws nothing at all until app protection is
 * cleared - it returns early rather than showing the app behind the prompt, so that the screen is
 * not in the app-switcher snapshot either - and the navigator is built inside that content. So
 * between a notification tap and the user typing their password there is no navigator.
 *
 * The shared root takes the request out of [PendingNavigation] as soon as it is parked, and a
 * request handed to nobody is a request lost: the user would clear the prompt and land on the
 * overview instead of on the screen they tapped.
 *
 * Only the last request is kept, for the same reason [PendingNavigation] keeps only the last one:
 * two taps mean the user wants the second.
 */
internal class NavigationRelay {

    private var navigate: ((NavigationRequest) -> Unit)? = null
    private var waiting: NavigationRequest? = null

    /** Navigates now when a navigator is attached, and otherwise keeps the request until one is. */
    fun offer(request: NavigationRequest) {
        val target = navigate
        if (target == null) waiting = request else target(request)
    }

    /**
     * Hands over the navigator, and with it anything that was waiting.
     *
     * `null` takes the navigator away again, which is what happens when the app locks itself on the
     * way to the background. A request offered after that waits for the next one.
     */
    fun attach(navigate: ((NavigationRequest) -> Unit)?) {
        this.navigate = navigate
        if (navigate == null) return
        val request = waiting ?: return
        waiting = null
        navigate(request)
    }
}
