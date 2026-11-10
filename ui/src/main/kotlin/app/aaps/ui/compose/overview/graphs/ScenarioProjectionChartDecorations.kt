package app.aaps.ui.compose.overview.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgType
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import kotlin.math.abs
import kotlin.math.min

/**
 * Chart-space geometry for AIMI scenario floor (IOB) and best (UAM) curves.
 */
internal data class ScenarioProjectionChartGeometry(
    val floorPoints: List<Pair<Double, Double>>,
    val bestPoints: List<Pair<Double, Double>>,
    val terminalBest: Pair<Double, Double>?,
)

internal fun buildScenarioProjectionChartGeometry(
    predictions: List<BgDataPoint>,
    minTimestamp: Long,
    glucoseMgdlToChartY: (Double) -> Double,
): ScenarioProjectionChartGeometry? {
    val floor = predictions
        .filter { it.type == BgType.IOB_PREDICTION }
        .sortedBy { it.timestamp }
    val best = predictions
        .filter { it.type == BgType.UAM_PREDICTION }
        .sortedBy { it.timestamp }
    if (floor.size < 2 || best.size < 2) return null

    fun toChartPoint(point: BgDataPoint): Pair<Double, Double> =
        timestampToX(point.timestamp, minTimestamp) to glucoseMgdlToChartY(point.value)

    val pairedCount = min(floor.size, best.size)
    val floorChart = floor.take(pairedCount).map(::toChartPoint)
    val bestChart = best.take(pairedCount).map(::toChartPoint)
    return ScenarioProjectionChartGeometry(
        floorPoints = floorChart,
        bestPoints = bestChart,
        terminalBest = bestChart.lastOrNull(),
    )
}

/**
 * Semi-transparent band between clinical floor and scenario-best curves.
 * Drawn under the line series so prediction strokes stay crisp on top.
 */
