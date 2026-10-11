package app.aaps.core.ui.compose.navigation

import platform.UserNotifications.UNMutableNotificationContent

/**
 * Says which screen this notification should open when the user taps it.
 *
 * This is the iOS half of a notification deep link, and the writing half of
 * [PendingNavigation.parseRoute]. Android puts the route in an intent extra; iOS has no intents, so
 * the route travels in the notification's own `userInfo` dictionary - under the same key, in the
 * same `plugin_preferences/<pluginKey>` form, read back by the same parser. One key and one parser
 * for both platforms is the point: a tap has to land on the same screen on both phones, and two
 * route formats would drift apart the first time one of them changed.
 *
 * It lives here, next to the key and the parser, rather than beside the code that posts
 * notifications, because there is more than one poster: the shared notification platform, the loop
 * notifier and the AIMI plugins all build their own content.
 *
 * Whatever else is in `userInfo` is kept, because the setter replaces the whole dictionary and a
 * caller may be carrying its own values in there.
 *
 * [route] is a route string such as `plugin_preferences/OpenAPSAIMIPlugin`, which
 * `AppRoute.PluginPreferences.createRoute` builds.
 */
fun UNMutableNotificationContent.setNavigationRoute(route: String) {
    val merged = mutableMapOf<Any?, Any?>()
    for (entry in userInfo.entries) merged[entry.key] = entry.value
    merged[PendingNavigation.EXTRA_NAVIGATE_ROUTE] = route
    setUserInfo(merged)
}
