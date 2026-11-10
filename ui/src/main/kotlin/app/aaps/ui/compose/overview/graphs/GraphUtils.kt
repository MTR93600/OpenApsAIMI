package app.aaps.ui.compose.overview.graphs

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.StrokeCap
import app.aaps.core.interfaces.overview.graph.SeriesType
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import app.aaps.core.interfaces.overview.graph.ChartTbrSegment
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Shared utilities for Vico graphs in AndroidAPS.
 *
 * CRITICAL: All graphs MUST use the same x-coordinate system to ensure proper alignment.
 * This uses whole minutes from minTimestamp to avoid label repetition and precision errors.
 *
 * **Graph Alignment (3 pillars):**
 * All graphs MUST have identical x-axis configuration for pixel-based scroll/zoom sync:
 * 1. `rangeProvider = CartesianLayerRangeProvider.fixed(minX = 0.0, maxX = maxX)` — same X range
 * 2. `getXStep = { _, _, _ -> 1.0 }` — same xStep (1 minute per unit)
 * 3. Normalizer line ([createNormalizerLine] + [NORMALIZER_X]/[NORMALIZER_Y] dummy series) —
 *    ensures identical maxPointSize across all charts → same xSpacing and unscalable padding
 *
 * **Scroll/Zoom Synchronization:**
 * - BG graph (primary): scrollEnabled = true, zoomEnabled = true
 * - Secondary graphs: scrollEnabled = false, zoomEnabled = false
 * - Pixel-based sync: snapshotFlow { bgScrollState.value to bgZoomState.value }
 * - See OverviewGraphsSection for full implementation
 *
 * **Point Connectors:**
 * - Adaptive step graphs (COB): Use `AdaptiveStep` - steps for steep angles (>45°), lines for gradual
 * - Fixed step graphs (IOB, AbsIOB): Use `Square` PointConnector from core.graph.vico
 * - Smooth graphs (Activity, BGI, Ratio): Use default connector (no pointConnector parameter)
 */

/**
 * Convert timestamp to x-value (whole minutes from minTimestamp).
 *
 * CRITICAL: This is the standard x-coordinate calculation for ALL graphs.
 * - Uses whole minutes (not milliseconds or fractional hours)
 * - Prevents label repetition (Vico increments by 1)
 * - Avoids precision errors with decimals
 *
 * @param timestamp The data point timestamp in milliseconds
 * @param minTimestamp The reference timestamp (start of graph time range)
 * @return X-value in whole minutes from minTimestamp
 */
fun timestampToX(timestamp: Long, minTimestamp: Long): Double =
    ((timestamp - minTimestamp) / 60000).toDouble()

/**
 * Creates a time formatter for X-axis labels showing hours (HH format).
 *
 * @param minTimestamp The reference timestamp for x-value calculation
 * @return CartesianValueFormatter that converts x-values back to time labels
 */
@Composable
fun rememberTimeFormatter(minTimestamp: Long): CartesianValueFormatter {
    return remember(minTimestamp) {
        val dateFormat = SimpleDateFormat("HH", Locale.getDefault())
        CartesianValueFormatter { _, value, _ ->
            val timestamp = minTimestamp + (value * 60000).toLong()
            dateFormat.format(Date(timestamp))
        }
    }
}

/**
 * Creates an item placer for X-axis that shows labels at whole hour intervals.
 *
 * Calculates offset from minTimestamp to align labels with whole hours (e.g., 12:00, 13:00).
 *
 * @param minTimestamp The reference timestamp for calculating hour alignment
 * @return HorizontalAxis.ItemPlacer with 60-minute spacing aligned to whole hours
 */
@OptIn(ExperimentalTime::class)
@Composable
fun rememberBottomAxisItemPlacer(minTimestamp: Long): HorizontalAxis.ItemPlacer {
    return remember(minTimestamp) {
        val instant = Instant.fromEpochMilliseconds(minTimestamp)
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val minutesIntoHour = localDateTime.minute
        val offsetToNextHour = if (minutesIntoHour == 0) 0 else 60 - minutesIntoHour

        HorizontalAxis.ItemPlacer.aligned(
            spacing = { 60 },  // 60 minutes between labels
            offset = { offsetToNextHour }
        )
    }
}

/**
 * Default zoom level for graphs - shows 6 hours of data (360 minutes).
 */
const val DEFAULT_GRAPH_ZOOM_MINUTES = 360.0

/**
 * When predictions are shown, auto-scroll places **now + this offset** at the right edge of the viewport
 * so the scenario tail (~4 h) stays readable on the dashboard / overview graph.
 * Keep in sync with [app.aaps.core.data.configuration.Constants.PREDICTION_VIEWPORT_FUTURE_BIAS_MINUTES].
 */
