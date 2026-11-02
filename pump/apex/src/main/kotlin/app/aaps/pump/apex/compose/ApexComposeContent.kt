package app.aaps.pump.apex.compose

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.pump.PumpOverviewScreen
import app.aaps.core.ui.R as CoreUiR

/** The Apex plugin screen. It replaces the `ApexFragment` of dev_OAPSAIMI. */
class ApexComposeContent(
    private val pluginName: String
) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        val viewModel: ApexOverviewViewModel = metroViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        val navigationIcon: @Composable () -> Unit = {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiR.string.back))
            }
        }
        val settingsAction: @Composable RowScope.() -> Unit = {
            onSettings?.let { action ->
                IconButton(onClick = action) {
                    Icon(Icons.Filled.Settings, contentDescription = stringResource(CoreUiR.string.settings))
                }
            }
        }

        LaunchedEffect(Unit) {
            setToolbarConfig(ToolbarConfig(title = pluginName, navigationIcon = navigationIcon, actions = settingsAction))
        }

        PumpOverviewScreen(state = uiState)
    }
}