internal class ScenarioProjectionEnvelopeDecoration(
    private val floorPoints: List<Pair<Double, Double>>,
    private val bestPoints: List<Pair<Double, Double>>,
    private val bgAxisMinY: Double,
    private val bgAxisMaxY: Double,
    private val fillColor: Color,
    private val fillAlpha: Float = 0.14f,
) : Decoration {

    override fun drawUnderLayers(context: CartesianDrawingContext) {
        if (floorPoints.size < 2 || bestPoints.size < 2 || bgAxisMaxY <= bgAxisMinY) return
        with(context) {
            val xStep = ranges.xStep
            if (xStep == 0.0) return@with

            val plotLeft = layerBounds.left
            val plotRight = layerBounds.right
            val plotBottom = layerBounds.bottom
            val plotHeight = layerBounds.height

            fun chartYToCanvas(y: Double): Float {
                val t = ((y - bgAxisMinY) / (bgAxisMaxY - bgAxisMinY)).toFloat().coerceIn(0f, 1f)
                return plotBottom - t * plotHeight
            }

            fun dataXToCanvasX(dataX: Double): Float =
                plotLeft +
                    layerDimensions.startPadding +
                    layerDimensions.xSpacing * ((dataX - ranges.minX) / xStep).toFloat() -
                    scroll

            val path = Path()
            floorPoints.forEachIndexed { index, (x, y) ->
                val canvasX = dataXToCanvasX(x)
                val canvasY = chartYToCanvas(y)
                if (index == 0) path.moveTo(canvasX, canvasY) else path.lineTo(canvasX, canvasY)
            }
            for (index in bestPoints.indices.reversed()) {
                val (x, y) = bestPoints[index]
                path.lineTo(dataXToCanvasX(x), chartYToCanvas(y))
            }
            path.close()

            with(mutableDrawScope) {
                drawPath(path = path, color = fillColor.copy(alpha = fillAlpha))
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ScenarioProjectionEnvelopeDecoration &&
            floorPoints == other.floorPoints &&
            bestPoints == other.bestPoints &&
            bgAxisMinY == other.bgAxisMinY &&
            bgAxisMaxY == other.bgAxisMaxY &&
            fillColor == other.fillColor &&
            fillAlpha == other.fillAlpha

    override fun hashCode(): Int {
        var result = floorPoints.hashCode()
        result = 31 * result + bestPoints.hashCode()
        result = 31 * result + bgAxisMinY.hashCode()
        result = 31 * result + bgAxisMaxY.hashCode()
        result = 31 * result + fillColor.hashCode()
        result = 31 * result + fillAlpha.hashCode()
        return result
    }
}

/**
 * Highlights the terminal of the scenario-best curve (eventualBG anchor at horizon end).
 */
internal class ScenarioTerminalMarkerDecoration(
    private val terminalX: Double,
    private val terminalChartY: Double,
    private val bgAxisMinY: Double,
    private val bgAxisMaxY: Double,
    private val markerColor: Color,
    private val glowColor: Color,
) : Decoration {

    override fun drawOverLayers(context: CartesianDrawingContext) {
        if (bgAxisMaxY <= bgAxisMinY) return
        with(context) {
            val xStep = ranges.xStep
            if (xStep == 0.0) return@with

            val plotLeft = layerBounds.left
            val plotRight = layerBounds.right
            val plotTop = layerBounds.top
            val plotBottom = layerBounds.bottom
            val plotHeight = layerBounds.height

            fun chartYToCanvas(y: Double): Float {
                val t = ((y - bgAxisMinY) / (bgAxisMaxY - bgAxisMinY)).toFloat().coerceIn(0f, 1f)
                return plotBottom - t * plotHeight
            }

            val canvasX =
                plotLeft +
                    layerDimensions.startPadding +
                    layerDimensions.xSpacing * ((terminalX - ranges.minX) / xStep).toFloat() -
                    scroll
            if (canvasX < plotLeft - 8f || canvasX > plotRight + 8f) return@with

            val canvasY = chartYToCanvas(terminalChartY)
            val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 10f), 0f)

            with(mutableDrawScope) {
                drawLine(
                    color = markerColor.copy(alpha = 0.42f),
                    start = Offset(canvasX, plotTop),
                    end = Offset(canvasX, plotBottom),
                    strokeWidth = 1.25f,
                    pathEffect = dashEffect,
                )
                drawCircle(color = glowColor.copy(alpha = 0.38f), radius = 11f, center = Offset(canvasX, canvasY))
                drawCircle(color = markerColor.copy(alpha = 0.92f), radius = 6.5f, center = Offset(canvasX, canvasY))
                drawCircle(
                    color = markerColor,
                    radius = 4f,
                    center = Offset(canvasX, canvasY),
                    style = Stroke(width = 1.5f),
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ScenarioTerminalMarkerDecoration &&
            terminalX == other.terminalX &&
            terminalChartY == other.terminalChartY &&
            bgAxisMinY == other.bgAxisMinY &&
            bgAxisMaxY == other.bgAxisMaxY &&
            markerColor == other.markerColor &&
            glowColor == other.glowColor

    override fun hashCode(): Int {
        var result = terminalX.hashCode()
        result = 31 * result + terminalChartY.hashCode()
        result = 31 * result + bgAxisMinY.hashCode()
        result = 31 * result + bgAxisMaxY.hashCode()
        result = 31 * result + markerColor.hashCode()
        result = 31 * result + glowColor.hashCode()
        return result
    }
}

@Composable
internal fun rememberScenarioProjectionDecorations(
    geometry: ScenarioProjectionChartGeometry?,
    bgAxisMinY: Double,
    bgAxisMaxY: Double,
    envelopeFillColor: Color,
    terminalMarkerColor: Color,
    terminalGlowColor: Color,
): List<Decoration> {
    return remember(
        geometry,
        bgAxisMinY,
        bgAxisMaxY,
        envelopeFillColor,
        terminalMarkerColor,
        terminalGlowColor,
    ) {
        val g = geometry ?: return@remember emptyList()
        if (g.floorPoints.size < 2 || g.bestPoints.size < 2) return@remember emptyList()

        val hasEnvelopeGap = g.floorPoints.zip(g.bestPoints).any { (floor, best) ->
            abs(best.second - floor.second) > 0.5
        }

        buildList {
            if (hasEnvelopeGap) {
                add(
                    ScenarioProjectionEnvelopeDecoration(
                        floorPoints = g.floorPoints,
                        bestPoints = g.bestPoints,
                        bgAxisMinY = bgAxisMinY,
                        bgAxisMaxY = bgAxisMaxY,
                        fillColor = envelopeFillColor,
                        fillAlpha = 0.16f,
                    ),
                )
            }
            g.terminalBest?.let { (terminalX, terminalY) ->
                add(
                    ScenarioTerminalMarkerDecoration(
                        terminalX = terminalX,
                        terminalChartY = terminalY,
                        bgAxisMinY = bgAxisMinY,
                        bgAxisMaxY = bgAxisMaxY,
                        markerColor = terminalMarkerColor,
                        glowColor = terminalGlowColor,
                    ),
                )
            }
        }
    }
}