const val PREDICTION_VIEWPORT_FUTURE_BIAS_MINUTES = 180.0

/**
 * Maximum zoom-in level — never show fewer than this many minutes.
 * Prevents Vico's internal label/constraint math from overflowing at extreme zoom
 * (Compose `Constraints` can't represent the resulting data-label widths → crash).
 */
const val MIN_GRAPH_ZOOM_MINUTES = 30.0

/**
 * Grace period after the last user pan/zoom during which auto-scroll on new BG is suppressed,
 * so the viewport doesn't snap back while the user is examining the graph.
 */
const val INTERACTION_GRACE_MS = 60_000L

/**
 * Fraction of the graph height occupied by the basal overlay.
 *
 * The basal layer lives on its own (end) axis; the rest of the height is left for the primary data.
 * Two coupled formulas derive from this single value and MUST stay in sync — keep them expressed in
 * terms of this constant rather than hard-coded numbers:
 * - Basal axis max  = `maxBasal / BASAL_HEIGHT_FRACTION` → maxBasal plots at this fraction of the height.
 * - Primary axis max = `dataMax / (1 - BASAL_HEIGHT_FRACTION)` → primary (IOB) data fills the remaining
 *   height, reserving the basal fraction at the edge. Used by SecondaryGraphCompose (basal at top);
 *   BgGraphCompose overlays basal at the bottom and has no primary reservation.
 *
 * e.g. 0.5 → basal occupies half the height, the primary data the other half.
 */
const val BASAL_HEIGHT_FRACTION = 0.5

/**
 * Filters data points to only include those within the valid x-axis range.
 *
 * Use this when you have data that might extend beyond the visible time range
 * and you want to exclude out-of-range points from rendering.
 *
 * @param dataPoints List of (x, y) coordinate pairs
 * @param minX Minimum X value for the graph range
 * @param maxX Maximum X value for the graph range
 * @return Filtered and sorted list of (x, y) pairs within [minX, maxX]
 */
fun filterToRange(
    dataPoints: List<Pair<Double, Double>>,
    minX: Double,
    maxX: Double
): List<Pair<Double, Double>> {
    return dataPoints
        .filter { (x, _) -> x in minX..maxX }
        .sortedBy { (x, _) -> x }  // CRITICAL: Sort by x-value for Vico
}

/**
 * Target point size for layout normalization across all synchronized graphs.
 * Must be >= the largest actual point size used in any graph (currently 22dp from IOB SMB/bolus markers).
 *
 * Every graph includes an invisible normalizer line with this point size (via [createNormalizerLine]).
 * This ensures all charts have the same maxPointSize, which makes Vico compute identical:
 * - `xSpacing` (maxPointSize + pointSpacing) → same content width → pixel scroll sync works
 * - `unscalableStartPadding` (maxPointSize / 2) → same content offset → no start alignment shift
 *
 * Without this, each chart's different point sizes cause different layout, breaking pixel-based sync.
 */
val NORMALIZER_POINT_SIZE: Dp = 22.dp

/**
 * Creates an invisible line with [NORMALIZER_POINT_SIZE] transparent points.
 * Include this in every chart's lines list to normalize layout across synchronized graphs.
 */
fun createNormalizerLine(): LineCartesianLayer.Line =
    LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
        areaFill = null,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(
                component = ShapeComponent(fill = Fill(Color.Transparent), shape = CircleShape),
                size = NORMALIZER_POINT_SIZE
            )
        )
    )

/**
 * Y data for the normalizer dummy series. Always add this to every chart's lineModel block.
 * Two points at y=0, invisible, just to occupy a series slot for the normalizer line.
 */
val NORMALIZER_Y = listOf(0.0, 0.0)

/**
 * X data for the normalizer dummy series spanning the full chart range.
 * Must reach [maxX] so Vico computes the same scrollable content width across all charts.
 * Without this, charts without prediction data (IOB, COB) have shorter scroll extent
 * than the BG chart, causing them to stop following when scrolling into the forecast area.
 */
fun normalizerX(maxX: Double): List<Double> = listOf(0.0, maxX)

/**
 * Vico data-label guardrail.
 *
 * Very large X ranges can make Vico compute oversized text constraints while drawing point labels
 * (bolus/carbs/ext-bolus), leading to:
 * "Can't represent a width ... in Constraints".
 *
 * We keep point markers visible, but disable text labels when the chart span gets too large.
 */
private const val MAX_SAFE_DATA_LABEL_X_SPAN_MINUTES = 10_000.0

