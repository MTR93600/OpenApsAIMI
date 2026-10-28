@file:Suppress("ktlint:standard:function-naming")

package app.aaps.ui.compose.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.icons.IcAutomation
import app.aaps.core.ui.compose.icons.IcProfile
import app.aaps.core.ui.compose.icons.Pump
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/** Fixed six-item navigation, used only by the Glass home skin. */
enum class GlassNavigationTab {
    TREATMENTS, PROFILES, BOLUS, PUMP, SCENARIOS, MANAGEMENT;

    companion object {

        val fixedOrder: List<GlassNavigationTab> = listOf(TREATMENTS, PROFILES, BOLUS, PUMP, SCENARIOS, MANAGEMENT)
    }
}

/**
 * Tabs that need a master or a paired client. Same rule as the Treatments and Scenes buttons of
 * [MainNavigationBar]: a client that is not paired cannot deliver a dose, so the bolus calculator
 * must not be reachable either.
 */
internal val MASTER_ONLY_TABS: Set<GlassNavigationTab> =
    setOf(GlassNavigationTab.TREATMENTS, GlassNavigationTab.SCENARIOS, GlassNavigationTab.BOLUS)

// Smaller than the 24 dp default: six or seven items is more than the five Material suggests,
// so a smaller icon (with labelSmall text) keeps the bar from looking crowded.
private val NAV_ICON_SIZE = 20.dp

@Composable
fun GlassNavigationBar(
    masterOrPairedClient: Boolean,
    onTreatmentClick: () -> Unit,
    onScenariosClick: () -> Unit,
    onManagementClick: () -> Unit,
    onNavigate: (NavigationRequest) -> Unit,
    modifier: Modifier = Modifier,
    /** True only while an open-loop suggestion waits for the user to accept it. Same signal as the
     *  loop button of [MainNavigationBar]. Glass has no other way to this action, so it must stay here. */
    loopActionAvailable: Boolean = false,
    onLoopActionClick: () -> Unit = {},
) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledIconColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        windowInsets = WindowInsets(0),
        modifier = modifier,
    ) {
        GlassNavigationTab.fixedOrder.forEach { tab ->
            val enabled = masterOrPairedClient || tab !in MASTER_ONLY_TABS
            val label = stringResource(tab.label())
            NavigationBarItem(
                selected = false,
                enabled = enabled,
                onClick = {
                    when (tab) {
                        GlassNavigationTab.TREATMENTS -> onTreatmentClick()
                        GlassNavigationTab.SCENARIOS  -> onScenariosClick()
                        GlassNavigationTab.MANAGEMENT -> onManagementClick()
                        GlassNavigationTab.PROFILES   -> onNavigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT))
                        GlassNavigationTab.BOLUS      -> onNavigate(NavigationRequest.Element(ElementType.BOLUS_WIZARD))
                        // The screen of the active pump plugin (same as the Pump entry of the Manage sheet).
                        GlassNavigationTab.PUMP       -> onNavigate(NavigationRequest.Element(ElementType.PUMP))
                    }
                },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = label,
                        modifier = Modifier.size(NAV_ICON_SIZE),
                    )
                },
                label = { Text(text = label, style = MaterialTheme.typography.labelSmall) },
                colors = colors,
            )
        }
        // Loop accept action: shown only when the open loop has a suggestion waiting.
        if (loopActionAvailable) {
            NavigationBarItem(
                selected = false,
                // The badge is a constant "1": one suggestion is waiting. Spoken so it matches.
                modifier = Modifier.badgeState(stringResource(UiStrings.a11y_badge_available, 1)),
                onClick = onLoopActionClick,
                icon = {
                    BadgedBox(
                        badge = {
                            Badge(containerColor = AapsTheme.generalColors.statusNormal, contentColor = Color.Black) {
                                Text(text = "1")
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = stringResource(UiStrings.loop_accept_nav_label),
                            modifier = Modifier.size(NAV_ICON_SIZE)
                        )
                    }
                },
                label = { Text(text = stringResource(UiStrings.loop_accept_nav_label), style = MaterialTheme.typography.labelSmall) },
                colors = colors,
            )
        }
    }
}

private fun GlassNavigationTab.icon(): ImageVector =
    when (this) {
        GlassNavigationTab.TREATMENTS -> Icons.Default.Fastfood
        GlassNavigationTab.PROFILES   -> IcProfile
        GlassNavigationTab.BOLUS      -> Icons.Default.Medication
        GlassNavigationTab.PUMP       -> Pump
        GlassNavigationTab.SCENARIOS  -> IcAutomation
        GlassNavigationTab.MANAGEMENT -> Icons.Default.ManageAccounts
    }

private fun GlassNavigationTab.label(): TextRef =
    when (this) {
        GlassNavigationTab.TREATMENTS -> CoreUiStrings.treatments
        GlassNavigationTab.PROFILES   -> UiStrings.dashboard_glass_nav_profiles
        GlassNavigationTab.BOLUS      -> UiStrings.dashboard_v2_nav_bolus
        GlassNavigationTab.PUMP       -> CoreUiStrings.pump
        GlassNavigationTab.SCENARIOS  -> CoreUiStrings.scenes
        GlassNavigationTab.MANAGEMENT -> CoreUiStrings.manage
    }
