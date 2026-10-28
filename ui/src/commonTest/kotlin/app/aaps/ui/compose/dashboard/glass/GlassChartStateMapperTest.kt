package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.ui.compose.overview.graphs.ChartConfig
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for [buildGlassChartState], ported from `dev_OAPSAIMI`. Covers the window filter,
 * the progress mapping at the window edges, the unit handling, the prediction anchoring with its
 * 15 minute freshness gate, the basal step series and treatment merging.
 *
 * Units differ from `dev_OAPSAIMI` on purpose: on this branch the cache already holds display units,
 * so readings pass through and only the mg/dL axis constants are converted. The two unit tests check
 * exactly that, with a converter that would be obviously wrong if it were applied to a reading.
 */
class GlassChartStateMapperTest {

    private val identity: (Double) -> Double = { it }
    private val config = ChartConfig(highMark = 180.0, lowMark = 70.0)

    private fun bg(timestamp: Long, value: Double) =
        BgDataPoint(timestamp = timestamp, value = value, range = BgRange.IN_RANGE, type = BgType.BUCKETED)

    private fun prediction(timestamp: Long, value: Double, type: BgType = BgType.IOB_PREDICTION) =
        BgDataPoint(timestamp = timestamp, value = value, range = BgRange.IN_RANGE, type = type)

    private fun assertNear(expected: Float, actual: Float, tolerance: Float) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")

    private fun build(
        nowEpochMs: Long = NOW,
        rangeHours: Int = 6,
        bgReadings: List<BgDataPoint> = emptyList(),
        iobPoints: List<GraphDataPoint> = emptyList(),
        boluses: List<BolusGraphPoint> = emptyList(),
        carbs: List<CarbsGraphPoint> = emptyList(),
        mgdlToDisplayUnits: (Double) -> Double = identity,
        predictions: List<BgDataPoint> = emptyList(),
        iobPredictionPoints: List<GraphDataPoint> = emptyList(),
        basalData: BasalGraphData = BasalGraphData(emptyList(), emptyList(), 0.0),
    ) = buildGlassChartState(
        rangeHours = rangeHours,
        nowEpochMs = nowEpochMs,
        bgReadings = bgReadings,
        iobPoints = iobPoints,
        boluses = boluses,
        carbs = carbs,
        chartConfig = config,
        mgdlToDisplayUnits = mgdlToDisplayUnits,
        predictions = predictions,
        iobPredictionPoints = iobPredictionPoints,
        basalData = basalData,
    )

    @Test
    fun `reading before the window start is excluded`() {
        val result = build(bgReadings = listOf(bg(WINDOW_START - 1L, 120.0)))

        assertTrue(result.bgReadings.isEmpty())
    }

    @Test
    fun `reading at now maps to progress 1 and reading at window start maps to progress 0`() {
        val result = build(bgReadings = listOf(bg(WINDOW_START, 90.0), bg(NOW, 150.0)))

        assertEquals(2, result.bgReadings.size)
        assertEquals(0f, result.bgReadings[0].progress)
        assertEquals(1f, result.bgReadings[1].progress)
    }

    @Test
    fun `lowLine and highLine pass through chartConfig unconverted`() {
        // A conversion that would be obviously wrong if applied to lowMark/highMark.
        val result = build(mgdlToDisplayUnits = { it / 18.0 })

        assertEquals(70f, result.lowLine)
        assertEquals(180f, result.highLine)
    }

    @Test
    fun `bg reading value passes through because the cache already holds display units`() {
        // The cache gives 10.0 in mmol/L. A converter run on it a second time would give 0.55.
        val result = build(bgReadings = listOf(bg(NOW, 10.0)), mgdlToDisplayUnits = { it / 18.0 })

        assertEquals(1, result.bgReadings.size)
        assertEquals(10f, result.bgReadings[0].value)
        assertEquals(10f, result.currentBgValue)
    }

