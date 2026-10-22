package app.aaps.core.ui.compose.dashboard

import app.aaps.core.interfaces.overview.graph.BgType

/**
 * Plain input for [DashboardGraphComposeRenderer].
 *
 * Ported from `plugins/main` `DashboardEmbeddedComposeState.GraphRenderInput` —
 * the Android state holder is replaced by this immutable data class so the
 * renderer stays in commonMain. The platform maps its own state onto this.
 */
data class DashboardGraphRenderInput(
    val fromTimeEpochMs: Long,
    val toTimeEpochMs: Long,
    val nowEpochMs: Long,
    val targetLowMgdl: Double? = null,
    val targetHighMgdl: Double? = null,
    val points: List<DashboardGraphPoint> = emptyList(),
    val predictionPoints: List<DashboardGraphPoint> = emptyList(),
    val smbMarkers: List<DashboardSmbMarker> = emptyList(),
    val tbrMarkerEpochMs: List<Long> = emptyList(),
    val tbrSegments: List<DashboardTbrSegment> = emptyList(),
)

data class DashboardGraphPoint(
    val timestampEpochMs: Long,
    val value: Double,
    val type: BgType? = null,
)

/** Prediction points with different source semantics must never be connected into one curve. */
internal data class DashboardPredictionSeries(
    val type: BgType?,
    val points: List<DashboardGraphPoint>,
)

internal fun dashboardPredictionSeries(
    points: List<DashboardGraphPoint>,
): List<DashboardPredictionSeries> =
    points.groupBy { it.type }
        .map { (type, seriesPoints) -> DashboardPredictionSeries(type, seriesPoints) }

data class DashboardSmbMarker(
    val timestampEpochMs: Long,
)

data class DashboardTbrSegment(
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** 0..1 intensity for bar height. */
    val intensity01: Float,
)
