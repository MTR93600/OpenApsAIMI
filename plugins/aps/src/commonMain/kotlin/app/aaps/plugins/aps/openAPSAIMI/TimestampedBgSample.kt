package app.aaps.plugins.aps.openAPSAIMI

/**
 * Bucketed glucose reading used for time-windowed patient event memory.
 */
data class TimestampedBgSample(
    val timestampMs: Long,
    val bgMgdl: Double,
)
