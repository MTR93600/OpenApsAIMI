package app.aaps.ui.compose.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalSnackbarHostState
import app.aaps.core.ui.compose.dashboard.DashboardGraphCardStrings
import app.aaps.core.ui.compose.dashboard.DashboardGraphCardUiState
import app.aaps.core.ui.compose.dashboard.DashboardGraphComposeCard
import app.aaps.core.ui.compose.dashboard.DashboardGraphControlsUiState
import app.aaps.core.ui.compose.dashboard.DashboardGraphViewport
import app.aaps.core.ui.compose.dashboard.DashboardGraphViewportPresentation
import app.aaps.core.ui.compose.dashboard.GraphFreshnessLevel
import app.aaps.core.ui.compose.formatKmp
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * The dashboard glucose graph card, fed from the same graph cache and chart preferences as Overview.
 * Used by the Dashboard V2 screen and by the AIMI dashboard home, so both draw the same graph.
 *
 * Pan and zoom are shared Compose code for Android and iOS. A new range resets the viewport to the
 * live edge. The renderer receives the original Overview values and targets without unit conversion.
 *
 * @param selectedRangeHours hours shown; the owner keeps it (the AIMI home stores it in a preference)
 * @param onDoubleTap what a double tap does; null returns the graph to the live edge
 */
@Composable
internal fun DashboardGraphSection(
    graphViewModel: GraphViewModel,
    selectedRangeHours: Int,
    onSelectRange: (Int) -> Unit,
    onNavigate: (NavigationRequest) -> Unit,
    modifier: Modifier = Modifier,
    hideDetailedGraphStatus: Boolean = false,
    expandVertically: Boolean = false,
    onDoubleTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val readings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
    val treatmentData by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val graphConfig by graphViewModel.graphConfigFlow.collectAsStateWithLifecycle()
    val chartConfig by graphViewModel.chartConfigFlow.collectAsStateWithLifecycle()
    val derivedRange by graphViewModel.derivedTimeRange.collectAsStateWithLifecycle()
    val now by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()
    val dateUtil = LocalDateUtil.current
    val snackbar = LocalSnackbarHostState.current
    val snackbarScope = rememberCoroutineScope()
    var ownedViewport by remember { mutableStateOf<DashboardGraphViewport?>(null) }
    var ownedViewportRangeHours by remember { mutableIntStateOf(selectedRangeHours) }

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
    val currentOwned = ownedViewport
    val viewport = when {
        candidateViewport == null                                                      -> null
        currentOwned == null || ownedViewportRangeHours != selectedRangeHours          -> candidateViewport
        currentOwned.hasSameDataDomainAs(candidateViewport)                            -> currentOwned
        else                                                                           -> currentOwned.withDataDomain(
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

    DashboardGraphComposeCard(
        state = DashboardGraphCardUiState(
            renderInput = renderInput,
            // Overview supplies distinct, typed IOB/COB/UAM/ZT curves. The floor/best
            // scenario legend belongs only to a scenario dataset, which this input is not.
            showPredictionLegend = false,
            hasSmbMarkers = renderInput.smbMarkers.isNotEmpty(),
            hasTbrSegments = renderInput.tbrSegments.isNotEmpty() || renderInput.tbrMarkerEpochMs.isNotEmpty(),
            hideDetailedGraphStatus = hideDetailedGraphStatus,
            expandVertically = expandVertically,
            controlsState = DashboardGraphControlsUiState(
                selectedRangeHours = selectedRangeHours,
                onSelectRange = { hours ->
                    onSelectRange(hours)
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
            onDoubleTap = onDoubleTap ?: {
                viewport?.let { ownedViewport = it.followLive() }
                Unit
            },
            onLongPress = onLongPress,
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
        modifier = modifier,
    )
}
