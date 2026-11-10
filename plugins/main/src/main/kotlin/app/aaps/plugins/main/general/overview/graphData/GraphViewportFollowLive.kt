package app.aaps.plugins.main.general.overview.graphData

import app.aaps.core.interfaces.overview.OverviewData
import com.jjoe64.graphview.GraphView
import kotlin.math.abs

/** Minimum “still on live edge” slack so a few pixels of finger error do not freeze the graph. */
private const val MIN_LIVE_EDGE_TOLERANCE_MS = 180_000.0

/** Cap so we do not treat “half an hour in the past” as live on very wide ranges. */
private const val MAX_LIVE_EDGE_TOLERANCE_MS = 900_000.0

/**
 * Fraction of the configured X window used as tolerance: on a 6h graph, ~2% ≈ 7 min;
 * the old fixed 120s was ~1–2 px and made the chart stay frozen unless the user landed exactly on “now”.
 */
private const val LIVE_EDGE_TOLERANCE_FRACTION_OF_SPAN = 0.02

private fun liveEdgeToleranceMsForOverview(overviewData: OverviewData): Double {
    val spanMs = (overviewData.endTime - overviewData.fromTime).toDouble().coerceAtLeast(60_000.0)
    return (spanMs * LIVE_EDGE_TOLERANCE_FRACTION_OF_SPAN)
        .coerceIn(MIN_LIVE_EDGE_TOLERANCE_MS, MAX_LIVE_EDGE_TOLERANCE_MS)
}

/**
 * True when the viewport’s right edge still tracks [OverviewData.endTime] (“live”) so [GraphData.formatAxis]
 * may reset X; false after the user panned/zoomed away from the live edge.
 */
fun viewportShouldFollowLiveRange(graph: GraphView, overviewData: OverviewData): Boolean {
    val vp = graph.viewport
    if (!vp.isXAxisBoundsManual) return true
    val vpMax = vp.getMaxX(false)
    if (vpMax < 1_000_000_000_000.0) return true
    val liveEnd = overviewData.endTime.toDouble()
    val tolerance = liveEdgeToleranceMsForOverview(overviewData)
    return abs(vpMax - liveEnd) < tolerance
}
