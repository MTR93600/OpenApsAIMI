package app.aaps.ui.compose.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalSnackbarHostState
import app.aaps.core.ui.compose.formatKmp
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.dashboard.DashboardGraphCardStrings
import app.aaps.core.ui.compose.dashboard.DashboardGraphCardUiState
import app.aaps.core.ui.compose.dashboard.DashboardGraphComposeCard
import app.aaps.core.ui.compose.dashboard.DashboardGraphControlsUiState
import app.aaps.core.ui.compose.dashboard.DashboardGraphViewport
import app.aaps.core.ui.compose.dashboard.DashboardGraphViewportPresentation
import app.aaps.core.ui.compose.dashboard.GraphFreshnessLevel
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.glass.aimiContextNavigationRequest
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.chips.PluginStatusChip
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.max

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
    val readings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
    val treatmentData by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val graphConfig by graphViewModel.graphConfigFlow.collectAsStateWithLifecycle()
    val chartConfig by graphViewModel.chartConfigFlow.collectAsStateWithLifecycle()
    val derivedRange by graphViewModel.derivedTimeRange.collectAsStateWithLifecycle()
    val now by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()
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
    val dateUtil = LocalDateUtil.current
    val snackbar = LocalSnackbarHostState.current
    val snackbarScope = rememberCoroutineScope()
    var selectedRangeHours by remember { mutableIntStateOf(6) }
    var ownedViewport by remember { mutableStateOf<DashboardGraphViewport?>(null) }
    var ownedViewportRangeHours by remember { mutableIntStateOf(6) }

    val showPredictions = SeriesType.PREDICTIONS in graphConfig.bgOverlays
    val rangeEnd = derivedRange?.second ?: now
    val availableStart = derivedRange?.first ?: (now - 24L * 60L * 60L * 1000L)
    val rangeStart = max(availableStart, rangeEnd - selectedRangeHours * 60L * 60L * 1000L)
    val renderInput = DashboardGraphSnapshotMapper.create(
        readings = readings,
        predictions = predictions,
        treatments = treatmentData,
        showPredictions = showPredictions,
        rangeStartEpochMs = rangeStart,
        rangeEndEpochMs = rangeEnd,
        nowEpochMs = now,
        targetLow = chartConfig.lowMark,
        targetHigh = chartConfig.highMark,
    )
    val candidateViewport = if (readings.isNotEmpty() || renderInput.predictionPoints.isNotEmpty()) {
        DashboardGraphViewport.from(renderInput)
    } else {
        null
    }
    val viewport = when {
        candidateViewport == null -> null
        ownedViewport == null || ownedViewportRangeHours != selectedRangeHours -> candidateViewport
        ownedViewport!!.hasSameDataDomainAs(candidateViewport) -> ownedViewport
        else -> ownedViewport!!.withDataDomain(
            dataStartEpochMs = candidateViewport.dataStartEpochMs,
            dataEndEpochMs = candidateViewport.dataEndEpochMs,
        )
    }
    SideEffect {
        if (ownedViewport != viewport) ownedViewport = viewport
        ownedViewportRangeHours = selectedRangeHours
    }

    val smbToastTemplate = stringResource(UiStrings.dashboard_graph_smb_toast)
    val strings = DashboardGraphCardStrings(
        graphHeader = stringResource(UiStrings.dashboard_graph_header),
        bloodGlucoseA11y = stringResource(UiStrings.dashboard_graph_blood_glucose),
        scenarioFloorLabel = stringResource(UiStrings.dashboard_graph_scenario_floor),
        scenarioBestLabel = stringResource(UiStrings.dashboard_graph_scenario_best),
        predictionShort = stringResource(UiStrings.dashboard_graph_prediction_short),
        predictionIobLabel = stringResource(UiStrings.a11y_prediction_name_iob),
        predictionCobLabel = stringResource(UiStrings.a11y_prediction_name_cob),
        predictionAbsorbedCobLabel = stringResource(UiStrings.a11y_prediction_name_acob),
        predictionUamLabel = stringResource(UiStrings.a11y_prediction_name_uam),
        predictionZeroTempLabel = stringResource(UiStrings.a11y_prediction_name_zt),
        smbLegend = stringResource(UiStrings.dashboard_graph_smb_legend),
        tbrLegend = stringResource(UiStrings.dashboard_graph_tbr_legend),
        scenarioNearBgHint = stringResource(UiStrings.dashboard_graph_scenario_near_bg_hint),
        smbToast = { amount -> smbToastTemplate.formatKmp(amount) },
        statsButton = stringResource(CoreUiStrings.statistics),
        range6h = stringResource(UiStrings.dashboard_graph_scale_6h),
        range12h = stringResource(UiStrings.dashboard_graph_scale_12h),
        range18h = stringResource(UiStrings.dashboard_graph_scale_18h),
        range24h = stringResource(UiStrings.dashboard_graph_scale_24h),
        treatmentsLabel = stringResource(CoreUiStrings.treatments),
    )
    val liveText = stringResource(UiStrings.dashboard_graph_live)
    val noDataText = stringResource(UiStrings.dashboard_graph_no_data)
    val followingText = stringResource(UiStrings.dashboard_graph_following)
    val fixedText = stringResource(UiStrings.dashboard_graph_fixed)
    val freshnessUnknownText = stringResource(UiStrings.dashboard_graph_refresh_unknown)

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
            DashboardGraphComposeCard(
                state = DashboardGraphCardUiState(
                    renderInput = renderInput,
                    // Overview supplies distinct, typed IOB/COB/UAM/ZT curves. The floor/best
                    // scenario legend belongs only to a scenario dataset, which this input is not.
                    showPredictionLegend = false,
                    hasSmbMarkers = renderInput.smbMarkers.isNotEmpty(),
                    hasTbrSegments = renderInput.tbrSegments.isNotEmpty() || renderInput.tbrMarkerEpochMs.isNotEmpty(),
                    hideDetailedGraphStatus = false,
                    controlsState = DashboardGraphControlsUiState(
                        selectedRangeHours = selectedRangeHours,
                        onSelectRange = { hours ->
                            selectedRangeHours = hours
                            ownedViewport = null
                        },
                        onOpenStats = { onNavigate(NavigationRequest.Element(ElementType.STATISTICS)) },
                        onOpenTreatments = { onNavigate(NavigationRequest.Element(ElementType.TREATMENTS)) },
                    ),
                    viewport = viewport,
                    onViewportChanged = { next ->
                        ownedViewport = next
                        ownedViewportRangeHours = selectedRangeHours
                    },
                    viewportPresentation = { current ->
                        val dataText = if (readings.isNotEmpty()) liveText else noDataText
                        val followText = if (current.followingLive) followingText else fixedText
                        val labels = (0..4).map { tick ->
                            dateUtil.timeString(
                                current.fromTimeEpochMs +
                                    ((current.toTimeEpochMs - current.fromTimeEpochMs) * tick / 4L),
                            )
                        }
                        DashboardGraphViewportPresentation(
                            statusSummary = "${selectedRangeHours}h • $dataText • $followText",
                            freshnessText = freshnessUnknownText,
                            freshnessLevel = GraphFreshnessLevel.UNKNOWN,
                            tickLabels = labels,
                        )
                    },
                    onDoubleTap = { viewport?.let { ownedViewport = it.followLive() } },
                    onSmbMarkerTap = { marker ->
                        val label = treatmentData.boluses
                            .firstOrNull { it.isValid && it.timestamp == marker.timestampEpochMs }
                            ?.label
                        if (label != null) {
                            snackbarScope.launch { snackbar.showSnackbar(strings.smbToast(label)) }
                        }
                    },
                ),
                strings = strings,
            )
    }
}
