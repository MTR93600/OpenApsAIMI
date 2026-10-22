package app.aaps.core.ui.compose.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.overview.graph.BgType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dashboard graph card in commonMain.
 *
 * Ported from `plugins/main` `DashboardGraphComposeCard`. The Android version
 * had two rendering paths (Vico + legacy); this port keeps the Canvas path
 * ([DashboardGraphComposeRenderer]) with fully hoisted state:
 * - `GraphViewModel` flows → [DashboardGraphCardUiState] (pre-computed)
 * - `DashboardEmbeddedComposeState` → [DashboardGraphRenderInput] + callbacks
 * - `R.string` / `dimensionResource` → [DashboardGraphCardStrings] + dp constants
 * - Vico scroll/zoom → dropped (the Canvas renderer uses a fixed time window)
 * - SMB tap toast → [onSmbMarkerTap] callback (platform shows the toast)
 */

/** Hoisted UI state for [DashboardGraphComposeCard]. */
data class DashboardGraphCardUiState(
    val renderInput: DashboardGraphRenderInput,
    val showPredictionLegend: Boolean = false,
    val showScenarioNearBgHint: Boolean = false,
    val hasSmbMarkers: Boolean = false,
    val hasTbrSegments: Boolean = false,
    /** Pre-computed status row (range, data state, follow state). */
    val statusSummary: String = "",
    /** Pre-computed freshness line. */
    val freshnessText: String = "",
    /** Freshness level for the text color. */
    val freshnessLevel: GraphFreshnessLevel = GraphFreshnessLevel.UNKNOWN,
    val updateMessage: String = "",
    val hideDetailedGraphStatus: Boolean = false,
    val expandVertically: Boolean = false,
    val controlsState: DashboardGraphControlsUiState? = null,
    /** The shared viewport, owned by the screen's graph state. */
    val viewport: DashboardGraphViewport? = null,
    /**
     * Updates the owner with gesture changes. The owner should update its viewport and derive
     * viewport-dependent status and tick labels in the same state update.
     */
    val onViewportChanged: ((DashboardGraphViewport) -> Unit)? = null,
    /** Formats viewport-dependent status and tick labels without moving locale policy into common UI. */
    val viewportPresentation: ((DashboardGraphViewport) -> DashboardGraphViewportPresentation)? = null,
    /** Y-axis labels, pre-formatted by the platform. */
    val yAxisLabels: List<String> = emptyList(),
    /** X-axis tick labels, pre-formatted by the platform. */
    val tickLabels: List<String> = emptyList(),
    val onDoubleTap: (() -> Unit)? = null,
    val onLongPress: (() -> Unit)? = null,
    val onSmbMarkerTap: ((DashboardSmbMarker) -> Unit)? = null,
)

/** Localized axis and status strings for a specific graph viewport. */
data class DashboardGraphViewportPresentation(
    val statusSummary: String,
    val freshnessText: String,
    val freshnessLevel: GraphFreshnessLevel,
    val tickLabels: List<String>,
)

/** Resolved strings for [DashboardGraphComposeCard]. */
data class DashboardGraphCardStrings(
    val graphHeader: String,
    val bloodGlucoseA11y: String,
    val scenarioFloorLabel: String,
    val scenarioBestLabel: String,
    val predictionShort: String,
    val predictionIobLabel: String,
    val predictionCobLabel: String,
    val predictionAbsorbedCobLabel: String,
    val predictionUamLabel: String,
    val predictionZeroTempLabel: String,
    val smbLegend: String,
    val tbrLegend: String,
    val scenarioNearBgHint: String,
    /** (amountLabel) -> toast text for SMB marker taps. */
    val smbToast: (String) -> String,
    val statsButton: String,
    val range6h: String,
    val range12h: String,
    val range18h: String,
    val range24h: String,
    val treatmentsLabel: String,
)

