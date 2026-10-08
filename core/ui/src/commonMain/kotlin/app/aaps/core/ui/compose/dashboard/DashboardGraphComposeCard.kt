package app.aaps.core.ui.compose.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /** Y-axis labels, pre-formatted by the platform. */
    val yAxisLabels: List<String> = emptyList(),
    /** X-axis tick labels, pre-formatted by the platform. */
    val tickLabels: List<String> = emptyList(),
    val onDoubleTap: (() -> Unit)? = null,
    val onLongPress: (() -> Unit)? = null,
    val onSmbMarkerTap: ((DashboardSmbMarker) -> Unit)? = null,
)

/** Resolved strings for [DashboardGraphComposeCard]. */
data class DashboardGraphCardStrings(
    val graphHeader: String,
    val bloodGlucoseA11y: String,
    val scenarioFloorLabel: String,
    val scenarioBestLabel: String,
    val predictionShort: String,
    val smbLegend: String,
    val tbrLegend: String,
    val scenarioNearBgHint: String,
    /** (amountLabel) -> toast text for SMB marker taps. */
    val smbToast: (String) -> String,
)

@Composable
fun DashboardGraphComposeCard(
    state: DashboardGraphCardUiState,
    strings: DashboardGraphCardStrings,
    cardStrings: DashboardCardStrings,
    modifier: Modifier = Modifier,
) {
    val renderInput = state.renderInput
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
                    if (state.showPredictionLegend) {
                        DashboardScenarioProjectionLegend(
                            floorColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.62f),
                            bestColor = MaterialTheme.colorScheme.tertiary,
                            floorLabel = strings.scenarioFloorLabel,
                            bestLabel = strings.scenarioBestLabel,
                        )
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
                            strings = cardStrings,
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
                if (state.statusSummary.isNotBlank()) {
                    Text(
                        text = state.statusSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 2.dp),
                    )
                }
                if (state.freshnessText.isNotBlank()) {
                    val freshnessColor = when (state.freshnessLevel) {
                        GraphFreshnessLevel.FRESH -> MaterialTheme.colorScheme.primary
                        GraphFreshnessLevel.WARNING -> MaterialTheme.colorScheme.tertiary
                        GraphFreshnessLevel.STALE -> MaterialTheme.colorScheme.error
                        GraphFreshnessLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        text = state.freshnessText,
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
                        if (state.onSmbMarkerTap != null && renderInput.smbMarkers.isNotEmpty()) {
                            Modifier.pointerInput(renderInput.smbMarkers) {
                                detectTapGestures { offset ->
                                    val marker = findNearestSmbByX(
                                        tapX = offset.x,
                                        widthPx = size.width.toFloat(),
                                        hitPx = with(density) { 36.dp.toPx() },
                                        input = renderInput,
                                    )
                                    if (marker != null) state.onSmbMarkerTap?.invoke(marker)
                                }
                            }
                        } else {
                            Modifier
                        }
                    DashboardGraphComposeRenderer(
                        renderInput = renderInput,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(graphMinHeight)
                            .then(smbTapModifier),
                    )
                    if (state.tickLabels.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp)
                                .padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            state.tickLabels.forEach { label ->
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
    val reservedFabInset = min(72f, widthPx * 0.16f)
    val plotRight = widthPx - reservedFabInset
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