    @Test
    fun `prediction value passes through because the cache already holds display units`() {
        val result = build(
            bgReadings = listOf(bg(NOW, 8.0)),
            mgdlToDisplayUnits = { it / 18.0 },
            predictions = listOf(prediction(NOW + 3_600_000L, 6.5)),
        )

        assertEquals(2, result.predictions.size)
        assertEquals(8f, result.predictions[0].value)
        assertEquals(6.5f, result.predictions[1].value)
    }

    @Test
    fun `a bolus and a carb in the same window both appear in treatments with correct isCarb`() {
        val result = build(
            boluses = listOf(
                BolusGraphPoint(timestamp = WINDOW_START + 1_000_000L, amount = 1.5, bolusType = BolusType.NORMAL, isValid = true, label = "1.50 U"),
            ),
            carbs = listOf(
                CarbsGraphPoint(timestamp = WINDOW_START + 2_000_000L, amount = 45.0, isValid = true, label = "45 g"),
            ),
        )

        assertEquals(2, result.treatments.size)
        assertFalse(result.treatments.first { it.label == "1.50 U" }.isCarb)
        assertTrue(result.treatments.first { it.label == "45 g" }.isCarb)
        assertEquals(WINDOW_START + 2_000_000L, result.treatments.first { it.label == "45 g" }.timestamp)
    }

    @Test
    fun `empty input lists produce an empty GlassChartState without throwing`() {
        val result = build()

        assertTrue(result.bgReadings.isEmpty())
        assertTrue(result.iobReadings.isEmpty())
        assertTrue(result.treatments.isEmpty())
        assertEquals(0f, result.currentBgValue)
        assertEquals(0f, result.currentIob)
    }

    @Test
    fun `axisMinValue and axisHeadroom are converted from mg-dL to display units`() {
        val result = build(mgdlToDisplayUnits = { it / 18.0 })

        assertNear((30.0 / 18.0).toFloat(), result.axisMinValue, 0.01f)
        assertNear((40.0 / 18.0).toFloat(), result.axisHeadroom, 0.01f)
    }

    @Test
    fun `iob points outside the window are excluded like bg readings`() {
        val result = build(
            iobPoints = listOf(
                GraphDataPoint(timestamp = WINDOW_START - 1L, value = 3.0),
                GraphDataPoint(timestamp = WINDOW_START, value = 2.0),
            ),
        )

        assertEquals(1, result.iobReadings.size)
        assertEquals(2f, result.currentIob)
    }

    @Test
    fun `header iob total wins over the last plotted point`() {
        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = NOW,
            bgReadings = emptyList(),
            iobPoints = listOf(GraphDataPoint(timestamp = NOW - 60_000L, value = 2.0)),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = config,
            mgdlToDisplayUnits = identity,
            predictions = emptyList(),
            iobTotalU = 1.97,
        )

