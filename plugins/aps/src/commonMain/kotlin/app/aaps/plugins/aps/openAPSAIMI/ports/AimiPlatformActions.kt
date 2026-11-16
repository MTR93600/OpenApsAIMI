package app.aaps.plugins.aps.openAPSAIMI.ports

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.navigation.PendingNavigation

/**
 * The AIMI settings actions that only the platform host can do, such as opening a screen of the
 * app outside the settings.
 *
 * It works like [AimiPlatformPreferenceItems]: the AIMI pages ask for each action by its
 * [AimiPlatformActionSlot]. A platform that cannot honour an action returns `null`. The page then
 * shows no link at all. It never shows a link that does nothing.
 */
interface AimiPlatformActions {

    /** Returns the action for [slot], or `null` when this platform cannot do it. */
    fun action(slot: AimiPlatformActionSlot): (() -> Unit)?
}

/** The actions that a platform provides. See [AimiPlatformActions]. */
enum class AimiPlatformActionSlot {
    /** Opens the profile screen, where the profile targets are set. Used by the Essentials page. */
    PROFILE_SCREEN,
}

/**
 * Asks the app navigator to open the profile screen (the profile management screen of the drawer).
 *
 * The request goes through [PendingNavigation], which the app root collects and hands to its
 * navigator. The navigator asks for the same protection as the drawer entry before it opens the
 * screen, so this link is no way around a protection. Only a host that collects [PendingNavigation]
 * can use this: Android and iOS do, the desktop host does not.
 */
internal fun requestProfileScreen() {
    PendingNavigation.request(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT))
}
