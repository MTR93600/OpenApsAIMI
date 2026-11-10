package app.aaps.plugins.main.general.dashboard

import app.aaps.core.interfaces.overview.graph.ChartSmbMarker
import app.aaps.core.interfaces.overview.graph.ChartTbrSegment

/**
 * Central builder for Compose graph render snapshots.
 *
 * Keeping this mapping isolated from [DashboardShellController] makes graph-state evolution easier
 * to test and migrate when the legacy [app.aaps.plugins.main.general.dashboard.views.GlucoseGraphView]
 * is replaced by a pure Compose renderer.
 */
internal object GraphRenderInputFactory {

    fun build(
        rangeHours: Int,
        hasBgData: Boolean,
        followLive: Boolean,
        graphPanActive: Boolean = false,
        lastRefreshEpochMs: Long,
        fromTimeEpochMs: Long,
        toTimeEpochMs: Long,
        nowEpochMs: Long,
        targetLowMgdl: Double?,
        targetHighMgdl: Double?,
        points: List<DashboardEmbeddedComposeState.GraphPoint>,
        predictionPoints: List<DashboardEmbeddedComposeState.GraphPoint> = emptyList(),
        smbMarkers: List<ChartSmbMarker> = emptyList(),
        tbrMarkerEpochMs: List<Long> = emptyList(),
        tbrSegments: List<ChartTbrSegment> = emptyList(),
    ): DashboardEmbeddedComposeState.GraphRenderInput =
        DashboardEmbeddedComposeState.GraphRenderInput(
            rangeHours = rangeHours,
            hasBgData = hasBgData,
            followLive = followLive,
            graphPanActive = graphPanActive,
            lastRefreshEpochMs = lastRefreshEpochMs,
            fromTimeEpochMs = fromTimeEpochMs,
            toTimeEpochMs = toTimeEpochMs,
            nowEpochMs = nowEpochMs,
            targetLowMgdl = targetLowMgdl,
            targetHighMgdl = targetHighMgdl,
            points = points,
            predictionPoints = predictionPoints,
            smbMarkers = smbMarkers,
            tbrMarkerEpochMs = tbrMarkerEpochMs,
            tbrSegments = tbrSegments,
        )
}
