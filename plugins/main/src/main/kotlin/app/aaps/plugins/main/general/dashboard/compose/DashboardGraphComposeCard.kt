package app.aaps.plugins.main.general.dashboard.compose

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import app.aaps.core.interfaces.overview.graph.ChartSmbMarker
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState
import app.aaps.ui.compose.overview.graphs.DEFAULT_GRAPH_ZOOM_MINUTES
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.graphs.SecondaryGraphCompose
import app.aaps.ui.compose.overview.graphs.TreatmentBeltGraphCompose
import app.aaps.ui.compose.overview.graphs.bgReadingTintColor
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun DashboardGraphComposeCard(
    composeState: DashboardEmbeddedComposeState,
    graphViewModel: GraphViewModel,
    attachLegacyGraphBackend: Boolean,
    /** When true, hides graph update line + range/live/follow + freshness (compact Simple mode). */
    hideDetailedGraphStatus: Boolean = false,
    expandGraphVertically: Boolean = false,
    graphContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val useVicoGraph = !attachLegacyGraphBackend
    val vicoChartLook by graphViewModel.vicoChartLookFlow.collectAsStateWithLifecycle()
    val graphConfig by graphViewModel.graphConfigFlow.collectAsStateWithLifecycle()
    val generalUnits by graphViewModel.generalUnits.collectAsStateWithLifecycle()
    val showPredictionLegend = dashboardGraphPredictionLegendVisible(
        useVicoGraph = useVicoGraph,
        canvasRenderInput = composeState.graphRenderInput,
        graphViewModel = graphViewModel,
    )
    val predictionsForHint by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
    val bgReadingsForHint by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val showScenarioNearBgHint = remember(predictionsForHint, bgReadingsForHint, useVicoGraph, showPredictionLegend) {
        useVicoGraph &&
            showPredictionLegend &&
            scenarioNearBgEquilibriumHintVisible(
                predictions = predictionsForHint,
                currentBgMgdl = bgReadingsForHint.lastOrNull()?.value,
            )
    }
    val cardInnerPaddingHorizontal = dimensionResource(R.dimen.dashboard_card_inner_padding_horizontal)
    val cardInnerPaddingVertical = dimensionResource(R.dimen.dashboard_card_inner_padding_vertical)
    val graphMinHeight = dimensionResource(R.dimen.dashboard_graph_height_min)
    val sectionSpacing = dimensionResource(R.dimen.dashboard_section_spacing)
    val graphCorner = dimensionResource(R.dimen.dashboard_card_corner_large)
    val context = LocalContext.current
    val density = LocalDensity.current
    val smbHitPx = remember(density) { with(density) { 36.dp.toPx() } }
    val scheme = MaterialTheme.colorScheme
    val generalColors = AapsTheme.generalColors
    val originalBgValue = generalColors.originalBgValue
    val iobPredictionColor = generalColors.iobPrediction
    val scenarioBestLegendColor = lerp(generalColors.uamPrediction, scheme.tertiary, 0.45f)
    /** Matches [BgGraphCompose] on dashboard: original BG palette + optional reading tint, soft alpha. */
    val bgLegendLineColor = remember(useVicoGraph, vicoChartLook.bgReadingTintKey, scheme, originalBgValue) {
        if (useVicoGraph) {
            bgReadingTintColor(vicoChartLook.bgReadingTintKey, originalBgValue, scheme).copy(alpha = 0.88f)
        } else {
            scheme.primary
        }
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (expandGraphVertically) Modifier.fillMaxHeight() else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.52f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (expandGraphVertically) Modifier.fillMaxHeight() else Modifier)
                .padding(horizontal = cardInnerPaddingHorizontal, vertical = cardInnerPaddingVertical),
        ) {
            Text(
                text = stringResource(R.string.graph_menu_divider_header),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = sectionSpacing),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = sectionSpacing),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .width(14.dp)
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(bgLegendLineColor),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.a11y_blood_glucose),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (showPredictionLegend) {
                        if (useVicoGraph) {
                            DashboardScenarioProjectionLegend(
                                floorColor = iobPredictionColor,
                                bestColor = scenarioBestLegendColor,
                                floorLabel = stringResource(R.string.graph_legend_scenario_floor),
                                bestLabel = stringResource(R.string.graph_legend_scenario_best),
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .width(14.dp)
                                        .height(3.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.prediction_shortname),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (!attachLegacyGraphBackend && composeState.graphRenderInput.smbMarkers.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "▲",
                                fontSize = 10.sp,
                                color = if (useVicoGraph) {
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.62f)
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.graph_legend_smb),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (!attachLegacyGraphBackend &&
                        (
                            composeState.graphRenderInput.tbrSegments.isNotEmpty() ||
                                composeState.graphRenderInput.tbrMarkerEpochMs.isNotEmpty()
                            )
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(12.dp)
                                    .background(
                                        if (useVicoGraph) {
                                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.92f)
                                        } else {
                                            MaterialTheme.colorScheme.tertiary
                                        },
                                    ),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.graph_legend_tbr),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    DashboardGraphComposeControls(
                        composeState = composeState,
                        modifier = Modifier.height(34.dp),
                    )
                }
            }
            if (showScenarioNearBgHint) {
                Text(
                    text = stringResource(R.string.graph_scenario_near_bg_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = sectionSpacing),
                )
            }
            val updateMessage = composeState.graphUiState.updateMessage
            if (!hideDetailedGraphStatus && updateMessage.isNotBlank()) {
                Text(
                    text = updateMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = sectionSpacing),
                )
            }
            val renderInput = composeState.graphRenderInput
            val statusUi = dashboardGraphStatusRowModel(
                useVicoGraph = useVicoGraph,
                composeState = composeState,
                graphViewModel = graphViewModel,
            )
            if (!hideDetailedGraphStatus) {
                val renderSummary = buildString {
                    append(statusUi.summaryRangeHours)
                    append("h")
                    append(" \u2022 ")
                    append(stringResource(statusUi.dataStateRes))
                    append(" \u2022 ")
                    append(stringResource(statusUi.followStateRes))
                }
                val freshnessText = statusUi.freshnessMinutes?.let { mins ->
                    stringResource(statusUi.freshnessMessageRes, mins)
                } ?: stringResource(statusUi.freshnessMessageRes)
                val freshnessColor: Color = when (statusUi.freshnessLevel) {
                    GraphFreshnessLevel.FRESH -> MaterialTheme.colorScheme.primary
                    GraphFreshnessLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                    GraphFreshnessLevel.STALE -> MaterialTheme.colorScheme.error
                    GraphFreshnessLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = renderSummary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                )
                Text(
                    text = freshnessText,
                    style = MaterialTheme.typography.labelSmall,
                    color = freshnessColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = sectionSpacing),
                )
            } else {
                Spacer(modifier = Modifier.height(sectionSpacing))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (expandGraphVertically) {
                            Modifier
                                .weight(1f)
                                .heightIn(min = graphMinHeight)
                        } else {
                            Modifier.heightIn(min = graphMinHeight)
                        },
                    )
                    .pointerInput(
                        composeState.graphCommands.onDoubleTap,
                        composeState.graphCommands.onLongPress,
                    ) {
                        detectTapGestures(
                            onDoubleTap = { composeState.graphCommands.onDoubleTap?.invoke() },
                            onLongPress = { composeState.graphCommands.onLongPress?.invoke() },
                        )
                    },
                verticalAlignment = Alignment.Bottom,
            ) {
                if (!useVicoGraph) {
                    val yAxisLabels = remember(renderInput, generalUnits) {
                        graphYAxisLabels(renderInput) { y ->
                            graphViewModel.formatBgAxisLabelFromMgdl(y)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .width(44.dp)
                            .then(if (expandGraphVertically) Modifier.fillMaxHeight() else Modifier.height(graphMinHeight))
                            .padding(end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        yAxisLabels.forEach { label ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(if (expandGraphVertically) Modifier.fillMaxHeight() else Modifier)
                        .clip(RoundedCornerShape(graphCorner))
                        .then(
                            // Canvas dashboard only: shell shifts the window. Vico stack uses shared scroll/zoom
                            // like Overview — a parent pan here never wins over chart hit targets, so it broke history.
                            if (!attachLegacyGraphBackend &&
                                !useVicoGraph &&
                                composeState.graphCommands.onGraphPanFromDragFraction != null
                            ) {
                                Modifier.pointerInput(
                                    composeState.graphCommands.onGraphPanFromDragFraction,
                                    renderInput.rangeHours,
                                ) {
                                    detectHorizontalDragGestures { change, dragAmount ->
                                        change.consume()
                                        val w = size.width.coerceAtLeast(1).toFloat()
                                        composeState.graphCommands.onGraphPanFromDragFraction?.invoke(
                                            dragAmount / w,
                                        )
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    val vmDerived by graphViewModel.derivedTimeRange.collectAsStateWithLifecycle()
                    val effectiveShellRange = remember(
                        vmDerived,
                        renderInput.fromTimeEpochMs,
                        renderInput.toTimeEpochMs,
                    ) {
                        val f = renderInput.fromTimeEpochMs
                        val t = renderInput.toTimeEpochMs
                        if (f > 0L && t > f) f to t else vmDerived
                    }
                    val chartTimeRange = vmDerived ?: effectiveShellRange
                    val smbTapModifier =
                        if (!attachLegacyGraphBackend && !useVicoGraph && renderInput.smbMarkers.isNotEmpty()) {
                            Modifier.pointerInput(
                                renderInput.smbMarkers,
                                renderInput.fromTimeEpochMs,
                                renderInput.toTimeEpochMs,
                                renderInput.points,
                                renderInput.predictionPoints,
                                smbHitPx,
                            ) {
                                detectTapGestures { offset ->
                                    val marker = findNearestSmbByX(
                                        tapX = offset.x,
                                        widthPx = size.width.toFloat(),
                                        hitPx = smbHitPx,
                                        input = renderInput,
                                        chartPlotStartPx = 0f,
                                        chartTimeOverride = null,
                                    )
                                    if (marker != null) {
                                        ToastUtils.infoToast(
                                            context,
                                            context.getString(R.string.dashboard_graph_smb_toast, marker.amountLabel),
                                        )
                                    }
                                }
                            }
                        } else {
                            Modifier
                        }
                    if (useVicoGraph) {
                        // Same X span as Overview (cache flows); horizontal scroll enabled (see shellControls…).
                        val shellControlsHorizontalWindow = false
                        val scrollState = rememberVicoScrollState(
                            scrollEnabled = !shellControlsHorizontalWindow,
                            initialScroll = Scroll.Absolute.End,
                        )
                        val zoomState = rememberVicoZoomState(
                            zoomEnabled = true,
                            initialZoom = Zoom.x(DEFAULT_GRAPH_ZOOM_MINUTES),
                        )
                        // Dashboard: never show activity (strip / BG / IOB overlay) — keep the card readable.
                        val bgOverlaysForEffects = remember(graphConfig.bgOverlays) {
                            buildList {
                                addAll(graphConfig.bgOverlays.filter { it != SeriesType.ACTIVITY })
                                if (SeriesType.PREDICTIONS !in this) {
                                    add(SeriesType.PREDICTIONS)
                                }
                            }
                        }
                        DashboardVicoSharedViewportEffects(
                            graphViewModel = graphViewModel,
                            scrollState = scrollState,
                            zoomState = zoomState,
                            derivedTimeRange = chartTimeRange,
                            viewportResetGeneration = composeState.vicoViewportResetGeneration,
                            viewportFollowingLive = composeState.vicoViewportFollowingLive,
                            onViewportFollowingLiveChanged = composeState::setVicoViewportFollowingLive,
                            shellControlsHorizontalWindow = shellControlsHorizontalWindow,
                            bgOverlays = bgOverlaysForEffects,
                        )
                        val nowTs by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()
                        // In simple mode this card is sized with weight(1f). On short viewports, fixed
                        // BG/IOB heights can clip the secondary graph. Fit both charts to available height.
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (expandGraphVertically) Modifier.fillMaxHeight() else Modifier),
                        ) {
                            val beltHeight = 48.dp
                            val interChartSpacing = 4.dp
                            val bgDesiredHeight = graphConfig.bgHeight.dp
                            val iobDesiredHeight = graphConfig.iobHeight.dp
                            val (bgHeight, iobHeight) =
                                if (expandGraphVertically) {
                                    val chartBudget =
                                        (maxHeight - beltHeight - interChartSpacing).coerceAtLeast(0.dp)
                                    val desiredChartsTotal = (bgDesiredHeight + iobDesiredHeight).coerceAtLeast(1.dp)
                                    if (chartBudget > 0.dp && chartBudget < desiredChartsTotal) {
                                        val bgRatio = bgDesiredHeight.value / desiredChartsTotal.value
                                        val bgScaled = (chartBudget * bgRatio.toFloat()).coerceAtLeast(56.dp)
                                        val iobScaled = (chartBudget - bgScaled).coerceAtLeast(56.dp)
                                        if (bgScaled + iobScaled > chartBudget) {
                                            // If min guards exceed the budget, preserve BG and shrink IOB gracefully.
                                            val adjustedIob = (chartBudget - bgScaled).coerceAtLeast(0.dp)
                                            bgScaled to adjustedIob
                                        } else {
                                            bgScaled to iobScaled
                                        }
                                    } else {
                                        bgDesiredHeight to iobDesiredHeight
                                    }
                                } else {
                                    bgDesiredHeight to iobDesiredHeight
                                }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                // Same stack order / overlap as [app.aaps.ui.compose.overview.graphs.GraphsSection]
                                TreatmentBeltGraphCompose(
                                    viewModel = graphViewModel,
                                    scrollState = scrollState,
                                    zoomState = zoomState,
                                    derivedTimeRange = chartTimeRange,
                                    nowTimestamp = nowTs,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset(y = (-16).dp)
                                        .height(bgHeight),
                                ) {
                                    DashboardBgGraphVico(
                                        graphViewModel = graphViewModel,
                                        graphRenderInput = renderInput,
                                        scrollState = scrollState,
                                        zoomState = zoomState,
                                        derivedTimeRange = chartTimeRange,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                Spacer(modifier = Modifier.height(interChartSpacing))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset(y = (-8).dp),
                                ) {
                                    SecondaryGraphCompose(
                                        viewModel = graphViewModel,
                                        seriesTypes = listOf(SeriesType.IOB),
                                        scrollState = scrollState,
                                        zoomState = zoomState,
                                        derivedTimeRange = chartTimeRange,
                                        nowTimestamp = nowTs,
                                        activityOverlay = false,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(iobHeight),
                                    )
                                }
                            }
                        }
                    } else {
                        DashboardGraphComposeRenderer(
                            renderInput = renderInput,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (expandGraphVertically) Modifier.weight(1f) else Modifier.height(graphMinHeight))
                                .then(smbTapModifier),
                        )
                    }
                    if (!useVicoGraph) {
                        val tickLabels = remember(renderInput.fromTimeEpochMs, renderInput.toTimeEpochMs) {
                            graphTickLabels(
                                fromTimeEpochMs = renderInput.fromTimeEpochMs,
                                toTimeEpochMs = renderInput.toTimeEpochMs,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp)
                                .offset(y = (-2).dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            tickLabels.forEach { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                                )
                            }
                        }
                    }
                    if (attachLegacyGraphBackend) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .alpha(0f),
                        ) {
                            graphContent()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun dashboardGraphStatusRowModel(
    useVicoGraph: Boolean,
    composeState: DashboardEmbeddedComposeState,
    graphViewModel: GraphViewModel,
): GraphStatusUi {
    val renderInput = composeState.graphRenderInput
    val freshnessConfig = composeState.graphUiState.freshnessConfig
    val nowEpochMs = System.currentTimeMillis()
    if (!useVicoGraph) {
        return remember(
            renderInput,
            freshnessConfig.warningThresholdMinutes,
            freshnessConfig.staleThresholdMinutes,
        ) {
            GraphStatusPresenter.present(
                renderInput = renderInput,
                freshnessConfig = freshnessConfig,
                nowEpochMs = nowEpochMs,
            )
        }
    }
    val bgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    return GraphStatusPresenter.presentForVicoDashboard(
        rangeHours = composeState.graphUiState.rangeHours,
        hasBgReadings = bgReadings.isNotEmpty(),
        viewportFollowingLive = composeState.vicoViewportFollowingLive &&
            composeState.graphPanPastMs == 0L,
        lastRefreshEpochMs = renderInput.lastRefreshEpochMs,
        freshnessConfig = freshnessConfig,
        nowEpochMs = nowEpochMs,
    )
}

@Composable
private fun dashboardGraphPredictionLegendVisible(
    useVicoGraph: Boolean,
    canvasRenderInput: DashboardEmbeddedComposeState.GraphRenderInput,
    graphViewModel: GraphViewModel,
): Boolean {
    if (!useVicoGraph) {
        return canvasRenderInput.predictionPoints.isNotEmpty()
    }
    val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
    return predictions.isNotEmpty()
}

private fun findNearestSmbByX(
    tapX: Float,
    widthPx: Float,
    hitPx: Float,
    input: DashboardEmbeddedComposeState.GraphRenderInput,
    chartPlotStartPx: Float = 0f,
    /** When set (Vico + cache range), matches SMB hit-test to the chart X window, not the shell render window. */
    chartTimeOverride: Pair<Long, Long>? = null,
): ChartSmbMarker? {
    if (input.smbMarkers.isEmpty() || widthPx <= 1f) return null
    val reservedFabInset = min(72f, widthPx * 0.16f)
    val plotRight = widthPx - reservedFabInset
    val plotLeft = chartPlotStartPx
    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
    val basePoints = if (input.points.isNotEmpty()) input.points else input.predictionPoints
    val minX = chartTimeOverride?.first?.takeIf { it > 0L }
        ?: input.fromTimeEpochMs.takeIf { it > 0L }
        ?: basePoints.minOfOrNull { it.timestampEpochMs }
        ?: input.smbMarkers.minOfOrNull { it.timestampEpochMs }
        ?: return null
    val maxX = chartTimeOverride?.second?.takeIf { it > minX }
        ?: input.toTimeEpochMs.takeIf { it > minX }
        ?: basePoints.maxOfOrNull { it.timestampEpochMs }
        ?: input.smbMarkers.maxOfOrNull { it.timestampEpochMs }
        ?: return null
    if (maxX <= minX) return null
    val xRange = max(1L, maxX - minX).toFloat()
    fun toCanvasX(epochMs: Long): Float =
        plotLeft + (((epochMs - minX) / xRange) * plotWidth)

    var best: ChartSmbMarker? = null
    var bestDist = Float.POSITIVE_INFINITY
    for (m in input.smbMarkers) {
        val cx = toCanvasX(m.timestampEpochMs)
        val d = abs(tapX - cx)
        if (d <= hitPx && d < bestDist) {
            bestDist = d
            best = m
        }
    }
    return best
}

private fun graphTickLabels(fromTimeEpochMs: Long, toTimeEpochMs: Long): List<String> {
    if (fromTimeEpochMs <= 0L || toTimeEpochMs <= fromTimeEpochMs) return emptyList()
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    val ticks = 4
    return (0..ticks).map { idx ->
        val ratio = idx.toDouble() / ticks.toDouble()
        val timestamp = fromTimeEpochMs + ((toTimeEpochMs - fromTimeEpochMs) * ratio).toLong()
        formatter.format(Date(timestamp))
    }
}

private fun graphYAxisLabels(
    renderInput: DashboardEmbeddedComposeState.GraphRenderInput,
    formatY: (Double) -> String,
): List<String> {
    val values = buildList {
        addAll(renderInput.points.map { it.value })
        addAll(renderInput.predictionPoints.map { it.value })
        renderInput.targetLowMgdl?.let { add(it) }
        renderInput.targetHighMgdl?.let { add(it) }
    }
    if (values.isEmpty()) return emptyList()
    val min = values.minOrNull() ?: return emptyList()
    val maxValue = values.maxOrNull() ?: return emptyList()
    val span = max(1.0, maxValue - min)
    val paddedMin = min - span * 0.12
    val paddedMax = maxValue + span * 0.12
    val ticks = 4
    return (0..ticks).map { idx ->
        val ratio = idx.toDouble() / ticks.toDouble()
        val value = paddedMax - ((paddedMax - paddedMin) * ratio)
        formatY(value)
    }
}
