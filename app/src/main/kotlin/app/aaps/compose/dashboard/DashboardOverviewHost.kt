package app.aaps.compose.dashboard

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import app.aaps.plugins.main.general.dashboard.AimiDashboardComposeRootView
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction
import app.aaps.plugins.main.skins.DashboardHomeVariant

/**
 * AIMI dashboard home in Compose: embeds [AimiDashboardComposeRootView] (Compose column + shared
 * [DashboardShellController]; graph / notifications / adjustment remain in [AndroidView] slots).
 */
@Composable
fun DashboardOverviewHost(
    paddingValues: PaddingValues,
    fabBottomOffset: Dp,
    dashboardHomeVariant: DashboardHomeVariant,
    availablePluginClassNames: Set<String>,
    onToolAction: (DashboardV2ToolAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(bottom = fabBottomOffset),
        factory = { ctx ->
            require(ctx is FragmentActivity) { "DashboardOverviewHost requires FragmentActivity context" }
            AimiDashboardComposeRootView(
                context = ctx,
                dashboardHomeVariant = dashboardHomeVariant,
                availablePluginClassNames = availablePluginClassNames,
                onToolAction = onToolAction,
            )
        },
        update = { },
    )
}
