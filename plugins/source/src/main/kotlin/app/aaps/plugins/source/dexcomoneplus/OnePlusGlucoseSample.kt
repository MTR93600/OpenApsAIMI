package app.aaps.plugins.source.dexcomoneplus

/**
 * Parsed glucose sample from the native One+ session (A6 → A7).
 */
data class OnePlusGlucoseSample(
    val mgdl: Double,
    val timestampMs: Long,
    val trendArrowRaw: String? = null,
    val sequence: Long? = null,
)