fun shouldRenderPointDataLabels(maxX: Double): Boolean =
    maxX.isFinite() && maxX in 0.0..MAX_SAFE_DATA_LABEL_X_SPAN_MINUTES

/**
 * Triangle shape pointing upward (apex at top center, flat base at bottom).
 *
 * Used for rendering SMB markers on graphs. The triangle sits on the X axis
 * with the point facing up, making it visually distinct from circle dots.
 */
val TriangleShape: Shape = GenericShape { size, _ ->
    val baseHalf = size.width * 0.3f         // Narrow base for sharper triangle
    val cx = size.width / 2f
    moveTo(cx, 0f)                           // Top center (apex)
    lineTo(cx + baseHalf, size.height / 2f)  // Right base
    lineTo(cx - baseHalf, size.height / 2f)  // Left base
    close()
}

/**
 * Inverted triangle shape pointing downward (flat base at top, apex at bottom).
 *
 * Used for rendering bolus markers on graphs. The base sits at the data point's
 * y-coordinate with the apex pointing down.
 */
val InvertedTriangleShape: Shape = GenericShape { size, _ ->
    val baseHalf = size.width * 0.3f         // Narrow base for sharper triangle
    val cx = size.width / 2f
    moveTo(cx - baseHalf, 0f)               // Top left (base)
    lineTo(cx + baseHalf, 0f)               // Top right (base)
    lineTo(cx, size.height / 2f)            // Bottom center (apex)
    close()
}

/**
 * Creates a line for BG prediction series.
 * Transparent connecting line with small filled circle points in the given color.
 * Each prediction type (IOB, COB, UAM, ZT, aCOB) uses a different color.
 */
fun createPredictionLine(color: Color): LineCartesianLayer.Line =
    LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(Color.Transparent)),
        areaFill = null,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(
                component = ShapeComponent(
                    fill = Fill(color),
                    shape = CircleShape
                ),
                size = 4.dp
            )
        )
    )

/**
 * Softer BG prediction series: faint connector + smaller pastel dots (dashboard calm mode).
 */
fun createSoftPredictionLine(color: Color): LineCartesianLayer.Line =
    LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color.copy(alpha = 0.14f))),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 0.85.dp, cap = StrokeCap.Round),
        areaFill = null,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(
                component = ShapeComponent(
                    fill = Fill(color.copy(alpha = 0.72f)),
                    shape = CircleShape
                ),
                size = 3.5.dp
            )
        )
    )

/**
 * AIMI scenario **clinical floor** (maps to legacy IOB prediction series on the graph).
 *
 * Vico draws the connector from [LineCartesianLayer.Line.fill]; keep it **opaque** — low-alpha fill
 * made the dashed prediction nearly invisible on the dark dashboard (regression vs Canvas renderer).
 */
fun createScenarioFloorLine(
    color: Color,
    pointHaloColor: Color = Color.White,
): LineCartesianLayer.Line =
    LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        stroke = LineCartesianLayer.LineStroke.Dashed(
            thickness = 2.5.dp,
            cap = StrokeCap.Round,
            dashLength = 6.dp,
            gapLength = 4.dp,
        ),
        areaFill = null,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(
                component = ShapeComponent(
                    fill = Fill(color),
                    shape = CircleShape,
                    strokeFill = Fill(pointHaloColor.copy(alpha = 0.72f)),
                    strokeThickness = 1.dp,
                ),
                size = 5.dp,
            )
        ),
    )

/**
 * AIMI scenario **best path** (maps to legacy UAM prediction series on the graph).
 * Stronger weight than [createScenarioFloorLine] so the authoritative curve reads first.
 */
fun createScenarioBestLine(
    color: Color,
    pointHaloColor: Color = Color.White,
): LineCartesianLayer.Line =
    LineCartesianLayer.Line(
        fill = LineCartesianLayer.LineFill.single(Fill(color)),
        stroke = LineCartesianLayer.LineStroke.Dashed(
            thickness = 2.75.dp,
            cap = StrokeCap.Round,
            dashLength = 7.dp,
            gapLength = 4.dp,
        ),
        areaFill = null,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.Point(
                component = ShapeComponent(
                    fill = Fill(color),
                    shape = CircleShape,
                    strokeFill = Fill(pointHaloColor.copy(alpha = 0.78f)),
                    strokeThickness = 1.dp,
                ),
                size = 5.5.dp,
            )
        ),
    )

/** Blend prediction / accent colors toward surface for a less alarming palette. */
fun softenChartColor(accent: Color, surface: Color, amount: Float = 0.22f): Color =
    lerp(accent, surface, amount)

