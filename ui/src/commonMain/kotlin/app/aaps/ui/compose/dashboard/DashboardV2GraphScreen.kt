package app.aaps.ui.compose.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.glass.aimiContextNavigationRequest
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.chips.PluginStatusChip
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import kotlinx.coroutines.flow.Flow

/**
 * Shared Dashboard V2 graph screen backed by the same graph cache and chart preferences as Overview.
 *
 * Pan/zoom stays on this shared Compose screen for both Android and iOS. A double tap returns to the
 * live edge. The renderer receives the original Overview values and targets without unit conversion.
 */
@Composable
fun DashboardV2GraphScreen(
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    statusViewModel: StatusViewModel,
    statusLightsDef: PreferenceSubScreenDef,
    aimiDashboardData: Flow<AimiGlassDashboardData>,
    commandsAllowed: Boolean,
    onIobClick: () -> Unit,
    onNavigate: (NavigationRequest) -> Unit,
    showOverviewSummary: Boolean = true,
    showContextAction: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    val aimiDataState = if (showContextAction) {
        aimiDashboardData.collectAsStateWithLifecycle(initialValue = AimiGlassDashboardData())
    } else {
        remember { mutableStateOf(AimiGlassDashboardData()) }
    }
    val aimiData by aimiDataState
    val iobState by chipsViewModel.iobUiState.collectAsStateWithLifecycle()
    val cobState by chipsViewModel.cobUiState.collectAsStateWithLifecycle()
    val sensitivityState by chipsViewModel.sensitivityUiState.collectAsStateWithLifecycle()
    val pluginBadge by chipsViewModel.pluginBadge.collectAsStateWithLifecycle()
    var selectedRangeHours by remember { mutableIntStateOf(6) }

    Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (showOverviewSummary) {
                DashboardV2OverviewSummary(
                    bgInfo = bgInfoState.bgInfo,
                    timeAgoText = bgInfoState.timeAgoText,
                    iob = iobState,
                    cob = cobState,
                    sensitivity = sensitivityState,
                    onIobClick = onIobClick,
                    contextActionLabel = if (showContextAction && aimiData.contextScreenAvailable) {
                        stringResource(UiStrings.dashboard_glass_loop_open_context)
                    } else {
                        null
                    },
                    onOpenContext = if (showContextAction && aimiData.contextScreenAvailable) {
                        { onNavigate(aimiContextNavigationRequest()) }
                    } else {
                        null
                    },
                )
            }
            DashboardOverviewStatus(
                statusViewModel = statusViewModel,
                statusLightsDef = statusLightsDef,
                onNavigate = onNavigate,
                commandsAllowed = commandsAllowed,
            )
            pluginBadge?.let { badge ->
                PluginStatusChip(
                    badge = badge,
                    onClick = chipsViewModel::onPluginBadgeClick,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            DashboardGraphSection(
                graphViewModel = graphViewModel,
                selectedRangeHours = selectedRangeHours,
                onSelectRange = { selectedRangeHours = it },
                onNavigate = onNavigate,
            )
    }
}