@Composable
fun DashboardGraphComposeCard(
    state: DashboardGraphCardUiState,
    strings: DashboardGraphCardStrings,
    modifier: Modifier = Modifier,
) {
    val renderInput = state.renderInput
    val viewport = state.viewport ?: DashboardGraphViewport.from(renderInput)
    val activeViewport = viewport?.takeIf {
        state.onViewportChanged != null && state.viewportPresentation != null
    }
    val viewportEnabled = activeViewport != null
    val displayedInput = activeViewport?.applyTo(renderInput) ?: renderInput
    val viewportPresentation = activeViewport?.let { state.viewportPresentation?.invoke(it) }
    val gestureViewport = remember(
        activeViewport?.dataStartEpochMs,
        activeViewport?.dataEndEpochMs,
    ) { mutableStateOf(activeViewport) }
    // The owner may update its viewport as new graph data arrives or controls change. Keep the
    // stable gesture state holder synchronized without keying pointerInput on every viewport tick.
    SideEffect { gestureViewport.value = activeViewport }
    val displayedStatusSummary = viewportPresentation?.statusSummary ?: state.statusSummary
    val displayedFreshnessText = viewportPresentation?.freshnessText ?: state.freshnessText
    val displayedFreshnessLevel = viewportPresentation?.freshnessLevel ?: state.freshnessLevel
    val displayedTickLabels = viewportPresentation?.tickLabels ?: state.tickLabels
    val sectionSpacing = 8.dp
    val graphMinHeight = 180.dp
    val graphCorner = 16.dp

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.52f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = strings.graphHeader,
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
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f)),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = strings.bloodGlucoseA11y,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val predictionTypes = renderInput.predictionPoints.map { it.type }.distinct()
                    if (state.showPredictionLegend && predictionTypes.isEmpty()) {
                        DashboardScenarioProjectionLegend(
                            floorColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.62f),
                            bestColor = MaterialTheme.colorScheme.tertiary,
                            floorLabel = strings.scenarioFloorLabel,
                            bestLabel = strings.scenarioBestLabel,
                        )
                    } else if (predictionTypes.isNotEmpty()) {
                        predictionTypes.forEach { type ->
                            DashboardGraphPredictionLegend(
                                label = when (type) {
                                    BgType.IOB_PREDICTION -> strings.predictionIobLabel
                                    BgType.COB_PREDICTION -> strings.predictionCobLabel
                                    BgType.A_COB_PREDICTION -> strings.predictionAbsorbedCobLabel
                                    BgType.UAM_PREDICTION -> strings.predictionUamLabel
                                    BgType.ZT_PREDICTION -> strings.predictionZeroTempLabel
                                    BgType.REGULAR, BgType.BUCKETED, null -> strings.predictionShort
                                },
                                color = dashboardPredictionSeriesColor(type, MaterialTheme.colorScheme),
                            )
                        }
                    } else if (renderInput.predictionPoints.isNotEmpty()) {
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
                                text = strings.predictionShort,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.hasSmbMarkers) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "▲",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.smbLegend,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (state.hasTbrSegments) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(12.dp)
                                    .background(MaterialTheme.colorScheme.tertiary),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = strings.tbrLegend,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    state.controlsState?.let { controls ->
                        DashboardGraphComposeControls(
                            state = controls,
                            strings = strings,
                            modifier = Modifier.height(34.dp),
                        )
                    }
                }
            }
            if (state.showScenarioNearBgHint) {
                Text(
                    text = strings.scenarioNearBgHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = sectionSpacing),
                )
            }
            if (!state.hideDetailedGraphStatus && state.updateMessage.isNotBlank()) {
                Text(
                    text = state.updateMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = sectionSpacing),
                )
            }
            if (!state.hideDetailedGraphStatus) {
                if (displayedStatusSummary.isNotBlank()) {
                    Text(
                        text = displayedStatusSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                    )
                }
                if (displayedFreshnessText.isNotBlank()) {
                    val freshnessColor = when (displayedFreshnessLevel) {
                        GraphFreshnessLevel.FRESH -> MaterialTheme.colorScheme.primary
                        GraphFreshnessLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                        GraphFreshnessLevel.STALE -> MaterialTheme.colorScheme.error
                        GraphFreshnessLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = displayedFreshnessText,
                        style = MaterialTheme.typography.labelSmall,
                        color = freshnessColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = sectionSpacing),
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(sectionSpacing))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = graphMinHeight)
                    .pointerInput(state.onDoubleTap, state.onLongPress) {
                        detectTapGestures(
                            onDoubleTap = { state.onDoubleTap?.invoke() },
                            onLongPress = { state.onLongPress?.invoke() },
                        )
                    },
                verticalAlignment = Alignment.Bottom,
            ) {
                if (state.yAxisLabels.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .width(44.dp)
                            .height(graphMinHeight)
                            .padding(end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        state.yAxisLabels.forEach { label ->
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
                        .clip(RoundedCornerShape(graphCorner)),
                ) {
                    val density = LocalDensity.current
                    val smbTapModifier =
                        if (state.onSmbMarkerTap != null && displayedInput.smbMarkers.isNotEmpty()) {
                            Modifier.pointerInput(displayedInput.smbMarkers) {
                                detectTapGestures { offset ->
                                    val marker = findNearestSmbByX(
                                        tapX = offset.x,
                                        widthPx = size.width.toFloat(),
                                        hitPx = with(density) { 36.dp.toPx() },
                                        input = displayedInput,
                                    )
                                    if (marker != null) state.onSmbMarkerTap?.invoke(marker)
                                }
                            }
                        } else {
                            Modifier
                        }
                    val viewportCallback = rememberUpdatedState(state.onViewportChanged)
                    val viewportGestureModifier =
                        if (viewportEnabled) {
                            Modifier.pointerInput(
                                activeViewport?.dataStartEpochMs,
                                activeViewport?.dataEndEpochMs,
                            ) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val current = gestureViewport.value ?: return@detectTransformGestures
                                    val width = size.width.toFloat()
                                    if (width <= 0f) return@detectTransformGestures
                                    val plotWidth = dashboardGraphPlotWidthPx(width)
                                    val updated = current
                                        .panByPlot(deltaXPx = pan.x, canvasWidthPx = width)
                                        .zoomBy(
                                            scaleFactor = zoom,
                                            anchorFraction = (centroid.x / plotWidth).coerceIn(0f, 1f),
                                        )
                                    gestureViewport.value = updated
                                    viewportCallback.value?.invoke(updated)
                                }
                            }
                        } else {
                            Modifier
                        }
                    DashboardGraphComposeRenderer(
                        renderInput = displayedInput,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(graphMinHeight)
                            .then(viewportGestureModifier)
                            .then(smbTapModifier),
                    )
                    if (displayedTickLabels.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp)
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            displayedTickLabels.forEach { label ->
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.88f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardGraphPredictionLegend(
    label: String,
    color: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Finds the SMB marker nearest to [tapX], or null when none is within [hitPx].
 * Ported from `plugins/main` (pure math, no Android deps).
 */
fun findNearestSmbByX(
    tapX: Float,
    widthPx: Float,
    hitPx: Float,
    input: DashboardGraphRenderInput,
    chartPlotStartPx: Float = 0f,
): DashboardSmbMarker? {
    if (input.smbMarkers.isEmpty() || widthPx <= 1f) return null
    val plotRight = dashboardGraphPlotWidthPx(widthPx)
    val plotLeft = chartPlotStartPx
    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
    val basePoints = if (input.points.isNotEmpty()) input.points else input.predictionPoints
    val minX = input.fromTimeEpochMs.takeIf { it > 0L }
        ?: basePoints.minOfOrNull { it.timestampEpochMs }
        ?: input.smbMarkers.minOfOrNull { it.timestampEpochMs }
        ?: return null
    val maxX = input.toTimeEpochMs.takeIf { it > minX }
        ?: basePoints.maxOfOrNull { it.timestampEpochMs }
        ?: input.smbMarkers.maxOfOrNull { it.timestampEpochMs }
        ?: return null
    if (maxX <= minX) return null
    val xRange = max(1L, maxX - minX).toFloat()
    fun toCanvasX(epochMs: Long): Float =
        plotLeft + (((epochMs - minX) / xRange) * plotWidth)

    var best: DashboardSmbMarker? = null
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