        assertEquals(1.97f, result.currentIob)
    }

    @Test
    fun `IOB prediction is anchored to its own first point and closes the gap to the history line`() {
        val result = build(
            // The last cached history point is a few minutes stale, same as a real GraphViewModel cache tick.
            iobPoints = listOf(GraphDataPoint(timestamp = NOW - 4 * 60_000L, value = 2.5)),
            iobPredictionPoints = listOf(
                GraphDataPoint(timestamp = NOW, value = 2.4),
                GraphDataPoint(timestamp = NOW + 3_600_000L, value = 1.2),
            ),
        )

        // History gets a connector at progress=1, using the prediction's own fresh anchor value (2.4), not
        // the stale cached history value (2.5).
        assertEquals(2, result.iobReadings.size)
        assertEquals(1f, result.iobReadings[1].progress)
        assertEquals(2.4f, result.iobReadings[1].iob)

        assertEquals(2, result.iobPredictions.size)
        assertEquals(0f, result.iobPredictions[0].progress)
        assertEquals(2.4f, result.iobPredictions[0].iob)
        assertTrue(result.iobPredictions[1].progress > 0f)
    }

    @Test
    fun `no IOB prediction data produces no connector and no prediction points`() {
        val result = build(iobPoints = listOf(GraphDataPoint(timestamp = NOW - 4 * 60_000L, value = 2.5)))

        assertEquals(1, result.iobReadings.size)
        assertTrue(result.iobPredictions.isEmpty())
    }

    @Test
    fun `IOB prediction outside the 2 hour horizon is excluded`() {
        val result = build(
            iobPredictionPoints = listOf(
                GraphDataPoint(timestamp = NOW, value = 2.0),
                GraphDataPoint(timestamp = NOW + 3 * 3_600_000L, value = 0.0),
            ),
        )

        assertEquals(1, result.iobPredictions.size)
        assertEquals(0f, result.iobPredictions[0].progress)
    }

    @Test
    fun `predictions outside the 2 hour horizon are excluded and progress is 0 at now and 1 at horizon end`() {
        val result = build(
            // A fresh reading is required for predictions to render at all.
            bgReadings = listOf(bg(NOW, 120.0)),
            predictions = listOf(
                prediction(NOW, 120.0),
                prediction(NOW + 3_600_000L, 100.0),
                prediction(NOW + 2 * 3_600_000L, 90.0),
                prediction(NOW + 3 * 3_600_000L, 80.0),
            ),
        )

        assertEquals(3, result.predictions.size)
        assertEquals(0f, result.predictions[0].progress)
        assertEquals(1f, result.predictions[2].progress)
        assertNear(6f / 8f, result.historyFraction, 0.001f)
    }

    @Test
    fun `prediction line is anchored to the last BG reading so it starts where the history line ends`() {
        val result = build(
            bgReadings = listOf(bg(NOW, 145.0)),
            predictions = listOf(prediction(NOW + 3_600_000L, 100.0)),
        )

        // 2 points: the synthetic anchor at progress=0 (value = last BG reading), then the real prediction.
        assertEquals(2, result.predictions.size)
        assertEquals(0f, result.predictions[0].progress)
        assertEquals(145f, result.predictions[0].value)
        assertTrue(result.predictions[1].progress > 0f)
    }

    @Test
    fun `every prediction type gets its own anchor`() {
        val result = build(
            bgReadings = listOf(bg(NOW, 145.0)),
            predictions = listOf(
                prediction(NOW + 3_600_000L, 100.0, BgType.IOB_PREDICTION),
                prediction(NOW + 3_600_000L, 160.0, BgType.COB_PREDICTION),
            ),
        )

        val anchors = result.predictions.filter { it.progress == 0f }
        assertEquals(setOf(PredictionType.IOB, PredictionType.COB), anchors.map { it.type }.toSet())
        assertTrue(anchors.all { it.value == 145f })
    }

    @Test
    fun `a real prediction landing exactly at progress 0 is replaced by the anchor and not duplicated`() {
        val result = build(
            bgReadings = listOf(bg(NOW, 145.0)),
            predictions = listOf(
                // Lands exactly at progress=0 with a value that DIFFERS from the last BG reading.
                prediction(NOW, 130.0),
                prediction(NOW + 3_600_000L, 100.0),
            ),
        )

        val atProgressZero = result.predictions.filter { it.progress == 0f }
        assertEquals(1, atProgressZero.size)
        assertEquals(145f, atProgressZero.single().value)
        assertEquals(2, result.predictions.size)
    }

    @Test
    fun `a fresh but slightly stale last reading gets a connector point closing the gap to now`() {
        // 4 minutes old: within the 15-minute freshness window, but not exactly "now".
        val result = build(bgReadings = listOf(bg(NOW - 4 * 60_000L, 145.0)))

        assertEquals(2, result.bgReadings.size)
        assertTrue(result.bgReadings[0].progress < 1f)
        assertEquals(1f, result.bgReadings[1].progress)
        assertEquals(145f, result.bgReadings[1].value)
    }

    @Test
    fun `a reading exactly 15 minutes old is still fresh`() {
        val result = build(
            bgReadings = listOf(bg(NOW - 15 * 60_000L, 145.0)),
            predictions = listOf(prediction(NOW + 3_600_000L, 100.0)),
        )

        assertEquals(2, result.bgReadings.size)
        assertEquals(2, result.predictions.size)
    }

    @Test
    fun `a stale last reading gets no connector and predictions are suppressed entirely`() {
        // 20 minutes old: past the 15-minute freshness window (sensor dropout / stale data).
        val result = build(
            bgReadings = listOf(bg(NOW - 20 * 60_000L, 145.0)),
            predictions = listOf(prediction(NOW + 3_600_000L, 100.0)),
        )

        // No synthetic connector, only the one real, stale reading.
        assertEquals(1, result.bgReadings.size)
        // Predictions are suppressed entirely rather than rendered from an untrustworthy anchor.
        assertTrue(result.predictions.isEmpty())
    }

    @Test
    fun `a reading exactly at now gets no redundant duplicate connector point`() {
        val result = build(bgReadings = listOf(bg(NOW, 145.0)))

        assertEquals(1, result.bgReadings.size)
    }

    @Test
    fun `no basal data produces empty basal series and a safe non-zero maxBasalRateUh`() {
        val result = build()

        assertTrue(result.basalReadings.isEmpty())
        assertTrue(result.profileBasalReadings.isEmpty())
        // A 0.0 maxBasal (BasalGraphData's own default) would divide by zero in the chart's Y-axis scaling.
        assertTrue(result.maxBasalRateUh > 0f)
    }

    @Test
    fun `a rate change before the window is carried forward as a progress 0 point`() {
        val result = build(
            basalData = BasalGraphData(
                profileBasal = emptyList(),
                actualBasal = listOf(GraphDataPoint(timestamp = WINDOW_START - 3_600_000L, value = 0.8)),
                maxBasal = 1.5,
            ),
        )

        assertEquals(1, result.basalReadings.size)
        assertEquals(0f, result.basalReadings[0].progress)
        assertEquals(0.8f, result.basalReadings[0].rateUh)
        assertEquals(1.5f, result.maxBasalRateUh)
    }

    @Test
    fun `a temp basal change inside the window is mapped to its own progress after the carried forward point`() {
        val tempStart = WINDOW_START + 3_600_000L

        val result = build(
            basalData = BasalGraphData(
                profileBasal = listOf(GraphDataPoint(timestamp = WINDOW_START - 3_600_000L, value = 0.8)),
                actualBasal = listOf(
                    GraphDataPoint(timestamp = WINDOW_START - 3_600_000L, value = 0.8),
                    GraphDataPoint(timestamp = tempStart, value = 2.0),
                ),
                maxBasal = 2.0,
            ),
        )

        assertEquals(2, result.basalReadings.size)
        assertEquals(0f, result.basalReadings[0].progress)
        assertEquals(0.8f, result.basalReadings[0].rateUh)
        assertEquals(2.0f, result.basalReadings[1].rateUh)
        assertNear(1f / 6f, result.basalReadings[1].progress, 0.001f)

        // The unaffected profile line still only carries its own single carried-forward point.
        assertEquals(1, result.profileBasalReadings.size)
        assertEquals(0.8f, result.profileBasalReadings[0].rateUh)
    }

    @Test
    fun `a basal change exactly at the window start is not duplicated by the carry forward point`() {
        val result = build(
            basalData = BasalGraphData(
                profileBasal = emptyList(),
                actualBasal = listOf(GraphDataPoint(timestamp = WINDOW_START, value = 1.0)),
                maxBasal = 1.0,
            ),
        )

        assertEquals(1, result.basalReadings.size)
        assertEquals(0f, result.basalReadings[0].progress)
    }

    @Test
    fun `unrecognized BgType predictions are silently dropped`() {
        val result = build(
            predictions = listOf(
                prediction(NOW, 120.0, BgType.REGULAR),
                prediction(NOW, 120.0, BgType.BUCKETED),
            ),
        )

        assertTrue(result.predictions.isEmpty())
    }

    private companion object {

        const val NOW = 100_000_000L
        const val WINDOW_START = NOW - 6 * 3_600_000L
    }
}
