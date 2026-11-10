package app.aaps.core.interfaces.overview.graph

/**
 * SMB marker on dashboard-style charts (time + label only; UI maps to geometry).
 */
data class ChartSmbMarker(
    val timestampEpochMs: Long,
    val amountLabel: String,
)

/**
 * Temp basal segment in a visible time window: horizontal extent is duration,
 * [intensity01] scales bar height inside the TBR lane (visual floor applied in UI).
 */
data class ChartTbrSegment(
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** 0..1 for bar height in the TBR lane (minimum visual floor applied in renderer). */
    val intensity01: Float,
)
