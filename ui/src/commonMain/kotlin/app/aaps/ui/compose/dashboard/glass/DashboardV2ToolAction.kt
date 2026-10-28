package app.aaps.ui.compose.dashboard.glass

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.icons.IcGenericCgm
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.ui.UiStrings

/** Plugin that owns the AIMI screens below, as `NavigationRequest.PluginPreferences.pluginKey` expects it. */
private const val AIMI_PLUGIN_CLASS_NAME = "OpenAPSAIMIPlugin"
private const val AIMI_PROFILE_ADVISOR_SCREEN_KEY = "aimi_profile_advisor_compose"
private const val AIMI_MEAL_ADVISOR_SCREEN_KEY = "aimi_meal_advisor_compose"
private const val AIMI_CONTEXT_SCREEN_KEY = "aimi_context_compose"

/** The AIMI context screen. The only copy: the Tools tile and the DASHBOARD_V2 home both open it. */
internal fun aimiContextNavigationRequest(): NavigationRequest.PluginPreferences =
    NavigationRequest.PluginPreferences(
        pluginKey = AIMI_PLUGIN_CLASS_NAME,
        composeScreenKey = AIMI_CONTEXT_SCREEN_KEY,
        requiredProtection = ProtectionCheck.Protection.BOLUS,
    )

enum class DashboardV2ToolSection {
    GENERAL,
    AIMI,
}

/** Where a Tools tile goes. Everything that has a [NavigationRequest] uses [Navigate]. */
sealed interface DashboardV2ToolDestination {

    /** The host's Manage (actions) bottom sheet. */
    data object Actions : DashboardV2ToolDestination

    /**
     * The screen of the active BG source plugin. This branch has no `ElementType` for it, so the host
     * looks up `activePlugin.activeBgSource` and opens that plugin.
     */
    data object ActiveBgSource : DashboardV2ToolDestination

    data class Navigate(
        val request: NavigationRequest,
    ) : DashboardV2ToolDestination
}

/**
 * Fixed Tools inventory of the Glass dashboard, plus the AIMI shortcuts.
 *
 * The enum names are stored in [app.aaps.core.keys.StringNonKey.GlassSelectedTools]. Do not rename them.
 */
enum class DashboardV2ToolAction(
    val section: DashboardV2ToolSection,
    val destination: DashboardV2ToolDestination,
    val label: TextRef,
) {
    ACTIONS(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Actions,
        UiStrings.dashboard_v2_tool_actions,
    ),
    RAPID_ACTING(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Navigate(NavigationRequest.Element(ElementType.INSULIN_MANAGEMENT)),
        UiStrings.dashboard_v2_tool_rapid_acting,
    ),
    PROFILE(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Navigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT)),
        UiStrings.dashboard_v2_tool_profile,
    ),
    AUTOMATION(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Navigate(NavigationRequest.Element(ElementType.AUTOMATION_MANAGEMENT)),
        UiStrings.dashboard_v2_tool_automation,
    ),
    NSCLIENT(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Navigate(NavigationRequest.Plugin("NSClientV3Plugin")),
        UiStrings.dashboard_v2_tool_nsclient,
    ),
    TIDEPOOL(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Navigate(NavigationRequest.Plugin("TidepoolPlugin")),
        UiStrings.dashboard_v2_tool_tidepool,
    ),
    XDRIP(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Navigate(NavigationRequest.Plugin("XdripPlugin")),
        UiStrings.dashboard_v2_tool_xdrip,
    ),
    MAINTENANCE(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Navigate(NavigationRequest.Element(ElementType.MAINTENANCE)),
        UiStrings.dashboard_v2_tool_maintenance,
    ),
    XDRIP_BG(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.Navigate(NavigationRequest.Plugin("XdripSourcePlugin")),
        UiStrings.dashboard_v2_tool_xdrip_bg,
    ),
    SENSOR(
        DashboardV2ToolSection.GENERAL,
        DashboardV2ToolDestination.ActiveBgSource,
        UiStrings.dashboard_v2_tool_sensor,
    ),
    ADVISOR(
        DashboardV2ToolSection.AIMI,
        DashboardV2ToolDestination.Navigate(
            NavigationRequest.PluginPreferences(
                pluginKey = AIMI_PLUGIN_CLASS_NAME,
                composeScreenKey = AIMI_PROFILE_ADVISOR_SCREEN_KEY,
                requiredProtection = ProtectionCheck.Protection.PREFERENCES,
            ),
        ),
        UiStrings.dashboard_v2_tool_advisor,
    ),
    MEAL_ADVISOR(
        DashboardV2ToolSection.AIMI,
        DashboardV2ToolDestination.Navigate(
            NavigationRequest.PluginPreferences(
                pluginKey = AIMI_PLUGIN_CLASS_NAME,
                composeScreenKey = AIMI_MEAL_ADVISOR_SCREEN_KEY,
                requiredProtection = ProtectionCheck.Protection.BOLUS,
            ),
        ),
        UiStrings.dashboard_v2_tool_meal_advisor,
    ),
    AIMI_CONTEXT(
        DashboardV2ToolSection.AIMI,
        DashboardV2ToolDestination.Navigate(aimiContextNavigationRequest()),
        UiStrings.dashboard_v2_tool_aimi_context,
    ),
    AUDITOR_REPORT(
        DashboardV2ToolSection.AIMI,
        DashboardV2ToolDestination.Navigate(NavigationRequest.AuditorReport),
        UiStrings.dashboard_v2_tool_auditor_report,
    ),
    ;

    companion object {

        val general: List<DashboardV2ToolAction> = entries.filter { it.section == DashboardV2ToolSection.GENERAL }
        val aimi: List<DashboardV2ToolAction> = entries.filter { it.section == DashboardV2ToolSection.AIMI }
    }
}

