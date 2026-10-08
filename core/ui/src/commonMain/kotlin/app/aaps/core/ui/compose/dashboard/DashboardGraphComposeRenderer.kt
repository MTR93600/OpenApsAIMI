package app.aaps.core.ui.compose.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect

import kotlin.math.max
import kotlin.math.min

@Composable
internal fun DashboardGraphComposeRenderer(
    renderInput: DashboardGraphRenderInput,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val lineColor = scheme.primary
    val lineGlow = scheme.primary.copy(alpha = 0.38f)
    // Prediction (PRED): keep tertiary family but stronger contrast + heavier strokes so the dashed
    // future curve reads clearly on busy gradients (user feedback: line felt too light).
    val predictionColor = scheme.tertiary.copy(alpha = 0.98f)
    val predictionGlow = scheme.tertiary.copy(alpha = 0.38f)
    val targetBandEdge = scheme.primary.copy(alpha = 0.02f)
    val targetBandCore = scheme.primary.copy(alpha = 0.24f)
    val targetBandLine = scheme.primary.copy(alpha = 0.74f)
    val hypoOverlay = scheme.error.copy(alpha = 0.12f)
    val hyperOverlay = scheme.error.copy(alpha = 0.08f)
    val nowLineColor = scheme.tertiary.copy(alpha = 0.95f)
    val nowLineGlow = scheme.tertiary.copy(alpha = 0.28f)
    val smbMarkerColor = scheme.error
    val tbrMarkerColor = scheme.tertiary
    val bgTop = scheme.surfaceContainerHighest.copy(alpha = 0.55f)
    val bgMid = scheme.surface.copy(alpha = 0.92f)
    val bgBottom = scheme.surfaceContainerLow.copy(alpha = 0.85f)
    val vignette = scheme.primary.copy(alpha = 0.07f)
    val gridMajor = scheme.outline.copy(alpha = 0.20f)
    val gridMinor = scheme.outline.copy(alpha = 0.09f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas
        val reservedFabInset = min(72f, width * 0.16f)
        val plotLeft = 0f
        val plotTop = 0f
        val plotRight = width - reservedFabInset
        val plotBottom = height - 6f
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        // Layered plot background: depth + subtle primary wash + corner vignette
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to bgTop,
                    0.38f to bgMid,
                    1f to bgBottom,
                ),
                startY = 0f,
                endY = height,
            ),
            topLeft = Offset.Zero,
            size = Size(width = width, height = height),
        )
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to vignette,
                    0.42f to Color.Transparent,
                    1f to Color.Transparent,
                ),
                startY = 0f,
                endY = height,
            ),
            topLeft = Offset.Zero,
            size = Size(width = width, height = height),
        )
        drawRect(
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    0.5f to scheme.primary.copy(alpha = 0.045f),
                    1f to Color.Transparent,
                ),
                start = Offset(plotLeft, plotTop),
                end = Offset(plotRight, plotBottom),
            ),
            topLeft = Offset.Zero,
            size = Size(width = width, height = height),
        )

        val verticalTicks = 4
        val horizontalTicks = 5
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f), 0f)
        for (i in 0..verticalTicks) {
            val x = plotLeft + (i / verticalTicks.toFloat()) * plotWidth
            val gridPath = Path().apply {
                moveTo(x, plotTop)
                lineTo(x, plotBottom)
            }
            drawPath(
                path = gridPath,
                color = if (i % 2 == 0) gridMajor else gridMinor,
                style = Stroke(width = 1f, pathEffect = dashEffect),
            )
        }
        for (i in 0..horizontalTicks) {
            val y = plotTop + (i / horizontalTicks.toFloat()) * plotHeight
            val major = i == 0 || i == horizontalTicks || i == horizontalTicks / 2
            drawLine(
                color = if (major) gridMajor else gridMinor,
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = if (major) 1.15f else 0.9f,
            )
        }

        val predictionPoints = renderInput.predictionPoints
        val points = renderInput.points
        if (points.isEmpty() && predictionPoints.size < 2) return@Canvas
        val basePoints = if (points.isNotEmpty()) points else predictionPoints
        val minX = renderInput.fromTimeEpochMs.takeIf { it > 0L } ?: basePoints.minOf { it.timestampEpochMs }
        val maxX = renderInput.toTimeEpochMs.takeIf { it > minX } ?: basePoints.maxOf { it.timestampEpochMs }
        val valuesMin = basePoints.minOf { it.value }
        val valuesMax = basePoints.maxOf { it.value }
        val predMin = predictionPoints.minOfOrNull { it.value }
        val predMax = predictionPoints.maxOfOrNull { it.value }
        val targetLow = renderInput.targetLowMgdl
        val targetHigh = renderInput.targetHighMgdl
        val rawMinY = listOfNotNull(valuesMin, predMin, targetLow, targetHigh).minOrNull() ?: valuesMin
        val rawMaxY = listOfNotNull(valuesMax, predMax, targetLow, targetHigh).maxOrNull() ?: valuesMax
        val valueSpan = (rawMaxY - rawMinY).coerceAtLeast(1.0)
        val yPadding = (valueSpan * 0.12).coerceAtLeast(5.0)
        val minY = rawMinY - yPadding
        val maxY = rawMaxY + yPadding
        val xRange = max(1L, maxX - minX).toFloat()
        val yRange = max(1e-6, maxY - minY)

        fun toCanvasY(value: Double): Float {
            val normalizedY = ((value - minY) / yRange).toFloat()
            return plotBottom - (normalizedY * plotHeight)
        }
        fun toCanvasX(epochMs: Long): Float {
            return plotLeft + (((epochMs - minX) / xRange) * plotWidth)
        }

        if (targetLow != null && targetHigh != null && targetHigh > targetLow) {
            val yTop = toCanvasY(targetHigh)
            val yBottom = toCanvasY(targetLow)
            val bandH = (yBottom - yTop).coerceAtLeast(1f)
            val topOutHeight = (yTop - plotTop).coerceAtLeast(0f)
            if (topOutHeight > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to hyperOverlay,
                            1f to Color.Transparent,
                        ),
                        startY = plotTop,
                        endY = yTop,
                    ),
                    topLeft = Offset(plotLeft, plotTop),
                    size = Size(plotWidth, topOutHeight),
                )
            }
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to targetBandEdge,
                        0.22f to targetBandCore,
                        0.5f to targetBandCore.copy(alpha = 0.30f),
                        0.78f to targetBandCore,
                        1f to targetBandEdge,
                    ),
                    startY = yTop,
                    endY = yTop + bandH,
                ),
                topLeft = Offset(plotLeft, yTop),
                size = androidx.compose.ui.geometry.Size(plotWidth, bandH),
            )
            drawLine(
                color = targetBandLine.copy(alpha = 0.42f),
                start = Offset(plotLeft, yTop),
                end = Offset(plotRight, yTop),
                strokeWidth = 4.4f,
            )
            drawLine(
                color = targetBandLine,
                start = Offset(plotLeft, yTop),
                end = Offset(plotRight, yTop),
                strokeWidth = 2.2f,
            )
            drawLine(
                color = targetBandLine.copy(alpha = 0.42f),
                start = Offset(plotLeft, yBottom),
                end = Offset(plotRight, yBottom),
                strokeWidth = 4.4f,
            )
            drawLine(
                color = targetBandLine,
                start = Offset(plotLeft, yBottom),
                end = Offset(plotRight, yBottom),
                strokeWidth = 2.2f,
            )
            val bottomOutHeight = (plotBottom - yBottom).coerceAtLeast(0f)
            if (bottomOutHeight > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            1f to hypoOverlay,
                        ),
                        startY = yBottom,
                        endY = plotBottom,
                    ),
                    topLeft = Offset(plotLeft, yBottom),
                    size = Size(plotWidth, bottomOutHeight),
                )
            }
        }

        val nowEpoch = renderInput.nowEpochMs.takeIf { it in minX..maxX }
        if (nowEpoch != null) {
            val xNow = toCanvasX(nowEpoch)
            drawLine(
                color = nowLineGlow,
                start = Offset(xNow, plotTop),
                end = Offset(xNow, plotBottom),
                strokeWidth = 6f,
            )
            drawLine(
                color = nowLineColor,
                start = Offset(xNow, plotTop),
                end = Offset(xNow, plotBottom),
                strokeWidth = 2.2f,
            )
            val dashNow = PathEffect.dashPathEffect(floatArrayOf(4f, 8f), 0f)
            val nowDashPath = Path().apply {
                moveTo(xNow, plotTop)
                lineTo(xNow, plotBottom)
            }
            drawPath(
                path = nowDashPath,
                color = scheme.onSurface.copy(alpha = 0.38f),
                style = Stroke(width = 1f, pathEffect = dashNow, cap = StrokeCap.Round),
            )
        }

        val sorted = basePoints.sortedBy { it.timestampEpochMs }
        fun interpolateBgY(epochMs: Long): Float {
            if (sorted.isEmpty()) return plotBottom - plotHeight * 0.08f
            if (sorted.size == 1) return toCanvasY(sorted[0].value)
            if (epochMs <= sorted.first().timestampEpochMs) return toCanvasY(sorted.first().value)
            if (epochMs >= sorted.last().timestampEpochMs) return toCanvasY(sorted.last().value)
            for (i in 0 until sorted.lastIndex) {
                val a = sorted[i]
                val b = sorted[i + 1]
                if (epochMs < a.timestampEpochMs || epochMs > b.timestampEpochMs) continue
                val span = (b.timestampEpochMs - a.timestampEpochMs).toDouble().coerceAtLeast(1.0)
                val t = ((epochMs - a.timestampEpochMs).toDouble() / span).coerceIn(0.0, 1.0)
                val v = a.value + t * (b.value - a.value)
                return toCanvasY(v)
            }
            return toCanvasY(sorted.last().value)
        }

        if (sorted.size >= 2) {
            clipRect(left = plotLeft, top = plotTop, right = plotRight, bottom = plotBottom) {
                val fillPath = Path()
                val linePath = Path()
                sorted.forEachIndexed { idx, p ->
                    val x = toCanvasX(p.timestampEpochMs)
                    val y = toCanvasY(p.value)
                    if (idx == 0) {
                        linePath.moveTo(x, y)
                        fillPath.moveTo(x, plotBottom)
                        fillPath.lineTo(x, y)
                    } else {
                        linePath.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                    if (idx == sorted.lastIndex) {
                        fillPath.lineTo(x, plotBottom)
                        fillPath.close()
                    }
                }
                val yCurveTop = sorted.minOf { toCanvasY(it.value) }.coerceAtMost(plotBottom - 2f)
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to lineColor.copy(alpha = 0.34f),
                            0.45f to lineColor.copy(alpha = 0.14f),
                            1f to lineColor.copy(alpha = 0.03f),
                        ),
                        startY = yCurveTop,
                        endY = plotBottom,
                    ),
                )
                drawPath(
                    path = linePath,
                    color = lineGlow,
                    style = Stroke(width = 7.5f, cap = StrokeCap.Round),
                )
                drawPath(
                    path = linePath,
                    color = lineColor.copy(alpha = 0.55f),
                    style = Stroke(width = 5f, cap = StrokeCap.Round),
                )
                drawPath(
                    path = linePath,
                    color = lineColor,
                    style = Stroke(width = 3f, cap = StrokeCap.Round),
                )
                if (predictionPoints.size >= 2) {
                    val predictionPath = Path()
                    predictionPoints
                        .sortedBy { it.timestampEpochMs }
                        .forEachIndexed { index, point ->
                            val x = toCanvasX(point.timestampEpochMs)
                            val y = toCanvasY(point.value)
                            if (index == 0) predictionPath.moveTo(x, y) else predictionPath.lineTo(x, y)
                        }
                    val dashPred = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f)
                    drawPath(
                        path = predictionPath,
                        color = predictionGlow,
                        style = Stroke(
                            width = 9f,
                            cap = StrokeCap.Round,
                            pathEffect = dashPred,
                        ),
                    )
                    drawPath(
                        path = predictionPath,
                        color = predictionColor.copy(alpha = 0.62f),
                        style = Stroke(
                            width = 5.8f,
                            cap = StrokeCap.Round,
                            pathEffect = dashPred,
                        ),
                    )
                    drawPath(
                        path = predictionPath,
                        color = predictionColor,
                        style = Stroke(
                            width = 3.6f,
                            cap = StrokeCap.Round,
                            pathEffect = dashPred,
                        ),
                    )
                }
            }
        }

        val tbrLaneH = plotHeight * 0.14f
        val tbrLaneBottom = plotBottom - 2f
        val tbrLaneTop = tbrLaneBottom - tbrLaneH
        val smbMaxY = tbrLaneTop - 8f

        if (renderInput.tbrSegments.isNotEmpty() || renderInput.tbrMarkerEpochMs.isNotEmpty()) {
            drawRoundRect(
                color = tbrMarkerColor.copy(alpha = 0.09f),
                topLeft = Offset(plotLeft, tbrLaneTop),
                size = Size(plotWidth, tbrLaneH),
                cornerRadius = CornerRadius(4f, 4f),
            )
        }
        if (renderInput.tbrSegments.isNotEmpty()) {
            for (seg in renderInput.tbrSegments) {
                var x1 = toCanvasX(seg.startEpochMs)
                var x2 = toCanvasX(seg.endEpochMs)
                if (x2 < x1) {
                    val tmp = x1
                    x1 = x2
                    x2 = tmp
                }
                x1 = x1.coerceIn(plotLeft, plotRight)
                x2 = x2.coerceIn(plotLeft, plotRight)
                val barW = (x2 - x1).coerceAtLeast(3f)
                // Very small canvases can make (tbrLaneH - 2f) < 6f; keep a valid coerceIn range.
                val barHMax = (tbrLaneH - 2f).coerceAtLeast(6f)
                val barH = (tbrLaneH * seg.intensity01).coerceIn(6f, barHMax)
                val barTop = tbrLaneBottom - barH
                drawRoundRect(
                    color = tbrMarkerColor.copy(alpha = 0.35f),
                    topLeft = Offset(x1 - 0.5f, barTop - 0.5f),
                    size = Size(barW + 1f, barH + 1f),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawRoundRect(
                    color = tbrMarkerColor.copy(alpha = 0.88f),
                    topLeft = Offset(x1, barTop),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(3f, 3f),
                )
            }
        } else {
            for (t in renderInput.tbrMarkerEpochMs) {
                val x = toCanvasX(t)
                if (x < plotLeft || x > plotRight) continue
                val yTop = plotTop + plotHeight * 0.68f
                drawLine(
                    color = tbrMarkerColor.copy(alpha = 0.45f),
                    start = Offset(x, yTop),
                    end = Offset(x, tbrLaneTop),
                    strokeWidth = 5f,
                )
                drawLine(
                    color = tbrMarkerColor,
                    start = Offset(x, yTop),
                    end = Offset(x, tbrLaneTop),
                    strokeWidth = 2.8f,
                )
            }
        }

        if (sorted.isNotEmpty()) {
            val last = sorted.last()
            val lastX = toCanvasX(last.timestampEpochMs)
            val lastY = toCanvasY(last.value)
            drawCircle(
                color = scheme.inversePrimary.copy(alpha = 0.25f),
                radius = 14f,
                center = Offset(lastX, lastY),
            )
            drawCircle(
                color = lineColor.copy(alpha = 0.45f),
                radius = 10f,
                center = Offset(lastX, lastY),
            )
            drawCircle(
                color = scheme.surface.copy(alpha = 0.95f),
                radius = 6f,
                center = Offset(lastX, lastY),
            )
            drawCircle(
                color = lineColor,
                radius = 4f,
                center = Offset(lastX, lastY),
            )
        }

        // SMB: on the BG curve, above the TBR lane
        val smbRadiusPx = min(11f, plotHeight * 0.055f).coerceAtLeast(6f)
        for (m in renderInput.smbMarkers) {
            val t = m.timestampEpochMs
            val x = toCanvasX(t)
            if (x < plotLeft - smbRadiusPx || x > plotRight + smbRadiusPx) continue
            val cy = interpolateBgY(t).coerceIn(plotTop + smbRadiusPx, smbMaxY - smbRadiusPx)
            drawCircle(
                color = smbMarkerColor.copy(alpha = 0.35f),
                radius = smbRadiusPx + 2f,
                center = Offset(x, cy),
            )
            drawCircle(
                color = smbMarkerColor,
                radius = smbRadiusPx,
                center = Offset(x, cy),
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = smbRadiusPx,
                center = Offset(x, cy),
                style = Stroke(width = 2f),
            )
        }
    }
}
