package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.ui.compose.overview.graphs.ChartConfig
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression tests for [buildGlassChartState] — the mapper from raw
 * `app.aaps.ui.compose.overview.graphs.GraphViewModel` flow data into the Glass skin's
 * [GlassChartState]. Covers the window filter, the progress mapping at the window edges, the
 * unit-space passthrough of `chartConfig.lowMark`/`.highMark` (must NOT be run through
 * `mgdlToChartY` a second time), and treatment merging.
 */
class GlassChartStateMapperTest {

    private val identity: (Double) -> Double = { it }

    private fun bg(timestamp: Long, value: Double) =
        BgDataPoint(timestamp = timestamp, value = value, range = BgRange.IN_RANGE, type = BgType.BUCKETED)

    @Test
    fun `reading before the window start is excluded`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(windowStart - 1L, 120.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).isEmpty()
    }

    @Test
    fun `reading at nowEpochMs maps to progress 1 and reading at windowStart maps to progress 0`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(windowStart, 90.0), bg(nowEpochMs, 150.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).hasSize(2)
        assertThat(result.bgReadings[0].progress).isEqualTo(0f)
        assertThat(result.bgReadings[1].progress).isEqualTo(1f)
    }

    @Test
    fun `lowLine and highLine pass through chartConfig unconverted, not run through mgdlToChartY again`() {
        val nowEpochMs = 100_000_000L
        // A conversion that would be obviously wrong if applied to lowMark/highMark a second time.
        val suspiciousConverter: (Double) -> Double = { it / 18.0 }

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = suspiciousConverter,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.lowLine).isEqualTo(70f)
        assertThat(result.highLine).isEqualTo(180f)
    }

    @Test
    fun `bg reading value is run through mgdlToChartY, not passed through as raw mg-dL`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        // mmol/L-style converter: distinct from identity so a dropped conversion would fail this test.
        val mgdlToMmol: (Double) -> Double = { it / 18.0 }

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(nowEpochMs, 180.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = mgdlToMmol,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).hasSize(1)
        assertThat(result.bgReadings[0].value).isEqualTo(10f)
        assertThat(result.currentBgValue).isEqualTo(10f)
    }

    @Test
    fun `a bolus and a carb in the same window both appear in treatments with correct isCarb`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L
        val bolusTime = windowStart + 1_000_000L
        val carbTime = windowStart + 2_000_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = listOf(
                BolusGraphPoint(timestamp = bolusTime, amount = 1.5, bolusType = BolusType.NORMAL, isValid = true, label = "1.50 U"),
            ),
            carbs = listOf(
                CarbsGraphPoint(timestamp = carbTime, amount = 45.0, isValid = true, label = "45 g"),
            ),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.treatments).hasSize(2)
        val bolusPoint = result.treatments.first { it.label == "1.50 U" }
        val carbPoint = result.treatments.first { it.label == "45 g" }
        assertThat(bolusPoint.isCarb).isFalse()
        assertThat(carbPoint.isCarb).isTrue()
    }

    @Test
    fun `empty input lists produce an empty GlassChartState without throwing`() {
        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = 100_000_000L,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).isEmpty()
        assertThat(result.iobReadings).isEmpty()
        assertThat(result.treatments).isEmpty()
        assertThat(result.currentBgValue).isEqualTo(0f)
        assertThat(result.currentIob).isEqualTo(0f)
    }

    @Test
    fun `axisMinValue and axisHeadroom are converted through mgdlToChartY, not left as raw mg-dL constants`() {
        val mgdlToMmol: (Double) -> Double = { it / 18.0 }

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = 100_000_000L,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = mgdlToMmol,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.axisMinValue).isWithin(0.01f).of((30.0 / 18.0).toFloat())
        assertThat(result.axisHeadroom).isWithin(0.01f).of((40.0 / 18.0).toFloat())
    }

    @Test
    fun `iob points outside the window are excluded, matching the bg reading filter`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = listOf(
                GraphDataPoint(timestamp = windowStart - 1L, value = 3.0),
                GraphDataPoint(timestamp = windowStart, value = 2.0),
            ),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.iobReadings).hasSize(1)
        assertThat(result.currentIob).isEqualTo(2f)
    }

    @Test
    fun `IOB prediction is anchored to its own first point, closing the gap to the history line`() {
        val nowEpochMs = 100_000_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            // The last cached history point is a few minutes stale, same as a real GraphViewModel cache tick.
            iobPoints = listOf(GraphDataPoint(timestamp = nowEpochMs - 4 * 60_000L, value = 2.5)),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
            iobPredictionPoints = listOf(
                GraphDataPoint(timestamp = nowEpochMs, value = 2.4),
                GraphDataPoint(timestamp = nowEpochMs + 3_600_000L, value = 1.2),
            ),
        )

        // History gets a connector at progress=1, using the prediction's own fresh anchor value (2.4), not
        // the stale cached history value (2.5) — the two data sources can legitimately disagree slightly.
        assertThat(result.iobReadings).hasSize(2)
        assertThat(result.iobReadings[1].progress).isEqualTo(1f)
        assertThat(result.iobReadings[1].iob).isEqualTo(2.4f)

        assertThat(result.iobPredictions).hasSize(2)
        assertThat(result.iobPredictions[0].progress).isEqualTo(0f)
        assertThat(result.iobPredictions[0].iob).isEqualTo(2.4f)
        assertThat(result.iobPredictions[1].progress).isGreaterThan(0f)
    }

    @Test
    fun `no IOB prediction data produces no connector and no prediction points`() {
        val nowEpochMs = 100_000_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = listOf(GraphDataPoint(timestamp = nowEpochMs - 4 * 60_000L, value = 2.5)),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
            iobPredictionPoints = emptyList(),
        )

        assertThat(result.iobReadings).hasSize(1)
        assertThat(result.iobPredictions).isEmpty()
    }

    @Test
    fun `IOB prediction outside the 2-hour horizon is excluded`() {
        val nowEpochMs = 100_000_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
            iobPredictionPoints = listOf(
                GraphDataPoint(timestamp = nowEpochMs, value = 2.0),
                GraphDataPoint(timestamp = nowEpochMs + 3 * 3_600_000L, value = 0.0),
            ),
        )

        assertThat(result.iobPredictions).hasSize(1)
        assertThat(result.iobPredictions[0].progress).isEqualTo(0f)
    }

    @Test
    fun `predictions outside the 2-hour horizon are excluded, and progress is 0 at now, 1 at horizon end`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            // A fresh reading is required for predictions to render at all (see the staleness-gating test
            // below) — predictions are otherwise suppressed as untrustworthy.
            bgReadings = listOf(bg(nowEpochMs, 120.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = listOf(
                BgDataPoint(timestamp = nowEpochMs, value = 120.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
                BgDataPoint(timestamp = nowEpochMs + 3_600_000L, value = 100.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
                BgDataPoint(timestamp = nowEpochMs + 2 * 3_600_000L, value = 90.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
                BgDataPoint(timestamp = nowEpochMs + 3 * 3_600_000L, value = 80.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
            ),
        )

        assertThat(result.predictions).hasSize(3)
        assertThat(result.predictions[0].progress).isEqualTo(0f)
        assertThat(result.predictions[2].progress).isEqualTo(1f)
        assertThat(result.historyFraction).isWithin(0.001f).of(6f / 8f)
    }

    @Test
    fun `prediction line is anchored to the last BG reading, so it starts where the history line ends`() {
        val nowEpochMs = 100_000_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(nowEpochMs, 145.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = listOf(
                BgDataPoint(timestamp = nowEpochMs + 3_600_000L, value = 100.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
            ),
        )

        // 2 points: the synthetic anchor at progress=0 (value = last BG reading), then the real prediction.
        assertThat(result.predictions).hasSize(2)
        assertThat(result.predictions[0].progress).isEqualTo(0f)
        assertThat(result.predictions[0].value).isEqualTo(145f)
        assertThat(result.predictions[1].progress).isGreaterThan(0f)
    }

    @Test
    fun `a real prediction landing exactly at progress 0 is replaced by the anchor, not duplicated`() {
        val nowEpochMs = 100_000_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(nowEpochMs, 145.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = listOf(
                // Lands exactly at progress=0 (timestamp == nowEpochMs) with a value that DIFFERS from the
                // last BG reading — without the fix this would render as a second, conflicting progress=0
                // point for the same type, moving the discontinuity instead of removing it.
                BgDataPoint(timestamp = nowEpochMs, value = 130.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
                BgDataPoint(timestamp = nowEpochMs + 3_600_000L, value = 100.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
            ),
        )

        val atProgressZero = result.predictions.filter { it.progress == 0f }
        assertThat(atProgressZero).hasSize(1)
        assertThat(atProgressZero.single().value).isEqualTo(145f)
        assertThat(result.predictions).hasSize(2)
    }

    @Test
    fun `a fresh but slightly stale last reading gets a connector point closing the gap to now`() {
        val nowEpochMs = 100_000_000L
        // 4 minutes old — within the 15-minute freshness window, but not exactly "now", so its own
        // progress() is less than 1f and would otherwise leave a gap before the history-prediction boundary.
        val lastReadingTimestamp = nowEpochMs - 4 * 60_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(lastReadingTimestamp, 145.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).hasSize(2)
        assertThat(result.bgReadings[0].progress).isLessThan(1f)
        assertThat(result.bgReadings[1].progress).isEqualTo(1f)
        assertThat(result.bgReadings[1].value).isEqualTo(145f)
    }

    @Test
    fun `a stale last reading gets no connector and predictions are suppressed entirely`() {
        val nowEpochMs = 100_000_000L
        // 20 minutes old — past the 15-minute freshness window (sensor dropout / stale data).
        val lastReadingTimestamp = nowEpochMs - 20 * 60_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(lastReadingTimestamp, 145.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = listOf(
                BgDataPoint(timestamp = nowEpochMs + 3_600_000L, value = 100.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
            ),
        )

        // No synthetic connector — only the one real, stale reading.
        assertThat(result.bgReadings).hasSize(1)
        // Predictions are suppressed entirely rather than rendered from an untrustworthy anchor.
        assertThat(result.predictions).isEmpty()
    }

    @Test
    fun `a reading exactly at now gets no redundant duplicate connector point`() {
        val nowEpochMs = 100_000_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(nowEpochMs, 145.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).hasSize(1)
    }

    @Test
    fun `no basal data produces empty basal series and a safe non-zero maxBasalRateUh`() {
        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = 100_000_000L,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.basalReadings).isEmpty()
        assertThat(result.profileBasalReadings).isEmpty()
        // A 0.0 maxBasal (BasalGraphData's own default) would divide-by-zero a chart's Y-axis scaling.
        assertThat(result.maxBasalRateUh).isGreaterThan(0f)
    }

    @Test
    fun `a rate change before the window is carried forward as a progress-0 point`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
            basalData = BasalGraphData(
                profileBasal = emptyList(),
                actualBasal = listOf(GraphDataPoint(timestamp = windowStart - 3_600_000L, value = 0.8)),
                maxBasal = 1.5,
            ),
        )

        assertThat(result.basalReadings).hasSize(1)
        assertThat(result.basalReadings[0].progress).isEqualTo(0f)
        assertThat(result.basalReadings[0].rateUh).isEqualTo(0.8f)
        assertThat(result.maxBasalRateUh).isEqualTo(1.5f)
    }

    @Test
    fun `a temp basal change inside the window is mapped to its own progress, after the carried-forward point`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L
        val tempStart = windowStart + 3_600_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
            basalData = BasalGraphData(
                profileBasal = listOf(GraphDataPoint(timestamp = windowStart - 3_600_000L, value = 0.8)),
                actualBasal = listOf(
                    GraphDataPoint(timestamp = windowStart - 3_600_000L, value = 0.8),
                    GraphDataPoint(timestamp = tempStart, value = 2.0),
                ),
                maxBasal = 2.0,
            ),
        )

        assertThat(result.basalReadings).hasSize(2)
        assertThat(result.basalReadings[0].progress).isEqualTo(0f)
        assertThat(result.basalReadings[0].rateUh).isEqualTo(0.8f)
        assertThat(result.basalReadings[1].rateUh).isEqualTo(2.0f)
        assertThat(result.basalReadings[1].progress).isWithin(0.001f).of(1f / 6f)

        // The unaffected profile line still only carries its own single carried-forward point.
        assertThat(result.profileBasalReadings).hasSize(1)
        assertThat(result.profileBasalReadings[0].rateUh).isEqualTo(0.8f)
    }

    @Test
    fun `a basal change exactly at the window start is not duplicated by the carry-forward point`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
            basalData = BasalGraphData(
                profileBasal = emptyList(),
                actualBasal = listOf(GraphDataPoint(timestamp = windowStart, value = 1.0)),
                maxBasal = 1.0,
            ),
        )

        assertThat(result.basalReadings).hasSize(1)
        assertThat(result.basalReadings[0].progress).isEqualTo(0f)
    }

    @Test
    fun `unrecognized BgType predictions are silently dropped, not crashed on`() {
        val nowEpochMs = 100_000_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = listOf(
                BgDataPoint(timestamp = nowEpochMs, value = 120.0, range = BgRange.IN_RANGE, type = BgType.REGULAR),
                BgDataPoint(timestamp = nowEpochMs, value = 120.0, range = BgRange.IN_RANGE, type = BgType.BUCKETED),
            ),
        )

        assertThat(result.predictions).isEmpty()
    }
}