/**
 * Horizontal band between [yLow] and [yHigh] (glycémie units), drawn under other decorations in the list order.
 * Uses the same vertical mapping as [DashboardTbrLaneDecoration] when axis bounds are fixed.
 */
class TargetComfortCorridorDecoration(
    private val yLow: Double,
    private val yHigh: Double,
    private val bgAxisMinY: Double,
    private val bgAxisMaxY: Double,
    private val fillColor: Color,
    private val fillAlpha: Float = 0.085f,
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        if (bgAxisMaxY <= bgAxisMinY) return
        val lo = minOf(yLow, yHigh).coerceIn(bgAxisMinY, bgAxisMaxY)
        val hi = maxOf(yLow, yHigh).coerceIn(bgAxisMinY, bgAxisMaxY)
        if (hi <= lo) return
        with(context) {
            val span = bgAxisMaxY - bgAxisMinY
            fun glucoseYToCanvas(y: Double): Float {
                val t = ((y - bgAxisMinY) / span).toFloat().coerceIn(0f, 1f)
                return layerBounds.bottom - t * layerBounds.height
            }
            val topY = glucoseYToCanvas(hi)
            val bottomY = glucoseYToCanvas(lo)
            val rectTop = minOf(topY, bottomY)
            val h = (bottomY - rectTop).coerceAtLeast(2f)
            with(mutableDrawScope) {
                drawRoundRect(
                    color = fillColor.copy(alpha = fillAlpha),
                    topLeft = Offset(layerBounds.left, rectTop),
                    size = Size((layerBounds.right - layerBounds.left).coerceAtLeast(1f), h),
                    cornerRadius = CornerRadius(10f, 10f),
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is TargetComfortCorridorDecoration &&
            yLow == other.yLow &&
            yHigh == other.yHigh &&
            bgAxisMinY == other.bgAxisMinY &&
            bgAxisMaxY == other.bgAxisMaxY &&
            fillColor == other.fillColor &&
            fillAlpha == other.fillAlpha

    override fun hashCode(): Int {
        var result = yLow.hashCode()
        result = 31 * result + yHigh.hashCode()
        result = 31 * result + bgAxisMinY.hashCode()
        result = 31 * result + bgAxisMaxY.hashCode()
        result = 31 * result + fillColor.hashCode()
        result = 31 * result + fillAlpha.hashCode()
        return result
    }
}

@Composable
fun rememberTargetComfortCorridorDecoration(
    corridor: Pair<Double, Double>?,
    bgAxisMinY: Double,
    bgAxisMaxY: Double,
    fillColor: Color,
    fillAlpha: Float = 0.085f,
): TargetComfortCorridorDecoration? {
    return remember(corridor, bgAxisMinY, bgAxisMaxY, fillColor, fillAlpha) {
        val c = corridor
        if (c == null || bgAxisMaxY <= bgAxisMinY || c.second <= c.first) {
            null
        } else {
            TargetComfortCorridorDecoration(
                yLow = c.first,
                yHigh = c.second,
                bgAxisMinY = bgAxisMinY,
                bgAxisMaxY = bgAxisMaxY,
                fillColor = fillColor,
                fillAlpha = fillAlpha,
            )
        }
    }
}

/**
 * "Now" vertical dotted line decoration for Vico charts.
 * Draws a dotted vertical line at the current time position across the full chart height.
 * Shared across all graphs (BG, IOB, COB, Treatment Belt) for consistent "now" indication.
 *
 * @param nowX The x-value for "now" (minutes from minTimestamp, via [timestampToX])
 * @param color The line color
 * @param strokeWidthPx Line stroke width in pixels
 * @param dashLengthPx Dash segment length in pixels
 * @param gapLengthPx Gap between dashes in pixels
 */
class NowLine(
    private val nowX: Double,
    private val color: Color,
    private val strokeWidthPx: Float = 2f,
    private val dashLengthPx: Float = 6f,
    private val gapLengthPx: Float = 4f
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            val xStep = ranges.xStep
            if (xStep == 0.0) return

            // Convert x-value to canvas coordinate (mirrors Vico's internal getDrawX logic)
            val canvasX = layerBounds.left +
                layerDimensions.startPadding +
                layerDimensions.xSpacing * ((nowX - ranges.minX) / xStep).toFloat() -
                scroll

            // Skip if outside visible area
            if (canvasX < layerBounds.left || canvasX > layerBounds.right) return

            with(mutableDrawScope) {
                drawLine(
                    color = this@NowLine.color,
                    start = Offset(canvasX, layerBounds.top),
                    end = Offset(canvasX, layerBounds.bottom),
                    strokeWidth = strokeWidthPx,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(dashLengthPx, gapLengthPx), 0f
                    )
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is NowLine &&
            nowX == other.nowX &&
            color == other.color &&
            strokeWidthPx == other.strokeWidthPx

    override fun hashCode(): Int {
        var result = nowX.hashCode()
        result = 31 * result + color.hashCode()
        result = 31 * result + strokeWidthPx.hashCode()
        return result
    }
}

/**
 * Remember a [NowLine] decoration for the current time.
 * @param nowTimestamp current time in millis — pass a ticker value so the line updates periodically
 */
@Composable
fun rememberNowLine(minTimestamp: Long, nowTimestamp: Long, color: Color): NowLine {
    return remember(minTimestamp, nowTimestamp, color) {
        NowLine(nowX = timestampToX(nowTimestamp, minTimestamp), color = color)
    }
}

/**
 * TBR lane + bars (or vertical marker lines) in dashboard coordinates, using the same X mapping as [NowLine].
 *
 * When [bgAxisYMin] and [bgAxisYMax] are non-null, the lane is anchored to **glycémie = 0** in data space
 * (same vertical scale as the BG layer). Otherwise falls back to [legacyBottomReservePx] above the layer bottom.
 */
class DashboardTbrLaneDecoration(
    private val minTimestamp: Long,
    private val segments: List<ChartTbrSegment>,
    private val markerEpochMs: List<Long>,
    private val bgAxisYMin: Double?,
    private val bgAxisYMax: Double?,
    private val legacyBottomReservePx: Float,
    private val laneBackground: Color,
    private val barFillSoft: Color,
    private val barFillStrong: Color,
    private val markerLineColor: Color,
    /** Lower contrast bars, thinner markers — therapy strip stays readable without alarm-like weight. */
    private val softStyle: Boolean = false,
    /**
     * When true (activity + SMB + TBR strip under the BG chart), the TBR lane uses a larger share of
     * height so temp basal bars stay readable on a short chart.
     */
    private val therapyStrip: Boolean = false,
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        if (segments.isEmpty() && markerEpochMs.isEmpty()) return
        with(context) {
            val xStep = ranges.xStep
            if (xStep == 0.0) return@with

            val plotLeft = layerBounds.left
            val plotRight = layerBounds.right
            val plotTop = layerBounds.top
            val plotBottom = layerBounds.bottom

            val minY = bgAxisYMin
            val maxY = bgAxisYMax
            val laneBottom = if (minY != null && maxY != null && maxY > minY) {
                val span = maxY - minY
                fun glucoseYToCanvas(y: Double): Float {
                    val t = ((y - minY) / span).toFloat().coerceIn(0f, 1f)
                    return plotBottom - t * layerBounds.height
                }
                kotlin.math.min(glucoseYToCanvas(0.0) - 1.5f, plotBottom - 4f)
            } else {
                val reserve = legacyBottomReservePx.coerceAtLeast(4f)
                plotBottom - reserve - 1.5f
            }

            val contentTop = plotTop
            val contentHeight = (laneBottom - contentTop).coerceAtLeast(1f)
            // Taller lane + bars than the first Vico port — temp basals are easier to read without stealing BG space.
            val laneH = if (therapyStrip) {
                (contentHeight * 0.34f).coerceIn(52f, 96f)
            } else {
                (contentHeight * 0.14f).coerceIn(24f, 56f)
            }
            val laneTop = laneBottom - laneH

            fun dataXToCanvasX(dataX: Double): Float =
                plotLeft +
                    layerDimensions.startPadding +
                    layerDimensions.xSpacing * ((dataX - ranges.minX) / xStep).toFloat() -
                    scroll

            val laneBgAlpha = if (softStyle) 0.06f else 0.12f
            val barHaloAlpha = if (softStyle) 0.12f else 0.28f
            val barCoreAlpha = if (softStyle) 0.38f else 0.72f
            val markOuterW = if (softStyle) 3f else 5f
            val markInnerW = if (softStyle) 1.2f else 2.8f
            val markOuterA = if (softStyle) 0.22f else 0.45f
            val markInnerA = if (softStyle) 0.42f else 1f
            val cornerLane = if (softStyle) CornerRadius(6f, 6f) else CornerRadius(4f, 4f)
            val cornerBar = if (softStyle) CornerRadius(5f, 5f) else CornerRadius(3f, 3f)

            with(mutableDrawScope) {
                drawRoundRect(
                    color = laneBackground.copy(alpha = laneBgAlpha),
                    topLeft = Offset(plotLeft, laneTop),
                    size = Size((plotRight - plotLeft).coerceAtLeast(1f), laneH),
                    cornerRadius = cornerLane,
                )

                if (segments.isNotEmpty()) {
                    for (seg in segments) {
                        var x1 = dataXToCanvasX(timestampToX(seg.startEpochMs, minTimestamp))
                        var x2 = dataXToCanvasX(timestampToX(seg.endEpochMs, minTimestamp))
                        if (x2 < x1) {
                            val tmp = x1
                            x1 = x2
                            x2 = tmp
                        }
                        x1 = x1.coerceIn(plotLeft, plotRight)
                        x2 = x2.coerceIn(plotLeft, plotRight)
                        val barW = (x2 - x1).coerceAtLeast(3f)
                        val barHMax = (laneH - (if (therapyStrip) 5f else 3f)).coerceAtLeast(if (therapyStrip) 18f else 12f)
                        val barH = (laneH * seg.intensity01).coerceIn(if (therapyStrip) 14f else 10f, barHMax)
                        val barTop = laneBottom - barH
                        drawRoundRect(
                            color = barFillSoft.copy(alpha = barHaloAlpha),
                            topLeft = Offset(x1 - 0.5f, barTop - 0.5f),
                            size = Size(barW + 1f, barH + 1f),
                            cornerRadius = cornerBar,
                        )
                        drawRoundRect(
                            color = barFillStrong.copy(alpha = barCoreAlpha),
                            topLeft = Offset(x1, barTop),
                            size = Size(barW, barH),
                            cornerRadius = cornerBar,
                        )
                    }
                } else {
                    for (t in markerEpochMs) {
                        val x = dataXToCanvasX(timestampToX(t, minTimestamp))
                        if (x < plotLeft || x > plotRight) continue
                        val yTop = contentTop + contentHeight * (if (therapyStrip) 0.52f else 0.68f)
                        drawLine(
                            color = markerLineColor.copy(alpha = markOuterA),
                            start = Offset(x, yTop),
                            end = Offset(x, laneTop),
                            strokeWidth = markOuterW,
                        )
                        drawLine(
                            color = markerLineColor.copy(alpha = markInnerA),
                            start = Offset(x, yTop),
                            end = Offset(x, laneTop),
                            strokeWidth = markInnerW,
                        )
                    }
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DashboardTbrLaneDecoration &&
            minTimestamp == other.minTimestamp &&
            segments == other.segments &&
            markerEpochMs == other.markerEpochMs &&
            bgAxisYMin == other.bgAxisYMin &&
            bgAxisYMax == other.bgAxisYMax &&
            legacyBottomReservePx == other.legacyBottomReservePx &&
            laneBackground == other.laneBackground &&
            barFillSoft == other.barFillSoft &&
            barFillStrong == other.barFillStrong &&
            markerLineColor == other.markerLineColor &&
            softStyle == other.softStyle &&
            therapyStrip == other.therapyStrip

    override fun hashCode(): Int {
        var result = minTimestamp.hashCode()
        result = 31 * result + segments.hashCode()
        result = 31 * result + markerEpochMs.hashCode()
        result = 31 * result + (bgAxisYMin?.hashCode() ?: 0)
        result = 31 * result + (bgAxisYMax?.hashCode() ?: 0)
        result = 31 * result + legacyBottomReservePx.hashCode()
        result = 31 * result + laneBackground.hashCode()
        result = 31 * result + barFillSoft.hashCode()
        result = 31 * result + barFillStrong.hashCode()
        result = 31 * result + markerLineColor.hashCode()
        result = 31 * result + softStyle.hashCode()
        result = 31 * result + therapyStrip.hashCode()
        return result
    }
}

@Composable
fun rememberDashboardTbrLaneDecoration(
    minTimestamp: Long,
    segments: List<ChartTbrSegment>,
    markerEpochMs: List<Long>,
    bgAxisYMin: Double?,
    bgAxisYMax: Double?,
    legacyBottomReservePx: Float,
    laneBackground: Color,
    barFillSoft: Color,
    barFillStrong: Color,
    markerLineColor: Color,
    softStyle: Boolean = false,
    therapyStrip: Boolean = false,
): DashboardTbrLaneDecoration? {
    return remember(
        minTimestamp,
        segments,
        markerEpochMs,
        bgAxisYMin,
        bgAxisYMax,
        legacyBottomReservePx,
        laneBackground,
        barFillSoft,
        barFillStrong,
        markerLineColor,
        softStyle,
        therapyStrip,
    ) {
        if (segments.isEmpty() && markerEpochMs.isEmpty()) {
            null
        } else {
            DashboardTbrLaneDecoration(
                minTimestamp = minTimestamp,
                segments = segments,
                markerEpochMs = markerEpochMs,
                bgAxisYMin = bgAxisYMin,
                bgAxisYMax = bgAxisYMax,
                legacyBottomReservePx = legacyBottomReservePx,
                laneBackground = laneBackground,
                barFillSoft = barFillSoft,
                barFillStrong = barFillStrong,
                markerLineColor = markerLineColor,
                softStyle = softStyle,
                therapyStrip = therapyStrip,
            )
        }
    }
}

/**
 * Plain (non-Compose-state) holder for the latest visible x-range, written from
 * [VisibleRangeReporter.drawOverLayers] on every draw pass.
 *
 * Deliberately NOT a Compose `State`: writing Compose state synchronously from the draw phase
 * fights with Vico's own gesture-driven scroll/zoom mutations — the resulting invalidate-during-draw
 * starves pinch-zoom gesture recognition (observed: zoom became unresponsive). Callers must poll
 * [value] from a coroutine (e.g. a `LaunchedEffect` with a periodic `delay`) and only then write it
 * into real Compose state.
 */
class VisibleRangeHolder {
    @Volatile
    var value: Pair<Double, Double>? = null
}

/**
 * Reports the currently visible x-range (same "minutes from minTimestamp" unit as [timestampToX])
 * into [holder] on every draw pass. Purely observational — draws nothing, and never touches
 * Compose state directly (see [VisibleRangeHolder]).
 *
 * Inverse of [NowLine]'s x-value-to-canvas transform: canvasX = layerBounds.left + startPadding +
 * xSpacing * ((x - minX) / xStep) - scroll. Solving for x at the left/right edges of layerBounds
 * gives the visible x-range.
 */
class VisibleRangeReporter(
    private val holder: VisibleRangeHolder
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            val xStep = ranges.xStep
            val xSpacing = layerDimensions.xSpacing
            if (xStep == 0.0 || xSpacing <= 0f) return

            val visibleMinX = ranges.minX + xStep * (scroll - layerDimensions.startPadding) / xSpacing
            val visibleWidth = xStep * layerBounds.width / xSpacing
            holder.value = visibleMinX to (visibleMinX + visibleWidth)
        }
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * Remember a [VisibleRangeReporter] decoration that writes the visible x-range into [holder]
 * on every draw pass.
 */
@Composable
fun rememberVisibleRangeReporter(holder: VisibleRangeHolder): VisibleRangeReporter {
    return remember(holder) { VisibleRangeReporter(holder) }
}

// =========================================================================
// Nice-numbers axis scaling ("Nice Numbers for Graph Labels", Paul Heckbert)
// =========================================================================
//
// Vico's default Y-axis item placer divides a fixed [minY, maxY] range into evenly-spaced
// ticks with no rounding, so an arbitrary data-driven range (e.g. 12.39..57.39) produces ugly
// tick labels (12.39, 27.39, 42.39...). These helpers snap axis bounds and tick spacing to
// round numbers (1, 2, 5, 10, 20, 50... times a power of ten) instead.

/** A "nice" axis range: bounds and tick spacing all rounded to 1/2/5/10 x 10^n. */
data class NiceScale(val min: Double, val max: Double, val step: Double)

/**
 * Rounds [range] to a value of the form 1/2/2.5/5/10 x 10^n. [round] chooses the nearest such
 * value (used for tick spacing) vs. always rounding up (used for axis bounds, so the bound never
 * clips inside the data). [range] must be > 0.
 *
 * The round-up variant includes a 2.5 tier (a known variant of Heckbert's original {1,2,5,10}
 * set) to avoid an overly coarse jump between the 2 and 5 tiers — e.g. without it, a BG max of
 * 210 would round all the way up to 300 (skipping straight from a 50-step scale to a 100-step
 * one); with it, 210 rounds to 250 first, only reaching 300 once the value exceeds 250.
 */
private fun niceNum(range: Double, round: Boolean): Double {
    val exponent = floor(log10(range))
    val fraction = range / 10.0.pow(exponent)
    val niceFraction = if (round) {
        when {
            fraction < 1.5 -> 1.0
            fraction < 3.0 -> 2.0
            fraction < 7.0 -> 5.0
            else           -> 10.0
        }
    } else {
        when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 2.5 -> 2.5
            fraction <= 5.0 -> 5.0
            else            -> 10.0
        }
    }
    return niceFraction * 10.0.pow(exponent)
}

/**
 * Standard nice-ify: snaps [min, max] and the tick spacing to round numbers. Free-range, no
 * zero or pivot anchoring — used for series like VAR_SENSITIVITY and HEART_RATE.
 */
fun niceScale(min: Double, max: Double, maxTickCount: Int = 5): NiceScale {
    val safeMax = if (max > min) max else min + 1.0
    val range = niceNum(safeMax - min, round = false)
    val step = niceNum(range / (maxTickCount - 1), round = true)
    return NiceScale(floor(min / step) * step, ceil(safeMax / step) * step, step)
}

/** Rounds [value] up to the nearest nice number (1/2/5/10 x 10^n). Used for a lone axis bound. */
fun niceUp(value: Double): Double {
    if (value <= 0.0) return 0.0
    return niceNum(value, round = false)
}

/**
 * Rounds [value] (expected negative) further negative to the nearest nice magnitude — e.g.
 * -0.3 -> -0.5, -0.07 -> -0.1 (but -0.05 is already nice and is left unchanged). Used to give a
 * small negative excursion its own clean bound, decoupled from a much larger positive-side scale
 * (see [zeroFloorNiceRange]).
 */
fun niceNegativeSliver(value: Double): Double {
    if (value >= 0.0) return 0.0
    return -niceNum(-value, round = false)
}

/**
 * Minimum half-width for SENSITIVITY's pivot scale (see [niceScaleAroundPivot]'s `minDeviation`
 * param) — below this, snap to a fixed 95%/100%/105% scale instead of nice-ifying a near-zero
 * deviation (e.g. a SENS ratio sitting flat around 100%).
 */
const val SENS_MIN_DEVIATION = 5.0

/**
 * Nice-ify a symmetric deviation around [pivot] (e.g. SENSITIVITY around 100%, DEV_SLOPE
 * around 0), so [pivot] always lands exactly in the middle of the axis. Nice-ifying [min, max]
 * directly (via [niceScale]) would not preserve that centering.
 */
fun niceScaleAroundPivot(min: Double, max: Double, pivot: Double, maxTickCount: Int = 5, minDeviation: Double = 0.0): NiceScale {
    val rawDeviation = maxOf(abs(pivot - min), abs(max - pivot))
    // Below the floor (e.g. SENS sitting flat around 100%): snap to a fixed 3-tick scale
    // (pivot-minDeviation, pivot, pivot+minDeviation) instead of nice-ifying a near-zero range,
    // which would otherwise produce an overly tight, non-round scale (e.g. 99/99.5/100/100.5/101).
    if (minDeviation > 0.0 && rawDeviation <= minDeviation) return NiceScale(pivot - minDeviation, pivot + minDeviation, minDeviation)
    val deviation = if (rawDeviation > 0.0) rawDeviation else 1.0
    val niceDeviation = niceNum(deviation, round = false)
    val step = niceNum(2 * niceDeviation / (maxTickCount - 1), round = true)
    val steppedDeviation = ceil(niceDeviation / step) * step
    return NiceScale(pivot - steppedDeviation, pivot + steppedDeviation, step)
}

/**
 * Series that are 0-floored by default (see [zeroFloorNiceRange]): mostly-positive, allowing a
 * disparity-aware negative excursion without ever centering zero. IOB follows the same rule but
 * isn't included here — its basal-overlay combo graph has its own dedicated range computation
 * (SecondaryGraphCompose's `primaryYMaxResult`) that calls [zeroFloorNiceRange] directly.
 */
val ZERO_FLOOR_SERIES_TYPES = setOf(SeriesType.BGI, SeriesType.DEVIATIONS, SeriesType.ACTIVITY, SeriesType.STEPS, SeriesType.ABS_IOB)

/**
 * Zero-floor axis range, disparity-aware: when the negative excursion is tiny relative to the
 * positive side (ratio >= [disparityRatio]), one shared nice tick spacing across the whole
 * range would make the small negative part look arbitrary — so the two sides are nice-ified
 * independently instead (a small negative sliver just large enough to show the excursion, plus
 * a normal positive-side scale). When the two sides are comparable in magnitude, a single
 * unified nice scale spans the whole range, negative and positive sharing the same tick step.
 * Used for IOB and [ZERO_FLOOR_SERIES_TYPES].
 */
fun zeroFloorNiceRange(dataMin: Double, dataMax: Double, maxTickCount: Int = 5, disparityRatio: Double = 10.0): NiceScale {
    if (dataMin >= 0.0) return niceScale(0.0, dataMax, maxTickCount)
    val absMin = -dataMin
    val ratio = if (absMin > 0.0) dataMax / absMin else Double.MAX_VALUE
    return if (ratio >= disparityRatio) {
        val niceMax = niceUp(dataMax)
        val niceMin = niceNegativeSliver(dataMin)
        val step = niceNum(niceMax / (maxTickCount - 1), round = true)
        NiceScale(niceMin, niceMax, step)
    } else {
        niceScale(dataMin, dataMax, maxTickCount)
    }
}
