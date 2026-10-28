package app.aaps.core.ui.compose.navigation

import androidx.compose.runtime.compositionLocalOf
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.keys.interfaces.ClientRole

sealed class NavigationRequest {
    data class Element(val type: ElementType) : NavigationRequest()
    data class QuickWizard(val guid: String) : NavigationRequest()
    data class Plugin(val className: String) : NavigationRequest()
    data class PluginPreferences(
        val pluginKey: String,
        val composeScreenKey: String? = null,
        val requiredProtection: ProtectionCheck.Protection = ProtectionCheck.Protection.PREFERENCES,
    ) : NavigationRequest()

    /** A screen of the Glass dashboard skin. The navigator asks for [GlassDestination.protection] first. */
    data class Glass(val destination: GlassDestination) : NavigationRequest()

    /**
     * The AIMI auditor report. It is a dialog, not a screen, so the navigator calls
     * [app.aaps.core.interfaces.aps.AimiAuditorReport.openReport] instead of opening a route. No
     * protection, as on `dev_OAPSAIMI`: the report only shows text.
     */
    data object AuditorReport : NavigationRequest()
}

/**
 * The Glass dashboard screens a [NavigationRequest.Glass] can open.
 *
 * The information screens only show values and need no protection. The screens that start a
 * treatment or change the loop ask for [ProtectionCheck.Protection.BOLUS], as on `dev_OAPSAIMI`
 * (also the sensor insert, where the normal care dialog asks for nothing).
 *
 * @param element the normal screen this one replaces. Its client role and its "master or paired
 *   client" rule also apply here; null for an information screen.
 */
enum class GlassDestination(
    val protection: ProtectionCheck.Protection = ProtectionCheck.Protection.NONE,
    val element: ElementType? = null,
) {
    LOOP_DASHBOARD,
    SENSOR_QUALITY,
    PUMP_DETAIL,
    BATTERY_DETAIL,
    INSULIN_DETAIL(ProtectionCheck.Protection.BOLUS, ElementType.INSULIN),
    CANNULA_DETAIL(ProtectionCheck.Protection.BOLUS, ElementType.CANNULA_CHANGE),
    BASAL_DETAIL(ProtectionCheck.Protection.BOLUS, ElementType.TEMP_BASAL),
    SENSOR_INSERT_DETAIL(ProtectionCheck.Protection.BOLUS, ElementType.SENSOR_INSERT),
    TARGET_DETAIL(ProtectionCheck.Protection.BOLUS, ElementType.TEMP_TARGET_MANAGEMENT),
    LOOP_DETAIL(ProtectionCheck.Protection.BOLUS, ElementType.RUNNING_MODE),
    ;

    /**
     * True when this phone may open the screen. An information screen opens on every phone. A
     * treatment or loop screen needs a master or a paired client ([masterOrPairedClient]) and a
     * [clientRole] that allows the normal screen, the same rules the drawer and the treatment sheet use.
     *
     * The navigator checks this for every request, so a new way to open these screens cannot skip it.
     */
    fun isAllowed(masterOrPairedClient: Boolean, clientRole: ClientRole): Boolean {
        val normalScreen = element ?: return true
        return masterOrPairedClient && clientRole.allows(normalScreen.role)
    }
}

/**
 * CompositionLocal for dispatching a [NavigationRequest] from within a plugin's compose content
 * (or any nested screen) up to whichever host owns the NavController. Provided by
 * `PluginContentRoute` in the app module — defaults to a no-op so screens used outside that
 * route (previews, tests) don't crash.
 */
val LocalPluginNavigationRequest = compositionLocalOf<(NavigationRequest) -> Unit> { { } }
