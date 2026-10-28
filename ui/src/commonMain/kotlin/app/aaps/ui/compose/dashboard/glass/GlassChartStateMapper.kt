package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.ui.compose.overview.graphs.ChartConfig

/** A BG reading older than this is treated as stale for chart-boundary/prediction purposes — see
 *  [buildGlassChartState]. 15 minutes is 3x a normal ~5-minute CGM cadence, room for one missed reading
 *  without flagging normal jitter as a dropout. */
private const val FRESH_READING_WINDOW_MS = 15 * 60_000L

/**
 * Maps the raw `GraphViewModel` flow data into the [GlassChartState] drawn by [BgChartCard] and
 * [IobChartCard].
 *
 * Units: on this branch [BgDataPoint.value] is ALREADY in the user's display unit (mg/dL or mmol/L),
 * see `PrepareGraphDataRunner` and `PostCalculationRunner`, and `chartConfig.lowMark`/`highMark` are
 * display units too. So readings, predictions and the low/high lines are used as they are. Only the
 * two fixed axis constants (30 mg/dL floor, 40 mg/dL headroom) are written in mg/dL and go through
 * [mgdlToDisplayUnits]. The result is the same display-unit chart as on `dev_OAPSAIMI`, where the cache
 * held mg/dL and the mapper converted every reading itself.
 *
 * Inputs (from `GraphViewModel`): `bgReadingsFlow` -> [bgReadings], `iobGraphFlow` -> [iobPoints] and
 * [iobPredictionPoints], `treatmentGraphFlow` -> [boluses] and [carbs], `chartConfigFlow` -> [chartConfig],
 * `predictionsFlow` -> [predictions], `basalGraphFlow` -> [basalData].
 */
