package app.aaps.ui.compose.dashboard

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import kotlin.math.abs

/**
 * The glucose forecast shown on the home screens. Display only: nothing in the loop reads it.
 *
 * ## Why not a loop curve
 *
 * The loop curves (IOB, UAM, COB, ZT in `predBGs`) are scenarios: what would happen if the loop did
 * nothing more. The loop then acts on them, so the real glucose moves away from them on purpose. AIMI also
 * puts "scenarioBest" in the UAM slot, a curve built for dosing that is lifted toward the current glucose.
 * Measured on 4 weeks of one person's data (7332 loop runs, `tools/prediction_eval`), the curve the home
 * screen showed was off by 37 mg/dL on average at 60 min (+48 mg/dL too high during rises), while the
 * measured trend fading out was off by 18 at 30 min and 28 at 60 min, and a flat line by 27 at 60 min.
 *
 * ## What is shown
 *
 * - [centre]: the measured trend of the last 15 minutes, fading out. Each 5-minute step adds the previous
 *   step's change times [TREND_DECAY], so the movement stops within about 15 minutes. Beyond 30 minutes no
 *   curve measured did better than "it stays about here", and this one becomes exactly that.
 * - [low] and [high]: a range around the centre that held the real glucose about 75 % of the time in the
 *   same measurement (checked on data kept out of the calibration). Its half width grows with the horizon,
 *   see [halfWidthMgdl].
 * - [lowRisk]: the insulin-only curve (the IOB slot) goes under [LOW_RISK_MGDL] within the horizon. That
 *   curve is the worst forecast (it does not count food still being absorbed, nor what the loop does next)
 *   but the best early sign of a low: in the same data it saw 78 % of the lows 60 min ahead, with false
 *   alarms in 44 % of normal cases. So it is shown as a labelled possibility, never as the forecast and
 *   never as an alarm.
 *
 * Values are in the user's display unit, like [BgDataPoint.value]. The fixed mg/dL numbers are converted
 * with `mgdlToUnits`.
 */
data class DisplayForecast(
    /** Time of the last measured glucose; the forecast starts there. */
    val startMs: Long,
    /** One point every [STEP_MINUTES] from [startMs] (index 0) to [HORIZON_MINUTES]. */
    val centre: List<ForecastPoint>,
    val low: List<ForecastPoint>,
    val high: List<ForecastPoint>,
    /** The insulin-only curve up to the horizon, only when [lowRisk] is true; otherwise empty. */
    val insulinOnly: List<ForecastPoint>,
    val lowRisk: Boolean,
    /** False when the trend could not be measured; the centre is then flat. */
    val trendKnown: Boolean,
) {

    /** The point of [series] at [minutes] after [startMs], or null when the forecast does not reach it. */
    fun at(series: List<ForecastPoint>, minutes: Int): ForecastPoint? = series.getOrNull(minutes / STEP_MINUTES)

    companion object {

        const val STEP_MINUTES = 5
        private const val STEP_MS = STEP_MINUTES * 60_000L

        /** How far the forecast goes. Past one hour nothing measured beat "it stays about here". */
        const val HORIZON_MINUTES = 60

        /** The number shown on the home screen is at this horizon. */
        const val HEADLINE_MINUTES = 30

        /** Each 5-minute step keeps this share of the previous step's change. Best value of the measurement. */
        const val TREND_DECAY = 0.7

        /** The trend is the change over this time, as measured by the sensor. */
        private const val TREND_WINDOW_MS = 15 * 60_000L

        /** How far a reading may be from the time it stands for. */
        private const val TREND_TOLERANCE_MS = 3 * 60_000L

        /** No forecast from a reading older than this: projecting stale data would mislead. */
        const val MAX_READING_AGE_MS = 10 * 60_000L

        /** The insulin-only curve under this value raises [lowRisk]. */
        const val LOW_RISK_MGDL = 70.0

        /**
         * Half width of the range at each horizon, in mg/dL: the 80th percentile of the absolute error of
         * the centre in the measurement. Between two points it is interpolated, and 0 at the start.
         */
        private val HALF_WIDTH_MGDL = listOf(0 to 0.0, 15 to 15.0, 30 to 27.0, 45 to 35.0, 60 to 41.0)

        fun halfWidthMgdl(minutes: Int): Double {
            val m = minutes.coerceIn(0, HALF_WIDTH_MGDL.last().first)
            val upper = HALF_WIDTH_MGDL.first { it.first >= m }
            if (upper.first == m) return upper.second
            val lower = HALF_WIDTH_MGDL.last { it.first < m }
            val share = (m - lower.first).toDouble() / (upper.first - lower.first)
            return lower.second + (upper.second - lower.second) * share
        }

        /**
         * The forecast, or null when there is no fresh reading.
         *
         * @param readings measured glucose, any order, display unit
         * @param predictions the loop curves from `GraphViewModel.predictionsFlow`; only the insulin-only
         *   curve (IOB slot) is read, for [lowRisk]
         * @param mgdlToUnits converts a mg/dL value to the display unit
         */
        fun build(
            readings: List<BgDataPoint>,
            predictions: List<BgDataPoint>,
            nowMs: Long,
            mgdlToUnits: (Double) -> Double,
        ): DisplayForecast? {
            val measured = readings.filter { it.type in MEASURED_TYPES && it.timestamp <= nowMs && it.value.isFinite() }
            val last = measured.maxByOrNull { it.timestamp } ?: return null
            if (nowMs - last.timestamp > MAX_READING_AGE_MS) return null

            val earlier = measured
                .filter { abs(it.timestamp - (last.timestamp - TREND_WINDOW_MS)) <= TREND_TOLERANCE_MS }
                .minByOrNull { abs(it.timestamp - (last.timestamp - TREND_WINDOW_MS)) }
            val stepChange = earlier?.let { (last.value - it.value) * STEP_MS / (last.timestamp - it.timestamp).toDouble() }

            val steps = HORIZON_MINUTES / STEP_MINUTES
            val centre = ArrayList<ForecastPoint>(steps + 1)
            val low = ArrayList<ForecastPoint>(steps + 1)
            val high = ArrayList<ForecastPoint>(steps + 1)
            var value = last.value
            var change = stepChange ?: 0.0
            for (i in 0..steps) {
                if (i > 0) {
                    change *= TREND_DECAY
                    value += change
                }
                val time = last.timestamp + i * STEP_MS
                val half = mgdlToUnits(halfWidthMgdl(i * STEP_MINUTES))
                centre += ForecastPoint(time, value)
                low += ForecastPoint(time, value - half)
                high += ForecastPoint(time, value + half)
            }

            val horizonEnd = last.timestamp + HORIZON_MINUTES * 60_000L
            val insulinOnly = predictions
                .filter { it.type == BgType.IOB_PREDICTION && it.timestamp in last.timestamp..horizonEnd && it.value.isFinite() }
                .sortedBy { it.timestamp }
            val lowRisk = insulinOnly.any { it.value < mgdlToUnits(LOW_RISK_MGDL) }

            return DisplayForecast(
                startMs = last.timestamp,
                centre = centre,
                low = low,
                high = high,
                insulinOnly = if (lowRisk) insulinOnly.map { ForecastPoint(it.timestamp, it.value) } else emptyList(),
                lowRisk = lowRisk,
                trendKnown = stepChange != null,
            )
        }

        /** Measured readings; every other type is a loop curve. */
        private val MEASURED_TYPES = setOf(BgType.REGULAR, BgType.BUCKETED)
    }
}

/** One point of a [DisplayForecast], in the user's display unit. */
data class ForecastPoint(val timeMs: Long, val value: Double)
