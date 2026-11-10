package app.aaps.ui.compose.overview.graphs

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.overview.graph.ChartSmbMarker
import app.aaps.core.interfaces.overview.graph.ChartTbrSegment
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineSeries
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent

/** SMB markers sit above the enlarged TBR lane (activity uses the middle band). */
private const val STRIP_SMB_Y_FRACTION = 0.84

/**
 * Activity + dashboard SMB + TBR under the main BG chart: native activity Y scale, therapy cues in
 * readable space (same X / scroll / zoom as [BgGraphCompose]).
 */
@Composable
fun DashboardActivityStripChart(
    viewModel: GraphViewModel,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    nowTimestamp: Long,
    dashboardSmbMarkers: List<ChartSmbMarker>,
    dashboardTbrSegments: List<ChartTbrSegment>,
    dashboardTbrMarkerEpochMs: List<Long>,
    modifier: Modifier = Modifier,
) {
    val activityData by viewModel.activityGraphFlow.collectAsStateWithLifecycle()
    val (minTimestamp, maxTimestamp) = derivedTimeRange ?: run {
        val now = System.currentTimeMillis()
        now - 24 * 60 * 60 * 1000L to now
    }
    val maxX = remember(minTimestamp, maxTimestamp) {
        timestampToX(maxTimestamp, minTimestamp)
    }
    val stableTimeRange = remember(minTimestamp / 60000, maxTimestamp / 60000) {
        minTimestamp to maxTimestamp
    }
    val modelProducer = remember { CartesianChartModelProducer() }

    val yMax = remember(activityData.maxActivity) {
        val m = activityData.maxActivity
        if (m <= 0.0) 1.0 else m * 1.18
    }
    val smbLineY = remember(yMax) { yMax * STRIP_SMB_Y_FRACTION }

    LaunchedEffect(activityData, stableTimeRange, maxX, dashboardSmbMarkers, smbLineY) {
        modelProducer.runTransaction {
            lineSeries {
                val maxAct = activityData.maxActivity
                if (maxAct <= 0.0 || activityData.activity.size < 2) {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                } else {
                    val hist = activityData.activity
                        .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                        .sortedBy { it.first }
                    series(x = hist.map { it.first }, y = hist.map { it.second })
                    if (activityData.activityPrediction.size >= 2) {
                        val pred = activityData.activityPrediction
                            .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                            .sortedBy { it.first }
                        series(x = pred.map { it.first }, y = pred.map { it.second })
                    } else {
                        series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    }
                }
                if (dashboardSmbMarkers.isEmpty()) {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                } else {
                    val smbPts = dashboardSmbMarkers
                        .map { timestampToX(it.timestampEpochMs, minTimestamp) to smbLineY }
                        .sortedBy { it.first }
                    series(x = smbPts.map { it.first }, y = smbPts.map { it.second })
                }
                series(x = normalizerX(maxX), y = NORMALIZER_Y)
            }
        }
    }

    val rangeProvider = remember(maxX, yMax) {
        CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = 0.0, maxY = yMax)
    }

    val scheme = MaterialTheme.colorScheme
    val actAccent = scheme.secondary.copy(alpha = 0.62f)
    val normalizerLine = remember { createNormalizerLine() }

    val histLine = remember(actAccent) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(actAccent.copy(alpha = 0.42f))),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp, cap = StrokeCap.Round),
            areaFill = null,
        )
    }
    val predLine = remember(actAccent) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(actAccent.copy(alpha = 0.22f))),
            stroke = LineCartesianLayer.LineStroke.Dashed(
                thickness = 1.dp,
                cap = StrokeCap.Round,
                dashLength = 3.dp,
                gapLength = 3.dp,
            ),
            areaFill = null,
        )
    }

    val smbFill = scheme.secondaryContainer.copy(alpha = 0.58f)
    val smbStroke = scheme.outline.copy(alpha = 0.52f)
    val smbLine = remember(smbFill, smbStroke) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(
                    component = ShapeComponent(
                        fill = Fill(smbFill),
                        shape = TriangleShape,
                        strokeFill = Fill(smbStroke),
                        strokeThickness = 1.25.dp,
                    ),
                    size = 13.5.dp,
                ),
            ),
        )
    }

    val lines = remember(histLine, predLine, smbLine, normalizerLine) {
        listOf(histLine, predLine, smbLine, normalizerLine)
    }

    val bottomAxisItemPlacer = rememberBottomAxisItemPlacer(minTimestamp)
    val bottomAxisValueFormatter = rememberTimeFormatter(minTimestamp)
    val invisibleLabel = rememberTextComponent(
        style = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        minWidth = TextComponent.MinWidth.fixed(0.dp),
    )
    val ghostGuideline = LineComponent(fill = Fill(Color.Transparent))

    val layer = rememberLineCartesianLayer(
        lineProvider = LineCartesianLayer.LineProvider.series(lines),
        rangeProvider = rangeProvider,
        verticalAxisPosition = Axis.Position.Vertical.Start,
    )

    val nowLineColor = scheme.onSurface.copy(alpha = 0.36f)
    val nowLine = rememberNowLine(minTimestamp, nowTimestamp, nowLineColor)
    val tbrLaneWash = scheme.surfaceContainerHighest
    val tbrBarHalo = scheme.outlineVariant
    val tbrBarCore = scheme.secondary
    val tbrMarkerLine = scheme.outline
    val tbrDecoration = rememberDashboardTbrLaneDecoration(
        minTimestamp = minTimestamp,
        segments = dashboardTbrSegments,
        markerEpochMs = dashboardTbrMarkerEpochMs,
        bgAxisYMin = null,
        bgAxisYMax = null,
        legacyBottomReservePx = 8f,
        laneBackground = tbrLaneWash,
        barFillSoft = tbrBarHalo,
        barFillStrong = tbrBarCore,
        markerLineColor = tbrMarkerLine,
        softStyle = true,
        therapyStrip = true,
    )
    val decorations = remember(tbrDecoration, nowLine) {
        buildList {
            tbrDecoration?.let { add(it) }
            add(nowLine)
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            layer,
            startAxis = VerticalAxis.rememberStart(
                itemPlacer = VerticalAxis.ItemPlacer.step({ 1.0 }),
                label = invisibleLabel,
                guideline = ghostGuideline,
                line = ghostGuideline,
            ),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = bottomAxisValueFormatter,
                itemPlacer = bottomAxisItemPlacer,
                label = invisibleLabel,
                guideline = LineComponent(fill = Fill(scheme.outlineVariant.copy(alpha = 0.14f))),
            ),
            decorations = decorations,
            getXStep = { 1.0 },
        ),
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = scrollState,
        zoomState = zoomState,
    )
}
