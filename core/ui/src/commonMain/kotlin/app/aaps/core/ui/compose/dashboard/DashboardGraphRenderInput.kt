package app.aaps.core.ui.compose.dashboard

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
)

data class DashboardSmbMarker(
    val timestampEpochMs: Long,
)

data class DashboardTbrSegment(
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** 0..1 intensity for bar height. */
    val intensity01: Float,
)