internal fun buildGlassChartState(
    rangeHours: Int,
    nowEpochMs: Long,
    bgReadings: List<BgDataPoint>,
    iobPoints: List<GraphDataPoint>,
    boluses: List<BolusGraphPoint>,
    carbs: List<CarbsGraphPoint>,
    chartConfig: ChartConfig,
    /** mg/dL -> display unit. Used only for the fixed axis constants, see the KDoc above. */
    mgdlToDisplayUnits: (Double) -> Double,
    predictions: List<BgDataPoint>,
    iobPredictionPoints: List<GraphDataPoint> = emptyList(),
    basalData: BasalGraphData = BasalGraphData(emptyList(), emptyList(), 0.0),
    predictionHorizonHours: Int = 2,
    /** Insulin on board as the header prints it; null falls back to the last plotted point. */
    iobTotalU: Double? = null,
): GlassChartState {
    val windowStart = nowEpochMs - rangeHours * 3_600_000L
    fun progress(timestamp: Long): Float =
        ((timestamp - windowStart).toFloat() / (nowEpochMs - windowStart).toFloat()).coerceIn(0f, 1f)

    val windowedBg = bgReadings.filter { it.timestamp in windowStart..nowEpochMs }.sortedBy { it.timestamp }
    val windowedIob = iobPoints.filter { it.timestamp in windowStart..nowEpochMs }.sortedBy { it.timestamp }
    val windowedBoluses = boluses.filter { it.timestamp in windowStart..nowEpochMs }
    val windowedCarbs = carbs.filter { it.timestamp in windowStart..nowEpochMs }

    val predictionWindowEnd = nowEpochMs + predictionHorizonHours * 3_600_000L
    val windowedPredictions = predictions
        .filter { it.timestamp in nowEpochMs..predictionWindowEnd }
        .sortedBy { it.timestamp }

    fun predictionProgress(timestamp: Long): Float =
        ((timestamp - nowEpochMs).toFloat() / (predictionWindowEnd - nowEpochMs).toFloat()).coerceIn(0f, 1f)

    fun predictionType(type: BgType): PredictionType? = when (type) {
        BgType.IOB_PREDICTION   -> PredictionType.IOB
        BgType.COB_PREDICTION   -> PredictionType.COB
        BgType.A_COB_PREDICTION -> PredictionType.A_COB
        BgType.UAM_PREDICTION   -> PredictionType.UAM
        BgType.ZT_PREDICTION    -> PredictionType.ZT
        else                    -> null
    }

    val predictionPointsRaw = windowedPredictions.mapNotNull { p ->
        val type = predictionType(p.type) ?: return@mapNotNull null
        PredictionPoint(
            progress = predictionProgress(p.timestamp),
            value = p.value.toFloat(),
            type = type,
        )
    }
    // The history/prediction boundary (historyX(1f) == predictionX(0f), see GlassChartComponents.kt) is
    // fixed at "now" (nowEpochMs). But the last real BG reading is almost always a few minutes OLDER than
    // wall-clock "now" — the CGM only reports every ~5 minutes — so its own progress() is < 1f, and it
    // renders slightly to the LEFT of the boundary. Predictions, in contrast, always start exactly AT the
    // boundary. Left alone, that mismatch draws a visible horizontal gap with nothing in it.
    //
    // Fix: when the last reading is fresh enough to trust (within FRESH_READING_WINDOW_MS), extend the
    // history line with one more point pinned exactly at the boundary (progress=1f, same Y as the last
    // reading) — a short flat connector that closes the gap. If the last reading is older than that (sensor
    // dropout / stale data), do NOT synthesize a connector, and suppress predictions entirely: a forward
    // projection computed from stale data would be actively misleading, especially in a closed loop.
    val lastReading = windowedBg.lastOrNull()
    val lastReadingAgeMs = lastReading?.let { nowEpochMs - it.timestamp }
    val lastReadingIsFresh = lastReadingAgeMs != null && lastReadingAgeMs <= FRESH_READING_WINDOW_MS
    val lastReadingY = lastReading?.value?.toFloat()

    // A real prediction point can itself land at progress=0 (its timestamp equals nowEpochMs) — drop those
    // before prepending the synthetic anchor so a type never ends up with two progress=0 points (which would
    // just move the discontinuity instead of removing it, since the two values are not guaranteed equal).
    val predictionPoints = if (lastReadingIsFresh && lastReadingY != null) {
        val anchors = predictionPointsRaw.map { it.type }.distinct().map { type ->
            PredictionPoint(progress = 0f, value = lastReadingY, type = type)
        }
        anchors + predictionPointsRaw.filter { it.progress > 0f }
    } else {
        emptyList()
    }

    val historyFraction = rangeHours.toFloat() / (rangeHours + predictionHorizonHours).toFloat()

    val lastReadingProgress = lastReading?.let { progress(it.timestamp) }
    val bgReadingPoints = windowedBg.map {
        BgReadingPoint(progress = progress(it.timestamp), value = it.value.toFloat())
    } + if (lastReadingIsFresh && lastReadingY != null && lastReadingProgress != null && lastReadingProgress < 1f) {
        // Only add the connector when there is an actual gap to close — a reading already at progress=1f
        // (its timestamp is nowEpochMs itself) would otherwise get a redundant duplicate point.
        listOf(BgReadingPoint(progress = 1f, value = lastReadingY))
    } else {
        emptyList()
    }
    // Same anchor/continuity idea as the BG chart's predictions, but simpler: IOB is always locally
    // computable from known treatments (never "unavailable" the way a CGM reading can be), so there is no
    // staleness gate here — the projection is trustworthy whenever it exists at all.
    val windowedIobPredictions = iobPredictionPoints
        .filter { it.timestamp in nowEpochMs..predictionWindowEnd }
        .sortedBy { it.timestamp }
    val iobPredictionPointsRaw = windowedIobPredictions.map {
        IobReadingPoint(progress = predictionProgress(it.timestamp), iob = it.value.toFloat())
    }
    // The first raw point represents "IOB right now," computed fresh in the same call that produced the
    // whole projection — use its value as the shared anchor for both the prediction's own progress=0 point
    // and the history line's connector to the boundary, guaranteeing the two curves meet exactly.
    val iobAnchorValue = iobPredictionPointsRaw.firstOrNull()?.iob
    val iobPredictions = if (iobAnchorValue != null) {
        listOf(IobReadingPoint(progress = 0f, iob = iobAnchorValue)) + iobPredictionPointsRaw.filter { it.progress > 0f }
    } else {
        emptyList()
    }

    val lastIobProgress = windowedIob.lastOrNull()?.let { progress(it.timestamp) }
    val iobReadingPoints = windowedIob.map {
        IobReadingPoint(progress = progress(it.timestamp), iob = it.value.toFloat())
    } + if (iobAnchorValue != null && lastIobProgress != null && lastIobProgress < 1f) {
        listOf(IobReadingPoint(progress = 1f, iob = iobAnchorValue))
    } else {
        emptyList()
    }
    val treatmentPoints = buildList {
        windowedBoluses.forEach { b ->
            add(TreatmentPoint(progress = progress(b.timestamp), isCarb = false, label = b.label, timestamp = b.timestamp))
        }
        windowedCarbs.forEach { c ->
            add(TreatmentPoint(progress = progress(c.timestamp), isCarb = true, label = c.label, timestamp = c.timestamp))
        }
    }

    // BasalGraphData only carries a point where the rate CHANGES (a step series) — see its KDoc. Carry the
    // last known value from before the window forward as a progress=0 point, so a window that starts mid-step
    // (no change since before windowStart) still renders a value instead of starting empty.
    fun stepSeries(points: List<GraphDataPoint>): List<BasalReadingPoint> {
        val carriedForward = points.lastOrNull { it.timestamp <= windowStart }
        // Strictly after windowStart: a point AT windowStart is already covered by carriedForward above,
        // and including it again here would render a redundant duplicate progress=0 point.
        val withinWindow = points.filter { it.timestamp > windowStart && it.timestamp <= nowEpochMs }
        return buildList {
            carriedForward?.let { add(BasalReadingPoint(progress = 0f, rateUh = it.value.toFloat())) }
            withinWindow.forEach { add(BasalReadingPoint(progress = progress(it.timestamp), rateUh = it.value.toFloat())) }
        }
    }

    return GlassChartState(
        bgReadings = bgReadingPoints,
        iobReadings = iobReadingPoints,
        treatments = treatmentPoints,
        predictions = predictionPoints,
        iobPredictions = iobPredictions,
        basalReadings = stepSeries(basalData.actualBasal),
        profileBasalReadings = stepSeries(basalData.profileBasal),
        maxBasalRateUh = basalData.maxBasal.toFloat().coerceAtLeast(0.1f),
        historyFraction = historyFraction,
        currentBgValue = bgReadingPoints.lastOrNull()?.value ?: 0f,
        // The headline number is the one the header shows, not the last point of the five-minute
        // grid this curve is drawn on: the two are computed at different moments, and insulin on
        // board decays between them, so they used to disagree by a few hundredths of a unit in front
        // of the user. The curve keeps its own points; only the figure is shared.
        currentIob = iobTotalU?.toFloat() ?: iobReadingPoints.lastOrNull()?.iob ?: 0f,
        lowLine = chartConfig.lowMark.toFloat(),
        highLine = chartConfig.highMark.toFloat(),
        axisMinValue = mgdlToDisplayUnits(30.0).toFloat(),
        axisHeadroom = mgdlToDisplayUnits(40.0).toFloat() - mgdlToDisplayUnits(0.0).toFloat(),
        rangeHours = rangeHours,
    )
}
