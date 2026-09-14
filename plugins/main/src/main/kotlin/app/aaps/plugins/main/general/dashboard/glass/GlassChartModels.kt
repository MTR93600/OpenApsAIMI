package app.aaps.plugins.main.general.dashboard.glass

data class BgReadingPoint(val progress: Float, val value: Float)
data class IobReadingPoint(val progress: Float, val iob: Float)
data class TreatmentPoint(val progress: Float, val isCarb: Boolean, val label: String, val timestamp: Long = 0L)

/** One basal step: `rateUh` holds from this point's `progress` until the next point (or "now" for the
 *  last one) — a step series, not a smooth curve. Same window-relative `progress` scale as [BgReadingPoint]. */
data class BasalReadingPoint(val progress: Float, val rateUh: Float)

enum class PredictionType { IOB, COB, A_COB, UAM, ZT }
data class PredictionPoint(val progress: Float, val value: Float, val type: PredictionType)

/**
 * Chart-section state for the Glass overview screen — trimmed to what BgChartCard/IobChartCard/TimeFilterBar
 * render. `currentBgValue` is in the SAME space as `bgReadings`' values and `lowLine`/`highLine` — the
 * user's display unit (mg/dL or mmol/L), never raw mg/dL — so the chart never needs its own unit conversion;
 * only display TEXT formatting is the caller's job, via BgChartCard's `formatValue` callback.
 * `axisMinValue`/`axisHeadroom` are also display-unit space, same rule as `lowLine`/`highLine`.
 * `predictions`' `progress` is on a SEPARATE 0..1 scale (0 = now, 1 = end of the fixed prediction horizon),
 * NOT the same window-relative scale as `bgReadings`/`treatments` — `historyFraction` is what a renderer
 * uses to place both scales on one chart width (history occupies `[0, historyFraction]`, predictions
 * occupy `[historyFraction, 1]` of the total chart width).
 */
data class GlassChartState(
    val bgReadings: List<BgReadingPoint> = emptyList(),
    val iobReadings: List<IobReadingPoint> = emptyList(),
    val treatments: List<TreatmentPoint> = emptyList(),
    val predictions: List<PredictionPoint> = emptyList(),
    /** Projected IOB decay assuming no further treatments — same anchor-to-boundary continuity as
     *  [predictions], see [buildGlassChartState]. */
    val iobPredictions: List<IobReadingPoint> = emptyList(),
    /** Actual delivered basal (temp rate when one is running, else the scheduled rate) — a solid step line
     *  with area fill, same window-relative `progress` scale as [bgReadings] (no prediction segment: TBR
     *  is only ever known up to now). */
    val basalReadings: List<BasalReadingPoint> = emptyList(),
    /** Scheduled profile basal, for comparison against [basalReadings] — a dashed step line, no fill. */
    val profileBasalReadings: List<BasalReadingPoint> = emptyList(),
    /** Y-axis scale for both basal series: the dual-axis ceiling is `maxBasalRateUh * 4`, so basal only
     *  ever occupies the bottom ~25% of the chart height (mirrors the classic dashboard's BasalGraphData). */
    val maxBasalRateUh: Float = 1f,
    val historyFraction: Float = 1f,
    val currentBgValue: Float = 0f,
    val currentIob: Float = 0f,
    val lowLine: Float = 70f,
    val highLine: Float = 180f,
    val axisMinValue: Float = 30f,
    val axisHeadroom: Float = 40f,
    val rangeHours: Int = 6,
    val pumpStatusText: String = "",
)
