package app.aaps.plugins.main.general.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.aaps.core.interfaces.overview.graph.ChartSmbMarker
import app.aaps.core.interfaces.overview.graph.ChartTbrSegment
import app.aaps.plugins.main.general.dashboard.viewmodel.AdjustmentCardState
import app.aaps.plugins.main.general.overview.notifications.NotificationStore

/**
 * UI state driven from [DashboardShellController] when the AIMI hero is pure Compose (no
 * [app.aaps.plugins.main.general.dashboard.views.CircleTopDashboardView]).
 */
class DashboardEmbeddedComposeState {
    data class GraphFreshnessConfig(
        val warningThresholdMinutes: Int = 5,
        val staleThresholdMinutes: Int = 15,
    )

    data class GraphComposeUiState(
        val rangeHours: Int = 6,
        val updateMessage: String = "",
        val freshnessConfig: GraphFreshnessConfig = GraphFreshnessConfig(),
        val attachLegacyGraphBackend: Boolean = false,
    )

    data class GraphRenderInput(
        val rangeHours: Int = 6,
        val hasBgData: Boolean = false,
        val followLive: Boolean = true,
        /** When true, the user has panned the Compose graph into the past (not snapped to live right edge). */
        val graphPanActive: Boolean = false,
        val lastRefreshEpochMs: Long = 0L,
        val fromTimeEpochMs: Long = 0L,
        val toTimeEpochMs: Long = 0L,
        val nowEpochMs: Long = 0L,
        val targetLowMgdl: Double? = null,
        val targetHighMgdl: Double? = null,
        val points: List<GraphPoint> = emptyList(),
        val predictionPoints: List<GraphPoint> = emptyList(),
        val smbMarkers: List<ChartSmbMarker> = emptyList(),
        val tbrMarkerEpochMs: List<Long> = emptyList(),
        val tbrSegments: List<ChartTbrSegment> = emptyList(),
    )

    data class GraphPoint(
        val timestampEpochMs: Long,
        val value: Double,
    )

    data class GraphComposeCommands(
        val onSelectRange: ((Int) -> Unit)? = null,
        val onDoubleTap: (() -> Unit)? = null,
        val onLongPress: (() -> Unit)? = null,
        /** Horizontal pan: fraction of the current range width (positive = finger moved right → view older data). */
        val onGraphPanFromDragFraction: ((Float) -> Unit)? = null,
    )

    var contextIndicatorVisible by mutableStateOf(false)
    var adjustmentCardState by mutableStateOf<AdjustmentCardState?>(null)
    var notifications by mutableStateOf<List<NotificationStore.NotificationComposeItem>>(emptyList())
    var onOpenAdjustmentDetails by mutableStateOf<(() -> Unit)?>(null)
    var onRunLoopRequested by mutableStateOf<(() -> Unit)?>(null)
    var onDismissNotification by mutableStateOf<((Int) -> Unit)?>(null)
    var graphUiState by mutableStateOf(GraphComposeUiState())
    var graphRenderInput by mutableStateOf(GraphRenderInput())
    var graphCommands by mutableStateOf(GraphComposeCommands())

    /**
     * Incremented when the shell snaps the graph to « live » (range change, recovery, etc.) so the
     * Vico dashboard chart can reset scroll/zoom to match the legacy Canvas viewport behaviour.
     */
    var vicoViewportResetGeneration by mutableIntStateOf(0)
        private set

    internal fun bumpVicoViewportReset() {
        vicoViewportResetGeneration++
    }

    /**
     * When the dashboard uses Vico, reflects whether the chart viewport is at the « live » edge
     * (right side), as opposed to the user having panned into history.
     */
    var vicoViewportFollowingLive by mutableStateOf(true)
        private set

    internal fun setVicoViewportFollowingLive(following: Boolean) {
        if (vicoViewportFollowingLive != following) {
            vicoViewportFollowingLive = following
        }
    }

    internal fun resetVicoViewportFollowState() {
        vicoViewportFollowingLive = true
    }

    /**
     * Horizontal pan for the Compose-only graph: shifts the visible window backward in time
     * (right edge moves left from live), clamped in [DashboardShellController] to loaded BG data.
     */
    var graphPanPastMs by mutableLongStateOf(0L)
        internal set

    var metricsPreferencesSync by mutableIntStateOf(0)
        private set

    internal fun requestMetricsPreferenceResync() {
        metricsPreferencesSync++
    }

    fun updateGraphRange(hours: Int) {
        graphUiState = graphUiState.copy(rangeHours = hours)
        graphRenderInput = graphRenderInput.copy(rangeHours = hours)
    }

    fun updateGraphMessage(message: String) {
        graphUiState = graphUiState.copy(updateMessage = message)
    }

    fun updateGraphFreshnessConfig(config: GraphFreshnessConfig) {
        graphUiState = graphUiState.copy(freshnessConfig = config)
    }

    fun updateAttachLegacyGraphBackend(enabled: Boolean) {
        graphUiState = graphUiState.copy(attachLegacyGraphBackend = enabled)
    }

    fun updateGraphCommands(commands: GraphComposeCommands) {
        graphCommands = commands
    }

    fun updateGraphRenderInput(input: GraphRenderInput) {
        graphRenderInput = input
    }

    fun resetGraphPan() {
        graphPanPastMs = 0L
    }

    internal fun accumulateGraphPanPastMs(deltaMs: Long) {
        graphPanPastMs = (graphPanPastMs + deltaMs).coerceAtLeast(0L)
    }

    internal fun clampGraphPanPastMs(maxPanPastMs: Long) {
        graphPanPastMs = graphPanPastMs.coerceIn(0L, maxPanPastMs.coerceAtLeast(0L))
    }
}
