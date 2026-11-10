package app.aaps.plugins.main.general.dashboard.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.ui.compose.overview.graphs.DEFAULT_GRAPH_ZOOM_MINUTES
import app.aaps.ui.compose.overview.graphs.PREDICTION_VIEWPORT_FUTURE_BIAS_MINUTES
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.graphs.timestampToX
import com.patrykandpatrick.vico.compose.cartesian.Scroll
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import kotlin.math.max
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

private const val VICO_SCROLL_LIVE_EDGE_EPSILON_PX = 36f

/**
 * Live edge + zoom reset + "follow new BG" scroll — shared by dashboard BG and IOB Vico charts
 * so both stay aligned (same as Overview [app.aaps.ui.compose.overview.graphs.GraphsSection] idea).
 */
@OptIn(FlowPreview::class)
@Composable
internal fun DashboardVicoSharedViewportEffects(
    graphViewModel: GraphViewModel,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    viewportResetGeneration: Int,
    viewportFollowingLive: Boolean,
    onViewportFollowingLiveChanged: (Boolean) -> Unit,
    shellControlsHorizontalWindow: Boolean,
    bgOverlays: List<SeriesType>,
) {
    val maxScrollSeen = remember(viewportResetGeneration) {
        mutableFloatStateOf(Float.NEGATIVE_INFINITY)
    }

    LaunchedEffect(viewportResetGeneration) {
        if (viewportResetGeneration <= 0) return@LaunchedEffect
        maxScrollSeen.floatValue = Float.NEGATIVE_INFINITY
        zoomState.zoom(Zoom.x(DEFAULT_GRAPH_ZOOM_MINUTES))
        delay(10)
        scrollState.animateScroll(Scroll.Absolute.End)
        delay(390)
        maxScrollSeen.floatValue = max(maxScrollSeen.floatValue, scrollState.value)
        onViewportFollowingLiveChanged(true)
    }

    if (!shellControlsHorizontalWindow) {
        LaunchedEffect(scrollState, viewportResetGeneration) {
            snapshotFlow { scrollState.value }
                .debounce(40)
                .collect { scroll ->
                    maxScrollSeen.floatValue = max(maxScrollSeen.floatValue, scroll)
                    val atLiveEdge = scroll >= maxScrollSeen.floatValue - VICO_SCROLL_LIVE_EDGE_EPSILON_PX
                    onViewportFollowingLiveChanged(atLiveEdge)
                }
        }
    }

    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
    var lastBgTimestamp by remember { mutableLongStateOf(0L) }
    var scenarioViewportInitialized by remember(viewportResetGeneration) { mutableStateOf(false) }

    LaunchedEffect(predictions.size, derivedTimeRange, viewportFollowingLive, viewportResetGeneration) {
        if (scenarioViewportInitialized || !viewportFollowingLive) return@LaunchedEffect
        val showPredictions = SeriesType.PREDICTIONS in bgOverlays
        val timeRange = derivedTimeRange
        if (!showPredictions || predictions.isEmpty() || timeRange == null) return@LaunchedEffect
        val (minTimestamp, _) = timeRange
        val nowX = timestampToX(System.currentTimeMillis(), minTimestamp)
        scrollState.animateScroll(Scroll.Absolute.x(nowX + PREDICTION_VIEWPORT_FUTURE_BIAS_MINUTES, bias = 1f))
        scenarioViewportInitialized = true
    }

    LaunchedEffect(bgInfoState.bgInfo?.timestamp, viewportFollowingLive) {
        val newTimestamp = bgInfoState.bgInfo?.timestamp ?: return@LaunchedEffect
        val showPredictions = SeriesType.PREDICTIONS in bgOverlays
        if (viewportFollowingLive &&
            lastBgTimestamp != 0L &&
            newTimestamp > lastBgTimestamp
        ) {
            val timeRange = derivedTimeRange
            if (showPredictions && predictions.isNotEmpty() && timeRange != null) {
                val (minTimestamp, _) = timeRange
                val nowX = timestampToX(System.currentTimeMillis(), minTimestamp)
                scrollState.animateScroll(Scroll.Absolute.x(nowX + PREDICTION_VIEWPORT_FUTURE_BIAS_MINUTES, bias = 1f))
            } else {
                scrollState.animateScroll(Scroll.Absolute.End)
            }
        }
        lastBgTimestamp = newTimestamp
    }

    LaunchedEffect(scrollState, zoomState) {
        snapshotFlow { scrollState.value to zoomState.value }
            .drop(1)
            .debounce(30)
            .collect { graphViewModel.onGraphInteraction() }
    }
}
