package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.configuration.Constants
import app.aaps.core.graph.vico.Square
import app.aaps.core.interfaces.overview.graph.ActivityGraphData
import app.aaps.core.interfaces.overview.graph.BasalGraphData
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.ChartSmbMarker
import app.aaps.core.interfaces.overview.graph.ChartTbrSegment
import app.aaps.core.interfaces.overview.graph.EpsGraphPoint
import app.aaps.core.interfaces.overview.graph.SeriesType
import app.aaps.core.interfaces.overview.graph.TargetLineData
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.icons.IcProfile
import app.aaps.ui.R
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.VicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.VicoZoomState
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.HorizontalBox
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarkerController
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.Interaction
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Insets
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Series identifiers */
/** Basal on BG graph — deprecated, now shown as flipped overlay on IOB graph. Set to true to restore. */
@Deprecated("Basal moved to IOB graph as flipped overlay")
private const val showBasalOnBgGraph = false

private const val SERIES_REGULAR = "regular"
private const val SERIES_BUCKETED = "bucketed"
private const val SERIES_PRED_IOB = "pred_iob"
private const val SERIES_PRED_COB = "pred_cob"
private const val SERIES_PRED_ACOB = "pred_acob"
private const val SERIES_PRED_UAM = "pred_uam"
private const val SERIES_PRED_ZT = "pred_zt"
private const val SERIES_DASHBOARD_SMB = "dashboard_smb"

/** All prediction series identifiers */
private val PREDICTION_SERIES = listOf(SERIES_PRED_IOB, SERIES_PRED_COB, SERIES_PRED_ACOB, SERIES_PRED_UAM, SERIES_PRED_ZT)

/**
 * BG Graph using Vico — dual-layer chart.
 *
 * Layer 0 (start axis): BG readings — regular (outlined circles) + bucketed (filled, range-colored)
 * Layer 1 (end axis, hidden): Basal — profile (dashed step) + actual delivered (solid step + area fill)
 *
 * **BG start-axis Y** follows legacy [app.aaps.core.graph.data.GlucoseValueDataPoint] / GraphView:
 * display units from General → Units (mg/dL or mmol/L). Cache [BgDataPoint.value] stays mg/dL; conversion
 * happens when building Vico series so ticks and data share one coordinate space.
 *
 * Basal Y-axis is scaled so maxBasal occupies [BASAL_HEIGHT_FRACTION] of the chart height (maxY = maxBasal / BASAL_HEIGHT_FRACTION).
 *
 * Scroll/Zoom:
 * - Accepts external scroll/zoom states for synchronization with secondary graphs
 * - This is the primary interactive graph - user controls scroll/zoom here
 */