/**
 * A tile that opens a plugin, or a screen or dialog of a plugin, is usable only when that plugin is in
 * this build. Every other tile always is.
 *
 * The AIMI tiles all need `OpenAPSAIMIPlugin`. Android and iOS always have it, so there the tiles are
 * always enabled, as on `dev_OAPSAIMI`. Desktop has no AIMI, so there they are shown disabled instead
 * of doing nothing on a tap.
 */
fun DashboardV2ToolAction.isAvailable(availablePluginClassNames: Set<String>): Boolean {
    val pluginClassName = when (val request = (destination as? DashboardV2ToolDestination.Navigate)?.request) {
        is NavigationRequest.Plugin            -> request.className
        is NavigationRequest.PluginPreferences -> request.pluginKey
        NavigationRequest.AuditorReport        -> AIMI_PLUGIN_CLASS_NAME
        else                                   -> null
    }
    return pluginClassName?.let(availablePluginClassNames::contains) ?: true
}

internal fun DashboardV2ToolAction.icon(): ImageVector =
    when (this) {
        DashboardV2ToolAction.ACTIONS      -> Icons.Default.PlayArrow
        DashboardV2ToolAction.RAPID_ACTING -> Icons.Default.Medication
        DashboardV2ToolAction.PROFILE      -> Icons.Default.Person
        DashboardV2ToolAction.AUTOMATION   -> Icons.Default.AutoAwesome
        DashboardV2ToolAction.NSCLIENT     -> Icons.Default.Cloud
        DashboardV2ToolAction.TIDEPOOL     -> Icons.Default.Sync
        DashboardV2ToolAction.XDRIP        -> Icons.Default.WaterDrop
        DashboardV2ToolAction.MAINTENANCE  -> Icons.Default.Build
        DashboardV2ToolAction.XDRIP_BG     -> Icons.Default.Storage
        DashboardV2ToolAction.SENSOR       -> IcGenericCgm
        DashboardV2ToolAction.ADVISOR      -> Icons.Default.Settings
        DashboardV2ToolAction.MEAL_ADVISOR -> Icons.Default.Restaurant
        DashboardV2ToolAction.AIMI_CONTEXT -> Icons.Default.Favorite
        DashboardV2ToolAction.AUDITOR_REPORT -> Icons.Default.Assessment
    }
