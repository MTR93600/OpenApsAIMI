package app.aaps.core.ui.compose.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Where a tapped notification parks the screen it wants, until the UI is ready to go there.
 *
 * A notification is tapped while the app may not be running. The nav graph does not exist yet at
 * that moment, so the tap cannot navigate - it can only say where it wanted to go. This object
 * holds that one request until the host has a navigator, and the host then takes it.
 *
 * It is deliberately a plain object and not a Metro binding. The writer is a platform entry point
 * that runs before any graph is built: on Android the activity reading its own intent, on iOS the
 * notification delegate. Making it an injected dependency would mean building a graph inside those
 * entry points just to park a string.
 *
 * Only one request is kept. A second tap before the host has taken the first replaces it, which is
 * what a user means by tapping twice: they want the last thing they tapped.
 */
object PendingNavigation {

    private val _pending = MutableStateFlow<NavigationRequest?>(null)

    /**
     * The parked request, or `null` when there is none.
     *
     * A flow rather than a plain field because the second tap matters. A notification tapped while
     * the app is already open does not rebuild the UI, so a host that only looked once at start up
     * would never see it. Hosts collect this instead.
     */
    val pending: StateFlow<NavigationRequest?> = _pending.asStateFlow()

    /** Parks [request]. Any request not yet taken is replaced. */
    fun request(request: NavigationRequest) {
        _pending.value = request
    }

    /**
     * Takes the parked request and clears it, or answers `null` when there is none.
     *
     * Clearing on read is the point: a request must be acted on once. Without it, every later
     * recomposition would navigate again and the user could not leave the screen.
     */
    fun consume(): NavigationRequest? = _pending.getAndUpdate { null }

    /**
     * Reads a route string of the form `plugin_preferences/<pluginKey>` as a request.
     *
     * Android carries the wanted screen through an intent extra, which is a string, so the string
     * has to become a request again on this side. `AppRoute.PluginPreferences.createRoute` builds
     * the same shape, and this is its inverse. Anything else answers `null`, because a route this
     * code does not know is not something to guess at.
     */
    fun parseRoute(route: String?): NavigationRequest? {
        val value = route ?: return null
        val key = value.substringAfter(PLUGIN_PREFERENCES_PREFIX, missingDelimiterValue = "")
        return if (value.startsWith(PLUGIN_PREFERENCES_PREFIX) && key.isNotEmpty()) {
            NavigationRequest.PluginPreferences(key)
        } else {
            null
        }
    }

    /**
     * Builds the route a notification carries so a tap opens [pluginKey]'s settings.
     *
     * The inverse of [parseRoute], and kept beside it so the two cannot drift. Every poster calls
     * this instead of writing the string: Android puts it in an intent extra, iOS puts it in
     * `userInfo`, and one of them changing the spelling would break the other silently.
     *
     * [pluginKey] is the plugin's class simple name, because that is what the
     * `plugin_preferences/{pluginKey}` destination matches on.
     */
    fun pluginPreferencesRoute(pluginKey: String): String = PLUGIN_PREFERENCES_PREFIX + pluginKey

    /** The intent extra Android carries the route in. */
    const val EXTRA_NAVIGATE_ROUTE: String = "extra_navigate_route"

    private const val PLUGIN_PREFERENCES_PREFIX = "plugin_preferences/"
}