@Composable
fun BgGraphCompose(
    viewModel: GraphViewModel,
    bgOverlays: List<SeriesType>,
    scrollState: VicoScrollState,
    zoomState: VicoZoomState,
    derivedTimeRange: Pair<Long, Long>?,
    nowTimestamp: Long,
    /** When true, BG dots + prediction strokes use [MaterialTheme] (dashboard parity with Canvas). */
    useMaterial3DashboardStyle: Boolean = false,
    /** Dashboard-only SMB markers as a Vico line series (triangles), empty on overview. */
    dashboardSmbMarkers: List<ChartSmbMarker> = emptyList(),
    dashboardTbrSegments: List<ChartTbrSegment> = emptyList(),
    dashboardTbrMarkerEpochMs: List<Long> = emptyList(),
    /**
     * When true, the BG start axis uses a fixed **0 … max** vertical range so decorations (TBR) align with the
     * glycémie = 0 grid line (dashboard). Overview keeps the default auto Y range when false.
     */
    lockStartAxisYFromZero: Boolean = false,
    /**
     * Fallback TBR vertical layout when [lockStartAxisYFromZero] is false: distance from layer bottom (pixels).
     */
    dashboardTbrLegacyBottomReservePx: Float = 0f,
    /**
     * Dashboard: SMB/TBR, « now » line and BG point outlines use a **muted** palette (no alarm red for SMB)
     * and lighter weights to keep therapy cues readable without a punitive feel.
     */
    dashboardSoftTherapyVisuals: Boolean = false,
    /**
     * Dashboard AIMI scenario projection: show only floor (IOB) + best (UAM) with high-contrast styling,
     * risk envelope band, and terminal marker. PKPD reference curves (COB/ZT/aCOB) are hidden.
     */
    dashboardScenarioProjectionVisuals: Boolean = false,
    /**
     * When true with [SeriesType.ACTIVITY] in [bgOverlays], activity is drawn in [DashboardActivityStripChart]
     * below this chart (own Y scale) instead of being scaled into mg/dL space.
     */
    dashboardSplitActivityToStrip: Boolean = false,
    /**
     * Dashboard-only: called when the user taps an SMB triangle on this chart.
     * Uses Vico marker hit-testing (scroll/zoom aware). Overview leaves this null.
     */
    onDashboardSmbMarkerTap: ((ChartSmbMarker) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Collect flows independently - each triggers recomposition only when it changes
    val bgReadings by viewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val bucketedData by viewModel.bucketedDataFlow.collectAsStateWithLifecycle()
    val showPredictions = SeriesType.PREDICTIONS in bgOverlays
    val rawPredictions by viewModel.predictionsFlow.collectAsStateWithLifecycle()
    val predictions = if (showPredictions) rawPredictions else emptyList()
    val rawBasalData by viewModel.basalGraphFlow.collectAsStateWithLifecycle()
    // Basal on BG graph is deprecated — now shown as flipped overlay on IOB graph instead.
    // Keep the layer structure intact (dummy data) to avoid chart restructuring.
    @Suppress("DEPRECATION")
    val basalData = if (showBasalOnBgGraph) rawBasalData else BasalGraphData(emptyList(), emptyList(), 0.0)
    val epsPoints by viewModel.epsGraphFlow.collectAsStateWithLifecycle()
    val showActivityOnMainChart = SeriesType.ACTIVITY in bgOverlays && !dashboardSplitActivityToStrip
    val activityData by viewModel.activityGraphFlow.collectAsStateWithLifecycle()
    val chartConfig by viewModel.chartConfigFlow.collectAsStateWithLifecycle()
    val generalUnits by viewModel.generalUnits.collectAsStateWithLifecycle()
    val vicoChartLook by viewModel.vicoChartLookFlow.collectAsStateWithLifecycle()

    val targetData by viewModel.targetLineFlow.collectAsStateWithLifecycle()

    // Y-axis matches legacy GraphView: display units (mg/dL or mmol/L per General → Units).
    // [BgDataPoint.value] / target line / DB remain mg/dL; convert at Vico series build.
    val startAxisMaxY = remember(bgReadings, bucketedData, predictions, chartConfig, targetData, generalUnits) {
        val bgDisplay = (bgReadings + bucketedData).map { viewModel.glucoseMgdlToChartY(it.value) }
        val fromBg = if (bgDisplay.isNotEmpty()) bgDisplay.max() else chartConfig.highMark
        val predMax = predictions.maxOfOrNull { viewModel.glucoseMgdlToChartY(it.value) }
        val targetMax =
            targetData.targets.maxOfOrNull { viewModel.glucoseMgdlToChartY(it.value) }
                ?: Double.NEGATIVE_INFINITY
        maxOf(fromBg, predMax ?: fromBg, chartConfig.highMark, targetMax)
    }

    // Use derived time range or fall back to default (last GRAPH_TIME_RANGE_HOURS hours)
    val (minTimestamp, maxTimestamp) = derivedTimeRange ?: run {
        val now = System.currentTimeMillis()
        val dayAgo = now - Constants.GRAPH_TIME_RANGE_HOURS * 60 * 60 * 1000L
        dayAgo to now
    }

    // Single model producer shared by all layers
    val modelProducer = remember { CartesianChartModelProducer() }

    // Series registry - tracks current data for each series
    val seriesRegistry = remember { mutableStateMapOf<String, List<BgDataPoint>>() }

    // Colors from theme + optional Vico tint preference (discrete BG dots only)
    val scheme = MaterialTheme.colorScheme
    val regularColorBase = if (useMaterial3DashboardStyle) {
        scheme.primary
    } else {
        AapsTheme.generalColors.originalBgValue
    }
    val regularColor = remember(vicoChartLook.bgReadingTintKey, regularColorBase, scheme) {
        bgReadingTintColor(vicoChartLook.bgReadingTintKey, regularColorBase, scheme)
    }
    val lowColor = AapsTheme.generalColors.bgLow
    val inRangeColor = AapsTheme.generalColors.bgInRange
    val highColor = AapsTheme.generalColors.bgHigh
    val basalColor = AapsTheme.elementColors.tempBasal
    val targetLineColor = AapsTheme.elementColors.tempTarget
    val activityColor = AapsTheme.elementColors.activity

    // Predictions: keep AAPS palette (overview parity). Dashboard only overrides BG dot stroke above.
    val iobPredColor = AapsTheme.generalColors.iobPrediction
    val cobPredColor = AapsTheme.generalColors.cobPrediction
    val aCobPredColor = AapsTheme.generalColors.aCobPrediction
    val uamPredColor = AapsTheme.generalColors.uamPrediction
    val ztPredColor = AapsTheme.generalColors.ztPrediction

    // Calculate x-axis range (must match COB graph for alignment)
    val maxX = remember(minTimestamp, maxTimestamp) {
        timestampToX(maxTimestamp, minTimestamp)
    }

    // Track which series are currently included (for matching LineProvider)
    val activeSeriesState = remember { mutableStateOf(listOf<String>()) }

    // Stable time range - only changes when timestamps change by more than 1 minute
    val stableTimeRange = remember(minTimestamp / 60000, maxTimestamp / 60000) {
        minTimestamp to maxTimestamp
    }

    // Function to rebuild chart from registry
    suspend fun rebuildChart(
        currentBasalData: BasalGraphData,
        currentTargetData: TargetLineData,
        currentEpsPoints: List<EpsGraphPoint>,
        currentActivityData: ActivityGraphData,
        currentMaxBgY: Double,
    ) {
        val regularPoints = seriesRegistry[SERIES_REGULAR] ?: emptyList()
        val bucketedPoints = seriesRegistry[SERIES_BUCKETED] ?: emptyList()
        val smbPoints = seriesRegistry[SERIES_DASHBOARD_SMB] ?: emptyList()

        // Note: do NOT early-return when there are no BG points. With a clean DB the chart must
        // still build its frame (axes, now-line, in-range belt) via the normalizer + dummy layers,
        // matching the secondary graph. The per-layer logic below already handles empty series.

        modelProducer.runTransaction {
            // Block 1 → BG layer (layer 0, start axis)
            lineModel {
                val activeSeries = mutableListOf<String>()

                if (regularPoints.isNotEmpty()) {
                    val dataPoints = regularPoints
                        .map { timestampToX(it.timestamp, minTimestamp) to viewModel.glucoseMgdlToChartY(it.value) }
                        .sortedBy { it.first }
                    series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                    activeSeries.add(SERIES_REGULAR)
                }

                if (bucketedPoints.isNotEmpty()) {
                    val dataPoints = bucketedPoints
                        .map { timestampToX(it.timestamp, minTimestamp) to viewModel.glucoseMgdlToChartY(it.value) }
                        .sortedBy { it.first }
                    series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                    activeSeries.add(SERIES_BUCKETED)
                }

                if (smbPoints.isNotEmpty()) {
                    val dataPoints = smbPoints
                        .map { timestampToX(it.timestamp, minTimestamp) to viewModel.glucoseMgdlToChartY(it.value) }
                        .sortedBy { it.first }
                    series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                    activeSeries.add(SERIES_DASHBOARD_SMB)
                }

                // Prediction series - each type as a separate line
                for (predSeries in PREDICTION_SERIES) {
                    val predPoints = seriesRegistry[predSeries]
                    if (predPoints != null && predPoints.isNotEmpty()) {
                        val dataPoints = predPoints
                            .map { timestampToX(it.timestamp, minTimestamp) to viewModel.glucoseMgdlToChartY(it.value) }
                            .sortedBy { it.first }
                        series(x = dataPoints.map { it.first }, y = dataPoints.map { it.second })
                        activeSeries.add(predSeries)
                    }
                }

                // Normalizer series
                series(x = normalizerX(maxX), y = NORMALIZER_Y)

                activeSeriesState.value = activeSeries.toList()
            }

            // Block 2 → Basal layer (layer 1, end axis)
            lineModel {
                if (currentBasalData.profileBasal.size >= 2) {
                    val pts = currentBasalData.profileBasal
                        .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                        .sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })
                } else {
                    // Dummy series - invisible at y=0
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }

                if (currentBasalData.actualBasal.size >= 2) {
                    val pts = currentBasalData.actualBasal
                        .map { timestampToX(it.timestamp, minTimestamp) to it.value }
                        .sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })
                } else {
                    // Dummy series - invisible at y=0
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }

            // Block 3 → Target line layer (layer 2, start axis)
            lineModel {
                if (currentTargetData.targets.size >= 2) {
                    val pts = currentTargetData.targets
                        .map { timestampToX(it.timestamp, minTimestamp) to viewModel.glucoseMgdlToChartY(it.value) }
                        .sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })
                } else {
                    // Dummy series - invisible at y=0
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }

            // Block 4 → EPS layer (layer 3, end axis — Y-values normalized to basal coordinate space)
            // EPS shares End axis with basal, so Y-values must fit within basalMaxY range.
            // Place icons at 75% of chart height for 100% profile, scaled proportionally.
            lineModel {
                if (currentEpsPoints.isNotEmpty()) {
                    val epsBaseline = currentBasalData.maxBasal * 4.0 * 0.75 // 75% of basalMaxY
                    val pts = currentEpsPoints
                        .map { timestampToX(it.timestamp, minTimestamp) to (it.originalPercentage / 100.0 * epsBaseline) }
                        .sortedBy { it.first }
                    series(x = pts.map { it.first }, y = pts.map { it.second })
                } else {
                    // Dummy series - invisible at y=0
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }

            // Block 5 → Activity layer (layer 4, start axis — Y-values normalized to BG coordinate space)
            // Scale so maxActivity maps to 80% of maxBgY (same as legacy: maxY * 0.8 / maxIAValue)
            lineModel {
                val maxAct = currentActivityData.maxActivity
                if (!showActivityOnMainChart || maxAct <= 0.0 || currentActivityData.activity.size < 2) {
                    // Activity disabled or no data — emit dummy series (history + prediction)
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                    return@lineModel
                }
                val scaleFactor = currentMaxBgY * 0.8 / maxAct

                val pts = currentActivityData.activity
                    .map { timestampToX(it.timestamp, minTimestamp) to (it.value * scaleFactor) }
                    .sortedBy { it.first }
                series(x = pts.map { it.first }, y = pts.map { it.second })

                if (currentActivityData.activityPrediction.size >= 2) {
                    val predPts = currentActivityData.activityPrediction
                        .map { timestampToX(it.timestamp, minTimestamp) to (it.value * scaleFactor) }
                        .sortedBy { it.first }
                    series(x = predPts.map { it.first }, y = predPts.map { it.second })
                } else {
                    series(x = listOf(0.0, 1.0), y = listOf(0.0, 0.0))
                }
            }
        }
    }

    // Split predictions by type into registry
    val predictionsByType = remember(predictions, dashboardScenarioProjectionVisuals) {
        if (dashboardScenarioProjectionVisuals) {
            mapOf(
                SERIES_PRED_IOB to predictions.filter { it.type == BgType.IOB_PREDICTION },
                SERIES_PRED_COB to emptyList(),
                SERIES_PRED_ACOB to emptyList(),
                SERIES_PRED_UAM to predictions.filter { it.type == BgType.UAM_PREDICTION },
                SERIES_PRED_ZT to emptyList(),
            )
        } else {
            mapOf(
                SERIES_PRED_IOB to predictions.filter { it.type == BgType.IOB_PREDICTION },
                SERIES_PRED_COB to predictions.filter { it.type == BgType.COB_PREDICTION },
                SERIES_PRED_ACOB to predictions.filter { it.type == BgType.A_COB_PREDICTION },
                SERIES_PRED_UAM to predictions.filter { it.type == BgType.UAM_PREDICTION },
                SERIES_PRED_ZT to predictions.filter { it.type == BgType.ZT_PREDICTION },
            )
        }
    }

    // Single LaunchedEffect for all data - ensures atomic updates
    LaunchedEffect(
        bgReadings,
        bucketedData,
        predictionsByType,
        basalData,
        targetData,
        epsPoints,
        activityData,
        showActivityOnMainChart,
        chartConfig,
        generalUnits,
        stableTimeRange,
        dashboardSmbMarkers,
        dashboardSplitActivityToStrip,
        dashboardScenarioProjectionVisuals,
    ) {
        seriesRegistry[SERIES_REGULAR] = bgReadings
        seriesRegistry[SERIES_BUCKETED] = bucketedData
        for ((key, points) in predictionsByType) {
            seriesRegistry[key] = points
        }
        val sortedBgAsc = bgReadings.sortedBy { it.timestamp }
        val fallbackSmbY = viewModel.glucoseDisplayYToMgdl(
            (chartConfig.lowMark + chartConfig.highMark) / 2.0,
        )
        seriesRegistry[SERIES_DASHBOARD_SMB] =
            if (dashboardSplitActivityToStrip) {
                emptyList()
            } else {
                dashboardSmbMarkers.map { m ->
                    val y = interpolateBgForDashboardMarker(
                        epochMs = m.timestampEpochMs,
                        sortedAsc = sortedBgAsc,
                        fallbackY = fallbackSmbY,
                    )
                    BgDataPoint(
                        timestamp = m.timestampEpochMs,
                        value = y,
                        range = BgRange.IN_RANGE,
                        type = BgType.REGULAR,
                    )
                }
            }
        // Activity layer shares BG axis — scale in display units (legacy GraphData maxY).
        val bgDisplay = (bgReadings + bucketedData).map { viewModel.glucoseMgdlToChartY(it.value) }
        val targetPeakDisplay =
            targetData.targets.maxOfOrNull { viewModel.glucoseMgdlToChartY(it.value) }
                ?: Double.NEGATIVE_INFINITY
        val maxBgY = if (bgDisplay.isNotEmpty()) {
            maxOf(bgDisplay.max(), chartConfig.highMark, targetPeakDisplay)
        } else {
            maxOf(chartConfig.highMark, targetPeakDisplay)
        }
        rebuildChart(basalData, targetData, epsPoints, activityData, maxBgY)
    }

    // Build lookup map for BUCKETED points: x-value -> BgDataPoint (for PointProvider)
    val bucketedLookup = remember(bucketedData, minTimestamp) {
        bucketedData.associateBy { timestampToX(it.timestamp, minTimestamp) }
    }

    // Time formatter and axis configuration
    val timeFormatter = rememberTimeFormatter(minTimestamp)
    val bottomAxisItemPlacer = rememberBottomAxisItemPlacer(minTimestamp)
    val bgStartAxisValueFormatter = remember {
        CartesianValueFormatter { _, y, _ -> viewModel.formatBgChartAxisTick(y) }
    }

    // =========================================================================
    // BG layer lines (layer 0)
    // =========================================================================

    val regularColorChart = if (dashboardSoftTherapyVisuals) regularColor.copy(alpha = 0.88f) else regularColor
    val regularOutlineAlpha = if (dashboardSoftTherapyVisuals) 0.22f else 0.3f
    val customTintDots = vicoChartLook.bgReadingTintKey != VicoChartAppearance.TINT_THEME
    // Bucketed series is drawn on top of regular; keep in-range fill aligned with reading tint pref.
    val bucketedInRangeColor = if (customTintDots) regularColorChart else inRangeColor
    val bucketedPointProvider = remember(bucketedLookup, lowColor, bucketedInRangeColor, highColor) {
        BucketedPointProvider(bucketedLookup, lowColor, bucketedInRangeColor, highColor)
    }
    val regularDotFillAlpha = if (customTintDots) {
        if (dashboardSoftTherapyVisuals) 0.38f else 0.52f
    } else {
        0f
    }
    val regularLine = remember(regularColorChart, regularOutlineAlpha, regularDotFillAlpha) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(
                    component = ShapeComponent(
                        fill = Fill(
                            if (regularDotFillAlpha > 0f) {
                                regularColorChart.copy(alpha = regularDotFillAlpha)
                            } else {
                                Color.Transparent
                            },
                        ),
                        shape = CircleShape,
                        strokeFill = Fill(regularColorChart.copy(alpha = regularOutlineAlpha)),
                        strokeThickness = 1.dp
                    ),
                    size = 6.dp
                )
            )
        )
    }

    val bucketedLine = remember(bucketedPointProvider) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = bucketedPointProvider
        )
    }

    val smbFillResolved = if (dashboardSoftTherapyVisuals) {
        scheme.secondaryContainer.copy(alpha = 0.52f)
    } else {
        scheme.error.copy(alpha = 0.42f)
    }
    val smbStrokeResolved = if (dashboardSoftTherapyVisuals) {
        scheme.outline.copy(alpha = 0.48f)
    } else {
        scheme.error.copy(alpha = 0.88f)
    }
    val smbPointSize = if (dashboardSoftTherapyVisuals) 9.dp else 11.dp
    val smbStrokeThickness = if (dashboardSoftTherapyVisuals) 1.dp else 2.dp
    val smbDashboardLine = remember(smbFillResolved, smbStrokeResolved, smbPointSize, smbStrokeThickness) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(
                    component = ShapeComponent(
                        fill = Fill(smbFillResolved),
                        shape = TriangleShape,
                        strokeFill = Fill(smbStrokeResolved),
                        strokeThickness = smbStrokeThickness
                    ),
                    size = smbPointSize
                )
            )
        )
    }

    val normalizerLine = remember { createNormalizerLine() }

    val surfaceForBlend = scheme.surface
    val useScenarioProjectionLines = dashboardScenarioProjectionVisuals && showPredictions
    val scenarioPointHalo = scheme.onSurface.copy(alpha = 0.94f)
    val scenarioFloorColor = iobPredColor
    val scenarioBestColor = remember(uamPredColor, scheme.tertiary) {
        // UAM palette (#C9BD60) is muted on dark dashboard — lift toward tertiary for contrast.
        lerp(uamPredColor, scheme.tertiary, 0.45f)
    }
    val iobPredLine = remember(
        scenarioFloorColor,
        scenarioPointHalo,
        dashboardSoftTherapyVisuals,
        surfaceForBlend,
        useScenarioProjectionLines,
    ) {
        when {
            useScenarioProjectionLines -> createScenarioFloorLine(scenarioFloorColor, scenarioPointHalo)
            dashboardSoftTherapyVisuals -> createSoftPredictionLine(softenChartColor(iobPredColor, surfaceForBlend))
            else -> createPredictionLine(iobPredColor)
        }
    }
    val cobPredLine = remember(cobPredColor, dashboardSoftTherapyVisuals, surfaceForBlend) {
        val c = if (dashboardSoftTherapyVisuals) softenChartColor(cobPredColor, surfaceForBlend) else cobPredColor
        if (dashboardSoftTherapyVisuals) createSoftPredictionLine(c) else createPredictionLine(c)
    }
    val aCobPredLine = remember(aCobPredColor, dashboardSoftTherapyVisuals, surfaceForBlend) {
        val c = if (dashboardSoftTherapyVisuals) softenChartColor(aCobPredColor, surfaceForBlend) else aCobPredColor
        if (dashboardSoftTherapyVisuals) createSoftPredictionLine(c) else createPredictionLine(c)
    }
    val uamPredLine = remember(
        scenarioBestColor,
        scenarioPointHalo,
        dashboardSoftTherapyVisuals,
        surfaceForBlend,
        useScenarioProjectionLines,
    ) {
        when {
            useScenarioProjectionLines -> createScenarioBestLine(scenarioBestColor, scenarioPointHalo)
            dashboardSoftTherapyVisuals -> createSoftPredictionLine(softenChartColor(uamPredColor, surfaceForBlend))
            else -> createPredictionLine(uamPredColor)
        }
    }
    val ztPredLine = remember(ztPredColor, dashboardSoftTherapyVisuals, surfaceForBlend) {
        val c = if (dashboardSoftTherapyVisuals) softenChartColor(ztPredColor, surfaceForBlend) else ztPredColor
        if (dashboardSoftTherapyVisuals) createSoftPredictionLine(c) else createPredictionLine(c)
    }

    val activeSeries by activeSeriesState
    val bgLines = remember(activeSeries, regularLine, bucketedLine, smbDashboardLine, iobPredLine, cobPredLine, aCobPredLine, uamPredLine, ztPredLine, normalizerLine) {
        buildList {
            if (SERIES_REGULAR in activeSeries) add(regularLine)
            if (SERIES_BUCKETED in activeSeries) add(bucketedLine)
            if (SERIES_DASHBOARD_SMB in activeSeries) add(smbDashboardLine)
            if (SERIES_PRED_IOB in activeSeries) add(iobPredLine)
            if (SERIES_PRED_COB in activeSeries) add(cobPredLine)
            if (SERIES_PRED_ACOB in activeSeries) add(aCobPredLine)
            if (SERIES_PRED_UAM in activeSeries) add(uamPredLine)
            if (SERIES_PRED_ZT in activeSeries) add(ztPredLine)
            add(normalizerLine)
        }
    }

    // =========================================================================
    // Basal layer lines (layer 1) — always 2 lines: [profileLine, actualLine]
    // =========================================================================

    // Profile basal: dashed line, no fill, step connector
    val profileBasalLine = remember(basalColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(basalColor)),
            stroke = LineCartesianLayer.LineStroke.Dashed(
                thickness = 1.dp,
                cap = StrokeCap.Round,
                dashLength = 1.dp,
                gapLength = 2.dp
            ),
            areaFill = null,
            interpolator = Square
        )
    }

    // Actual delivered basal: solid line with semi-transparent area fill, step connector
    val actualBasalLine = remember(basalColor) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(basalColor)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
            areaFill = LineCartesianLayer.AreaFill.single(Fill(basalColor.copy(alpha = 0.3f))),
            interpolator = Square
        )
    }

    val basalLines = remember(profileBasalLine, actualBasalLine) {
        listOf(profileBasalLine, actualBasalLine)
    }

    // =========================================================================
    // Target line (layer 2) — single line on start (BG) axis
    // =========================================================================

    val targetLineColorUse = if (dashboardSoftTherapyVisuals) targetLineColor.copy(alpha = 0.48f) else targetLineColor
    val targetLine = remember(targetLineColorUse) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(targetLineColorUse)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.dp),
            areaFill = null,
            interpolator = Square
        )
    }

    val targetLines = remember(targetLine) { listOf(targetLine) }

    // =========================================================================
    // EPS layer lines (layer 3) — profile icon points
    // =========================================================================

    val profileSwitchColor = AapsTheme.elementColors.profileSwitch
    val profilePainter = rememberVectorPainter(IcProfile)

    val epsLine = remember(profileSwitchColor, profilePainter) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
            areaFill = null,
            pointProvider = LineCartesianLayer.PointProvider.single(
                LineCartesianLayer.Point(
                    component = PainterComponent(profilePainter, tint = profileSwitchColor),
                    size = 16.dp
                )
            )
        )
    }

    val epsLines = remember(epsLine) { listOf(epsLine) }

    // =========================================================================
    // Activity layer lines (layer 4) — solid historical + dashed prediction
    // =========================================================================

    val activityColorUse = if (dashboardSoftTherapyVisuals) activityColor.copy(alpha = 0.42f) else activityColor
    val activityHistStroke = if (dashboardSoftTherapyVisuals) 1.dp else 1.5.dp
    val activityPredStroke = if (dashboardSoftTherapyVisuals) 1.dp else 1.5.dp
    val activityHistLine = remember(activityColorUse, activityHistStroke) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(activityColorUse)),
            stroke = LineCartesianLayer.LineStroke.Continuous(thickness = activityHistStroke),
            areaFill = null
        )
    }

    val activityPredLine = remember(activityColorUse, activityPredStroke) {
        LineCartesianLayer.Line(
            fill = LineCartesianLayer.LineFill.single(Fill(activityColorUse)),
            stroke = LineCartesianLayer.LineStroke.Dashed(
                thickness = activityPredStroke,
                cap = StrokeCap.Round,
                dashLength = 4.dp,
                gapLength = 4.dp
            ),
            areaFill = null
        )
    }

    val activityLines = remember(activityHistLine, activityPredLine) {
        listOf(activityHistLine, activityPredLine)
    }

    // Basal Y-axis range: maxBasal / BASAL_HEIGHT_FRACTION so basal occupies that fraction of chart height
    // EPS layer shares End axis with basal, so both must use the same Y-range (basalMaxY)
    val basalMaxY = remember(basalData.maxBasal) {
        if (basalData.maxBasal > 0.0) basalData.maxBasal / BASAL_HEIGHT_FRACTION else 1.0
    }

    // =========================================================================
    // Decorations
    // =========================================================================

    val nowLineColor = if (dashboardSoftTherapyVisuals) {
        scheme.onSurface.copy(alpha = 0.36f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val nowLine = rememberNowLine(minTimestamp, nowTimestamp, nowLineColor)
    val tbrLaneWash = if (dashboardSoftTherapyVisuals) scheme.surfaceContainerHighest else scheme.tertiary.copy(alpha = 0.78f)
    val tbrBarHalo = if (dashboardSoftTherapyVisuals) scheme.outlineVariant else scheme.tertiary.copy(alpha = 0.78f)
    val tbrBarCore = if (dashboardSoftTherapyVisuals) scheme.secondary else scheme.tertiary.copy(alpha = 0.78f)
    val tbrMarkerLine = if (dashboardSoftTherapyVisuals) scheme.outline else scheme.tertiary.copy(alpha = 0.78f)
    val tbrDecoration = rememberDashboardTbrLaneDecoration(
        minTimestamp = minTimestamp,
        segments = if (dashboardSplitActivityToStrip) emptyList() else dashboardTbrSegments,
        markerEpochMs = if (dashboardSplitActivityToStrip) emptyList() else dashboardTbrMarkerEpochMs,
        bgAxisYMin = if (lockStartAxisYFromZero) 0.0 else null,
        bgAxisYMax = if (lockStartAxisYFromZero) startAxisMaxY else null,
        legacyBottomReservePx = dashboardTbrLegacyBottomReservePx,
        laneBackground = tbrLaneWash,
        barFillSoft = tbrBarHalo,
        barFillStrong = tbrBarCore,
        markerLineColor = tbrMarkerLine,
        softStyle = dashboardSoftTherapyVisuals,
    )
    val comfortCorridorPair =
        if (dashboardSoftTherapyVisuals && lockStartAxisYFromZero && chartConfig.lowMark < chartConfig.highMark) {
            chartConfig.lowMark to chartConfig.highMark
        } else {
            null
        }
    val comfortCorridorDecoration = rememberTargetComfortCorridorDecoration(
        corridor = comfortCorridorPair,
        bgAxisMinY = 0.0,
        bgAxisMaxY = startAxisMaxY,
        fillColor = scheme.secondaryContainer,
        fillAlpha = 0.095f,
    )
    val scenarioGeometry = remember(
        predictions,
        dashboardScenarioProjectionVisuals,
        showPredictions,
        minTimestamp,
        generalUnits,
    ) {
        if (!dashboardScenarioProjectionVisuals || !showPredictions) {
            null
        } else {
            buildScenarioProjectionChartGeometry(predictions, minTimestamp, viewModel::glucoseMgdlToChartY)
        }
    }
    val scenarioDecorations = rememberScenarioProjectionDecorations(
        geometry = scenarioGeometry,
        bgAxisMinY = 0.0,
        bgAxisMaxY = startAxisMaxY,
        envelopeFillColor = scenarioBestColor,
        terminalMarkerColor = scenarioBestColor,
        terminalGlowColor = scenarioBestColor,
    )
    val axisLabelColor = if (dashboardSoftTherapyVisuals) scheme.onSurfaceVariant.copy(alpha = 0.72f) else scheme.onSurface
    val gridGuideAlpha = if (dashboardSoftTherapyVisuals) 0.22f else 0.5f
    // Dashboard soft BG axis: ~48 mg/dL steps in chart Y space (display units — see legacy GraphData).
    val yAxisStep =
        remember(generalUnits, dashboardSoftTherapyVisuals, lockStartAxisYFromZero) {
            if (dashboardSoftTherapyVisuals && lockStartAxisYFromZero) {
                viewModel.chartBgSoftAxisStep()
            } else {
                1.0
            }
        }
    val decorations = remember(comfortCorridorDecoration, scenarioDecorations, tbrDecoration, nowLine) {
        buildList {
            comfortCorridorDecoration?.let { add(it) }
            addAll(scenarioDecorations)
            tbrDecoration?.let { add(it) }
            add(nowLine)
        }
    }

    val defaultMarkerController = CartesianMarkerController.rememberShowOnPress()
    val dashboardSmbTapController =
        remember(
            onDashboardSmbMarkerTap,
            dashboardSmbMarkers,
            minTimestamp,
            dashboardSplitActivityToStrip,
        ) {
            val cb = onDashboardSmbMarkerTap ?: return@remember null
            if (dashboardSplitActivityToStrip || dashboardSmbMarkers.isEmpty()) return@remember null
            DashboardSmbTapMarkerController(
                smbs = dashboardSmbMarkers,
                minTimestamp = minTimestamp,
                onSmbTap = cb,
            )
        }
    val sortedBgAscForMarker = remember(bgReadings) { bgReadings.sortedBy { it.timestamp } }
    val bgValueMarker = rememberBgValueMarker(
        minTimestamp = minTimestamp,
        sortedBg = sortedBgAscForMarker,
        formatChartYValue = viewModel::formatBgChartAxisTick,
        mgdlToChartY = viewModel::glucoseMgdlToChartY,
    )

    // =========================================================================
    // Range providers — hoisted out of rememberCartesianChart so keys are re-evaluated on recomposition
    // =========================================================================

    val startAxisRangeProvider = remember(maxX, lockStartAxisYFromZero, startAxisMaxY) {
        if (lockStartAxisYFromZero) {
            CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = 0.0, maxY = startAxisMaxY)
        } else {
            CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX)
        }
    }
    val endAxisRangeProvider = remember(maxX, basalMaxY) {
        CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX, minY = 0.0, maxY = basalMaxY)
    }

    // =========================================================================
    // Chart — multi layer
    // =========================================================================

    key(generalUnits) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                // Layer 0: BG (start axis, visible)
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(bgLines),
                    rangeProvider = startAxisRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.Start
                ),
                // Layer 1: Basal (end axis, hidden — no endAxis parameter)
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(basalLines),
                    rangeProvider = endAxisRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.End
                ),
                // Layer 2: Target line (start axis — shares BG Y-axis range)
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(targetLines),
                    rangeProvider = startAxisRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.Start
                ),
                // Layer 3: EPS (end axis — shares basalMaxY range, EPS Y-values normalized in rebuildChart)
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(epsLines),
                    rangeProvider = endAxisRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.End
                ),
                // Layer 4: Activity (start axis — shares BG Y-axis range, values normalized in rebuildChart)
                rememberLineCartesianLayer(
                    lineProvider = LineCartesianLayer.LineProvider.series(activityLines),
                    rangeProvider = startAxisRangeProvider,
                    verticalAxisPosition = Axis.Position.Vertical.Start
                ),
                startAxis = VerticalAxis.rememberStart(
                    itemPlacer = VerticalAxis.ItemPlacer.step({ yAxisStep }),
                    valueFormatter = bgStartAxisValueFormatter,
                    label = rememberTextComponent(
                        style = TextStyle(color = axisLabelColor),
                        minWidth = TextComponent.MinWidth.fixed(30.dp)
                    ),
                    guideline = LineComponent(fill = Fill(scheme.outlineVariant.copy(alpha = gridGuideAlpha)))
                ),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = timeFormatter,
                    itemPlacer = bottomAxisItemPlacer,
                    label = rememberTextComponent(
                        style = TextStyle(color = axisLabelColor)
                    ),
                    guideline = LineComponent(fill = Fill(scheme.outlineVariant.copy(alpha = gridGuideAlpha)))
                ),
                decorations = decorations,
                marker = bgValueMarker,
                markerController = dashboardSmbTapController ?: defaultMarkerController,
                getXStep = { _, _, _ -> 1.0 }
            ),
            modelProducer = modelProducer,
            modifier = modifier.fillMaxWidth(),
            scrollState = scrollState,
            zoomState = zoomState
        )
    }
}

private fun interpolateBgForDashboardMarker(
    epochMs: Long,
    sortedAsc: List<BgDataPoint>,
    fallbackY: Double,
): Double {
    if (sortedAsc.isEmpty()) return fallbackY
    if (sortedAsc.size == 1) return sortedAsc.first().value
    if (epochMs <= sortedAsc.first().timestamp) return sortedAsc.first().value
    if (epochMs >= sortedAsc.last().timestamp) return sortedAsc.last().value
    for (i in 0 until sortedAsc.lastIndex) {
        val a = sortedAsc[i]
        val b = sortedAsc[i + 1]
        if (epochMs < a.timestamp || epochMs > b.timestamp) continue
        val span = (b.timestamp - a.timestamp).toDouble().coerceAtLeast(1.0)
        val t = ((epochMs - a.timestamp).toDouble() / span).coerceIn(0.0, 1.0)
        return a.value + t * (b.value - a.value)
    }
    return sortedAsc.last().value
}

/**
 * Marker shown when tapping any point on the BG line: the value at that time (in the user's display
 * unit, via [formatChartYValue] — the same formatter the Y-axis ticks already use) and the time of day.
 */
@Composable
private fun rememberBgValueMarker(
    minTimestamp: Long,
    sortedBg: List<BgDataPoint>,
    formatChartYValue: (Double) -> CharSequence,
    mgdlToChartY: (Double) -> Double,
): DefaultCartesianMarker {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val outlineColor = MaterialTheme.colorScheme.outline
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val valueTimeTemplate = stringResource(R.string.graph_point_value_time)

    val labelBackground = remember(surfaceColor, outlineColor) {
        ShapeComponent(
            fill = Fill(surfaceColor),
            shape = RoundedCornerShape(4.dp),
            strokeFill = Fill(outlineColor),
            strokeThickness = 1.dp
        )
    }
    val markerLabel = rememberTextComponent(
        style = TextStyle(color = onSurfaceColor, fontSize = 15.sp),
        padding = Insets(horizontal = 10.dp, vertical = 5.dp),
        background = labelBackground
    )
    val valueFormatter = remember(minTimestamp, sortedBg, formatChartYValue, mgdlToChartY, valueTimeTemplate) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            if (sortedBg.isEmpty()) return@ValueFormatter ""
            val x = targets.firstOrNull()?.x ?: return@ValueFormatter ""
            val epochMs = minTimestamp + (x * 60000).toLong()
            val bgMgdl = interpolateBgForDashboardMarker(epochMs, sortedBg, fallbackY = sortedBg.last().value)
            val valueText = formatChartYValue(mgdlToChartY(bgMgdl))
            val timeText = timeFormat.format(Date(epochMs))
            String.format(valueTimeTemplate, valueText, timeText)
        }
    }

    return rememberDefaultCartesianMarker(
        label = markerLabel,
        valueFormatter = valueFormatter,
        labelPosition = DefaultCartesianMarker.LabelPosition.AroundPoint,
        indicator = null,
        guideline = null,
    )
}

/**
 * Matches [LineCartesianLayerMarkerTarget]s for the dashboard SMB series without duplicating
 * scroll/zoom → model-X math. Tapping an SMB point fires [onSmbTap] (a toast) and, per the Vico
 * contract for [shouldAcceptInteraction] (returning `false` only skips updating marker visibility for
 * that interaction — it does not hide an already-visible marker), leaves the value/time tooltip as it
 * was. Any other tap on the line falls through to the normal tooltip.
 */
private class DashboardSmbTapMarkerController(
    private val smbs: List<ChartSmbMarker>,
    private val minTimestamp: Long,
    private val onSmbTap: (ChartSmbMarker) -> Unit,
) : CartesianMarkerController {

    override val acceptsLongPress: Boolean get() = false

    // Vico only calls shouldShowMarker after shouldAcceptInteraction returns true for the SAME
    // interaction, and both run on the UI thread during pointer-input handling, so a plain var is
    // enough to pass "was this tap an SMB hit" across the two calls.
    private var lastTapWasSmb = false

    override fun shouldAcceptInteraction(
        interaction: Interaction,
        targets: List<CartesianMarker.Target>,
    ): Boolean {
        if (interaction !is Interaction.Tap || targets.isEmpty()) return true
        val tapX = interaction.point.x
        var bestSmb: ChartSmbMarker? = null
        var bestDist = Float.POSITIVE_INFINITY
        for (t in targets) {
            if (t !is LineCartesianLayerMarkerTarget) continue
            val smb = smbs.firstOrNull { smb ->
                abs(timestampToX(smb.timestampEpochMs, minTimestamp) - t.x) < MODEL_X_MATCH_EPS
            } ?: continue
            val d = abs(t.canvasX - tapX)
            if (d <= SMB_TAP_MAX_CANVAS_X_DIST_PX && d < bestDist) {
                bestDist = d
                bestSmb = smb
            }
        }
        lastTapWasSmb = bestSmb != null
        if (bestSmb != null) {
            onSmbTap(bestSmb)
        }
        // Always true: an SMB hit must still reach shouldShowMarker so it can actively hide any
        // stale value/time tooltip left over from an earlier tap, instead of leaving it on screen.
        return true
    }

    override fun shouldShowMarker(interaction: Interaction, targets: List<CartesianMarker.Target>): Boolean =
        !lastTapWasSmb

    private companion object {
        private const val MODEL_X_MATCH_EPS = 0.02
        private const val SMB_TAP_MAX_CANVAS_X_DIST_PX = 56f
    }
}
