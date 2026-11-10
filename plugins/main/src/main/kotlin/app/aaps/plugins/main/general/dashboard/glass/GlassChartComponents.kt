package app.aaps.plugins.main.general.dashboard.glass

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.ceil


/** How close to "now" (part of the visible history) the finger marker is pulled onto the current reading. */
private const val NOW_SNAP = 0.03f

/** The finger marker is pulled onto the current reading when it comes near it, so it is easy to stop on it. */
private fun snapToNow(progress: Float): Float = if (abs(progress - 1f) < NOW_SNAP) 1f else progress

@Composable
internal fun BgChartCard(
    readings: List<BgReadingPoint>,
    treatments: List<TreatmentPoint>,
    timeRangeHours: Float,
    currentBgValue: Float,
    lowLine: Float,
    highLine: Float,
    predictions: List<PredictionPoint>,
    historyFraction: Float,
    axisMinValue: Float,
    axisHeadroom: Float,
    basalReadings: List<BasalReadingPoint> = emptyList(),
    profileBasalReadings: List<BasalReadingPoint> = emptyList(),
    maxBasalRateUh: Float = 1f,
    formatValue: (Float) -> String,
    isDark: Boolean,
    /** Shared position (0..1 along the history) of the finger on this or the IOB chart; null when not touched. */
    scrubProgress: Float? = null,
    onScrub: (Float?) -> Unit = {},
    /** Finger drag along the time axis, as a fraction of the visible history (positive: finger moved right). */
    onPanBy: (Float) -> Unit = {},
    /** How far the window is moved back from now, in minutes; the time marks follow it. */
    panMinutes: Int = 0,
    /** True when the chart sits inside the combined card: no own glass container, fixed inner height. */
    embedded: Boolean = false,
) {
    val touchedReading: BgReadingPoint? = scrubProgress?.takeIf { it <= 1f }?.let { p -> readings.minByOrNull { abs(it.progress - p) } }
    // The marker may go past "now" into the forecast: q is the place along the forecast (0..1).
    val forecastQ: Float? = scrubProgress?.takeIf { it > 1f && historyFraction < 1f }
        ?.let { p -> ((p - 1f) * historyFraction / (1f - historyFraction)).coerceIn(0f, 1f) }
    val touchedPrediction: PredictionPoint? = forecastQ?.let { q ->
        val main = predictions.firstOrNull { it.type == PredictionType.IOB }?.type ?: predictions.firstOrNull()?.type
        predictions.filter { it.type == main }.minByOrNull { abs(it.progress - q) }
    }
    var selectedTreatment by remember { mutableStateOf<TreatmentPoint?>(null) }

    // When new data arrives, reset the drag back to the current reading
    LaunchedEffect(readings) {
        selectedTreatment = null
    }

    ChartShell(embedded = embedded, isDark = isDark, height = 215.dp) {
        // The plot fills the whole card up to its edges; the finger readout floats over its top right corner.
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 30.dp, top = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (touchedReading != null) {
                    val pt = touchedReading!!
                    val now = LocalTime.now()
                    val hoursAgo = timeRangeHours * (1f - pt.progress)
                    val pointTime = now.minusMinutes((hoursAgo * 60).toLong() + panMinutes)
                    val nearbyTreatment = treatments.minByOrNull { abs(it.progress - pt.progress) }
                    val isNearTreatment = nearbyTreatment != null && abs(nearbyTreatment.progress - pt.progress) < 0.025f
                    val timeStr = String.format(Locale.US, "%02d:%02d", pointTime.hour, pointTime.minute)
                    val bgStr = formatValue(pt.value)
                    val bgColor = when {
                        pt.value < lowLine -> Color(0xFFEF4444)
                        pt.value > highLine -> Color(0xFFF59E0B)
                        else -> Color(0xFF22C55E)
                    }
                    if (isNearTreatment) {
                        val t = nearbyTreatment!!
                        Text(
                            text = "$timeStr  ${t.label} - $bgStr",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = bgColor
                        )
                    } else {
                        Text(
                            text = "$timeStr  $bgStr",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = bgColor
                        )
                    }
                } else if (touchedPrediction != null) {
                    val horizonMin = timeRangeHours * 60f * (1f - historyFraction) / historyFraction
                    val at = LocalTime.now().plusMinutes(((forecastQ ?: 0f) * horizonMin).toLong())
                    Text(
                        text = String.format(Locale.US, "%02d:%02d  %s", at.hour, at.minute, formatValue(touchedPrediction.value)),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE6D39A)
                    )
                } else {
                    if (readings.isEmpty()) {
                        Text(
                            text = "--",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                    } else {
                        Text(
                            text = formatValue(currentBgValue),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                currentBgValue < lowLine -> Color(0xFFEF4444)
                                currentBgValue > highLine -> Color(0xFFF59E0B)
                                else -> Color(0xFF22C55E)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(0.dp))

            val treatmentsNow by rememberUpdatedState(treatments)
            val historyNow by rememberUpdatedState(historyFraction)
            val scrubNow by rememberUpdatedState(onScrub)
            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val chartW = size.width - 65f
                        val chartH = size.height - 36f
                        val treatmentY = chartH - 8f
                        val p = (down.position.x / (chartW * historyNow)).coerceIn(0f, 1f / historyNow)
                        val snapAtDown = treatmentsNow.filter { !it.isCarb }.minByOrNull { abs(it.progress - p) }
                        scrubNow(snapToNow(if (snapAtDown != null && abs(snapAtDown.progress - p) < 0.025f) snapAtDown.progress else p))

                        // Check if the touch landed near a treatment marker (X + Y)
                        val nearTreatment = treatmentsNow.minByOrNull { abs(it.progress - p) }
                        if (nearTreatment != null
                            && abs(nearTreatment.progress - p) < 0.035f
                            && abs(down.position.y - treatmentY) < 50f
                        ) {
                            selectedTreatment = nearTreatment
                        } else {
                            selectedTreatment = null
                        }

                        // The finger moves the marker line along the chart (the time is moved with the bar under it).
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            if (event.changes.any { it.pressed }) {
                                // Two fingers are a zoom: the marker line stays where it is.
                                if (event.changes.count { it.pressed } == 1) {
                                    val px = (event.changes.first().position.x / (chartW * historyNow)).coerceIn(0f, 1f / historyNow)
                                    val nearestBolus = treatmentsNow.filter { !it.isCarb }.minByOrNull { abs(it.progress - px) }
                                    scrubNow(snapToNow(if (nearestBolus != null && abs(nearestBolus.progress - px) < 0.025f) nearestBolus.progress else px))
                                    event.changes.first().consume()
                                }
                            } else {
                                break
                            }
                        }
                    }
                }
            ) {
                val w = size.width
                val h = size.height
                val paddingRight = 65f
                val chartW = w - paddingRight
                val chartH = h - 36f

                fun historyX(progress: Float): Float = progress * chartW * historyFraction
                fun predictionX(progress: Float): Float = (historyFraction + progress * (1f - historyFraction)) * chartW

                // Shared finger position: the same vertical line is drawn on the BG and IOB charts.
                scrubProgress?.let { p ->
                    val x = historyX(p)
                    drawLine(
                        color = if (isDark) Color(0x99FFFFFF) else Color(0x99334155),
                        start = Offset(x, 0f),
                        end = Offset(x, chartH),
                        strokeWidth = 2f
                    )
                }

                val minY = axisMinValue
                val maxY = maxOf(
                    readings.maxByOrNull { it.value }?.value ?: highLine,
                    predictions.maxByOrNull { it.value }?.value ?: highLine,
                    highLine
                ) + axisHeadroom

                fun mapY(bg: Float): Float {
                    val clamped = bg.coerceIn(minY, maxY)
                    return chartH - ((clamped - minY) / (maxY - minY)) * chartH
                }

                val yHigh = mapY(highLine)
                val yLow = mapY(lowLine)

                // 1. User range band (lowLine to highLine) with a green gradient
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF34D399).copy(alpha = if (isDark) 0.26f else 0.20f),
                            Color(0xFF34D399).copy(alpha = if (isDark) 0.08f else 0.04f)
                        ),
                        startY = yHigh,
                        endY = yLow
                    ),
                    topLeft = Offset(0f, yHigh),
                    size = Size(chartW, yLow - yHigh)
                )

                // 2. Horizontal reference lines (highLine and lowLine)
                val lineStroke = if (isDark) Color(0x2EFFFFFF) else Color(0x26000000)
                drawLine(color = lineStroke, start = Offset(0f, yHigh), end = Offset(chartW, yHigh), strokeWidth = 1f)
                drawLine(color = lineStroke, start = Offset(0f, yLow), end = Offset(chartW, yLow), strokeWidth = 1f)

                // Y-axis labels on the right (highLine and lowLine)
                val textPaint = Paint().apply {
                    color = if (isDark) android.graphics.Color.WHITE else android.graphics.Color.rgb(15, 23, 42)
                    textSize = 30f
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                val scaleCenterX = chartW + paddingRight / 2f
                drawContext.canvas.nativeCanvas.drawText(formatValue(highLine), scaleCenterX, yHigh + 8f, textPaint)
                drawContext.canvas.nativeCanvas.drawText(formatValue(lowLine), scaleCenterX, yLow + 8f, textPaint)

                // 3. Vertical time lines (at whole hours or half hours, by real time)
                drawTimeAxisGrid(timeRangeHours, panMinutes, chartW, historyFraction, chartH, if (isDark) Color(0x14FFFFFF) else Color(0x0D000000))

                // 3b. "Now" line separating history from predictions
                val nowX = chartW * historyFraction
                drawLine(
                    color = if (isDark) Color(0x40FFFFFF) else Color(0x30000000),
                    start = Offset(nowX, 0f),
                    end = Offset(nowX, chartH),
                    strokeWidth = 1.5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )

                // 3c. TBR (basal) overlay — dual Y-axis sharing this chart's height. Dashed step line for the
                // scheduled profile basal, solid step line with area fill for the actually delivered basal
                // (profile rate, or the temp rate while one is running) — same design as the classic
                // dashboard's BasalGraphData-driven graph. Confined to the bottom ~25% of the chart height
                // (maxY = maxBasalRateUh * 4) so it never competes visually with the BG curve above it.
                val maxYBasal = (maxBasalRateUh * 4f).coerceAtLeast(0.01f)
                fun mapBasalY(rateUh: Float): Float {
                    val clamped = rateUh.coerceIn(0f, maxYBasal)
                    return chartH - (clamped / maxYBasal) * chartH
                }
                fun stepPath(points: List<BasalReadingPoint>, endX: Float): Path {
                    val path = Path()
                    if (points.isEmpty()) return path
                    var x = historyX(points.first().progress)
                    var y = mapBasalY(points.first().rateUh)
                    path.moveTo(x, y)
                    for (i in 1 until points.size) {
                        val nx = historyX(points[i].progress)
                        path.lineTo(nx, y)
                        y = mapBasalY(points[i].rateUh)
                        path.lineTo(nx, y)
                        x = nx
                    }
                    path.lineTo(endX, y)
                    return path
                }
                val basalNowX = chartW * historyFraction
                if (basalReadings.isNotEmpty()) {
                    val linePath = stepPath(basalReadings, basalNowX)
                    val areaPath = Path().apply {
                        addPath(linePath)
                        lineTo(basalNowX, chartH)
                        lineTo(historyX(basalReadings.first().progress), chartH)
                        close()
                    }
                    drawPath(path = areaPath, color = Color(0xFF818CF8).copy(alpha = if (isDark) 0.18f else 0.12f))
                    drawPath(path = linePath, color = Color(0xFF818CF8).copy(alpha = 0.9f), style = Stroke(width = 3f))
                }
                if (profileBasalReadings.isNotEmpty()) {
                    drawPath(
                        path = stepPath(profileBasalReadings, basalNowX),
                        color = if (isDark) Color(0xFFCBD5E1).copy(alpha = 0.5f) else Color(0xFF64748B).copy(alpha = 0.6f),
                        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
                    )
                }

                // 4. BG curve and filled area - colored by range zone
                if (readings.size >= 2) {
                    fun zoneColor(bg: Float): Color = when {
                        bg < lowLine -> Color(0xFFEF4444)   // below range -> red
                        bg > highLine -> Color(0xFFF59E0B)  // above range -> amber
                        else -> Color(0xFF22C55E)           // in range -> green
                    }

                    // Segmented curve stroke, colored by zone
                    for (i in 1 until readings.size) {
                        val prev = readings[i - 1]
                        val curr = readings[i]

                        val pX = historyX(prev.progress)
                        val pY = mapY(prev.value)
                        val cX = historyX(curr.progress)
                        val cY = mapY(curr.value)

                        val midX = (pX + cX) / 2f

                        val segPath = Path().apply {
                            moveTo(pX, pY)
                            cubicTo(midX, pY, midX, cY, cX, cY)
                        }
                        drawPath(
                            path = segPath,
                            color = zoneColor((prev.value + curr.value) / 2f),
                            style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }

                    // Current reading point at the right edge (glowing circle with white center); not shown while looking back
                    if (panMinutes == 0) {
                    val lastX = historyX(readings.last().progress)
                    val lastY = mapY(readings.last().value)
                    val lastColor = zoneColor(readings.last().value)
                    drawCircle(color = lastColor, radius = 10f, center = Offset(lastX, lastY))
                    drawCircle(color = Color.White, radius = 4.5f, center = Offset(lastX, lastY))
                    }
                }

                // 5. Treatments. The size of a dose shows in everything: a bigger dose has a longer and thicker stick, a
                // bigger dot and a bigger number. The number stands to the left of the stick (to the right when there is no
                // room on the left), so it does not sit on the dot. Big doses are placed first; a number that would touch
                // another number or stick moves up or down, or the stick gets longer.
                run {
                    if (treatments.isEmpty() || readings.isEmpty()) return@run
                    val stickAlpha = 0.6f
                    val dotAlpha = 0.75f
                    val textAlpha = 0.85f
                    // Every dose is shown on its own; only carbs of the same minute are added together.
                    val merged = ArrayList<TreatmentPoint>()
                    for (t in treatments.sortedBy { it.progress }) {
                        val last = merged.lastOrNull()
                        if (t.isCarb && last != null && last.isCarb && last.timestamp == t.timestamp) {
                            merged[merged.lastIndex] = last.copy(amount = last.amount + t.amount, label = formatTreatmentLabel(true, last.amount + t.amount))
                        } else {
                            merged.add(t.copy(label = formatTreatmentLabel(t.isCarb, t.amount)))
                        }
                    }
                    val bolusMax = merged.filter { !it.isCarb }.maxOfOrNull { it.amount }?.coerceAtLeast(0.1f) ?: 0.1f
                    val carbMax = merged.filter { it.isCarb }.maxOfOrNull { it.amount }?.coerceAtLeast(1f) ?: 1f
                    val labelPaint = Paint().apply {
                        textAlign = Paint.Align.LEFT
                        isAntiAlias = true
                        isFakeBoldText = true
                    }
                    val labelBackground = Paint().apply {
                        isAntiAlias = true
                        color = android.graphics.Color.argb(220, 15, 23, 42)
                    }
                    val labels = ArrayList<Rect>()
                    val sticks = ArrayList<Rect>()
                    var sideFlip = false
                    fun inside(r: Rect) = r.left >= 0f && r.right <= chartW && r.top >= 0f && r.bottom <= chartH
                    for (t in merged.sortedByDescending { d -> if (d.isCarb) d.amount / carbMax else d.amount / bolusMax }) {
                        val tx = historyX(t.progress)
                        val bg = readings.minByOrNull { abs(it.progress - t.progress) }?.value ?: continue
                        val dotY = mapY(bg)
                        val color = (if (t.isCarb) Color(0xFFF59E0B) else Color(0xFF38BDF8))
                        val f = kotlin.math.sqrt((if (t.isCarb) t.amount / carbMax else t.amount / bolusMax).coerceIn(0.02f, 1f)).coerceIn(0.2f, 1f)
                        val dotR = if (t.isCarb) 5f + 7f * f else 5.5f
                        val stickW = if (t.isCarb) 2f + 6f * f else 2.5f
                        labelPaint.textSize = 19f
                        val w = labelPaint.measureText(t.label)
                        val th = labelPaint.textSize + 4f
                        if (!t.isCarb) {
                            // SMB: a compact triangle touches the glucose curve. Neighbouring markers alternate
                            // above/below the curve, keeping the chart clear while preserving every event.
                            sideFlip = !sideFlip
                            val triangleH = 14f
                            val triangleHalfW = 7f
                            val above = sideFlip
                            val baseY = if (above) dotY - triangleH else dotY + triangleH
                            val triangle = Path().apply {
                                moveTo(tx, dotY)
                                lineTo(tx - triangleHalfW, baseY)
                                lineTo(tx + triangleHalfW, baseY)
                                close()
                            }
                            drawPath(triangle, color.copy(alpha = 0.9f))

                            val labelTop = if (above) baseY - th - 7f else baseY + 7f
                            val raw = Rect(tx - w / 2f, labelTop, tx + w / 2f, labelTop + th)
                            val r = Rect(raw.left - 4f, raw.top - 3f, raw.right + 4f, raw.bottom + 3f)
                            if (inside(r) && labels.none { it.overlaps(r) }) {
                                labels.add(r)
                                labelPaint.color = android.graphics.Color.argb((textAlpha * 255).toInt(), 56, 189, 248)
                                drawContext.canvas.nativeCanvas.drawRoundRect(r.left, r.top, r.right, r.bottom, 5f, 5f, labelBackground)
                                drawContext.canvas.nativeCanvas.drawText(t.label, r.left + 4f, r.bottom - 7f, labelPaint)
                            }
                            continue
                        }
                        // Carb markers keep their amount-scaled height.
                        val baseH = if (t.isCarb) 20f + 90f * f else 52f
                        val tipY = dotY - baseH
                        var spot: Rect? = null
                        sideFlip = !sideFlip
                        run {
                            val ty = tipY
                            val left = Rect(tx - dotR - 4f - w, ty - th / 2f, tx - dotR - 4f, ty + th / 2f)
                            val right = Rect(tx + dotR + 4f, ty - th / 2f, tx + dotR + 4f + w, ty + th / 2f)
                            for (r in if (sideFlip) listOf(left, right) else listOf(right, left)) {
                                val padded = Rect(r.left - 4f, r.top - 3f, r.right + 4f, r.bottom + 3f)
                                if (inside(padded) && labels.none { it.overlaps(padded) } && sticks.none { it.overlaps(padded) }) {
                                    spot = padded; break
                                }
                            }
                        }
                        drawLine(color = color.copy(alpha = stickAlpha), start = Offset(tx, dotY), end = Offset(tx, tipY), strokeWidth = stickW, cap = StrokeCap.Round)
                        drawCircle(color = color.copy(alpha = dotAlpha), radius = dotR, center = Offset(tx, tipY))
                        sticks.add(Rect(tx - stickW, minOf(dotY, tipY) - dotR, tx + stickW, maxOf(dotY, tipY) + dotR))
                        val r = spot ?: continue
                        labels.add(r)
                        labelPaint.color = android.graphics.Color.argb(
                            (textAlpha * 255).toInt(),
                            if (t.isCarb) 245 else 56,
                            if (t.isCarb) 158 else 189,
                            if (t.isCarb) 11 else 248
                        )
                        drawContext.canvas.nativeCanvas.drawRoundRect(r.left, r.top, r.right, r.bottom, 5f, 5f, labelBackground)
                        drawContext.canvas.nativeCanvas.drawText(t.label, r.left + 4f, r.bottom - 7f, labelPaint)
                    }
                }

                // 5b. BG predictions (extend past "now" into the fixed prediction horizon)
                if (predictions.isNotEmpty()) {
                    fun predictionColor(type: PredictionType): Color = when (type) {
                        PredictionType.IOB   -> Color(0xFF64B5F6)
                        PredictionType.COB   -> Color(0xFFFFB74D)
                        PredictionType.A_COB -> Color(0xFFFFB74D).copy(alpha = 0.5f)
                        PredictionType.UAM   -> Color(0xFFE6D39A)
                        PredictionType.ZT    -> Color(0xFF4DD4D4)
                    }

                    predictions.groupBy { it.type }.forEach { (type, points) ->
                        val sorted = points.sortedBy { it.progress }
                        if (sorted.size >= 2) {
                            for (i in 1 until sorted.size) {
                                val prev = sorted[i - 1]
                                val curr = sorted[i]
                                drawLine(
                                    color = predictionColor(type),
                                    start = Offset(predictionX(prev.progress), mapY(prev.value)),
                                    end = Offset(predictionX(curr.progress), mapY(curr.value)),
                                    // Same 6f width as the solid history curve above (Stroke(width = 6f, ...))
                                    // — the dashed line should read as a continuation of the same stroke,
                                    // not a thinner overlay.
                                    strokeWidth = 6f,
                                    cap = StrokeCap.Round,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                                )
                            }
                        }
                        sorted.forEach { point ->
                            drawCircle(
                                color = predictionColor(type),
                                radius = 4f,
                                center = Offset(predictionX(point.progress), mapY(point.value))
                            )
                        }
                    }
                }

                // 6. Hour labels on the X-axis (aligned with the IOB chart)
                val hourPaint = Paint().apply {
                    color = if (isDark) android.graphics.Color.argb(130, 255, 255, 255) else android.graphics.Color.argb(150, 71, 85, 105)
                    textSize = 26f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                drawTimeAxisLabels(timeRangeHours, panMinutes, chartW, historyFraction, h - 6f, hourPaint)

                // 7. Touched-point indicator (dashed line + circle)
                touchedReading?.let { pt ->
                    val ptX = historyX(pt.progress)
                    val ptY = mapY(pt.value)

                    drawLine(
                        color = Color.White.copy(alpha = 0.4f),
                        start = Offset(ptX, 0f),
                        end = Offset(ptX, chartH),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                    drawCircle(color = Color(0xFFF59E0B), radius = 10f, center = Offset(ptX, ptY))
                    drawCircle(color = Color.White, radius = 4f, center = Offset(ptX, ptY))
                }
                touchedPrediction?.let { pt ->
                    val ptX = predictionX(pt.progress)
                    val ptY = mapY(pt.value)
                    drawCircle(color = Color(0xFFE6D39A), radius = 10f, center = Offset(ptX, ptY))
                    drawCircle(color = Color.White, radius = 4f, center = Offset(ptX, ptY))
                }
            }
        }
    }

    // Treatment details dialog (SMB/Bolus/Carbs)
    selectedTreatment?.let { t ->
        val timestamp = t.timestamp
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val dateStr = String.format(Locale.US, "%02d/%02d/%04d", cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
        val timeStr = String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))

        AlertDialog(
            onDismissRequest = { selectedTreatment = null },
            confirmButton = {
                TextButton(onClick = { selectedTreatment = null }) {
                    Text("OK", color = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7))
                }
            },
            title = {
                Text(
                    text = "Treatment Details",
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Type:", color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B), fontSize = 13.sp)
                        Text(
                            text = if (t.isCarb) "Carbs" else "Bolus",
                            color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Date:", color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B), fontSize = 13.sp)
                        Text(dateStr, color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Time:", color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B), fontSize = 13.sp)
                        Text(timeStr, color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Amount:", color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B), fontSize = 13.sp)
                        Text(
                            text = t.label,
                            color = if (t.isCarb) Color(0xFF22C55E) else Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            },
            containerColor = if (isDark) Color(0xCC0F172A) else Color(0xF2FFFFFF),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

// ==========================================
// IOB (ACTIVE INSULIN) CHART
// ==========================================
@Composable
internal fun IobChartCard(
    iobReadings: List<IobReadingPoint>,
    currentIob: Float,
    timeRangeHours: Float,
    historyFraction: Float,
    isDark: Boolean,
    predictions: List<IobReadingPoint> = emptyList(),
    /** Shared position (0..1 along the history) of the finger on this or the BG chart; null when not touched. */
    scrubProgress: Float? = null,
    onScrub: (Float?) -> Unit = {},
    /** Finger drag along the time axis, as a fraction of the visible history (positive: finger moved right). */
    onPanBy: (Float) -> Unit = {},
    /** How far the window is moved back from now, in minutes; the time marks follow it. */
    panMinutes: Int = 0,
    /** True when the chart sits inside the combined card: no own glass container, fixed inner height. */
    embedded: Boolean = false,
    /** Caption, line colour and number format; the same chart is used for COB. */
    title: String = "IOB",
    accent: Color = Color(0xFF38BDF8),
    valueFormat: String = "%.2f U",
) {
    val touchedIob: IobReadingPoint? = scrubProgress?.takeIf { it <= 1f }?.let { p -> iobReadings.minByOrNull { abs(it.progress - p) } }
    val forecastQ: Float? = scrubProgress?.takeIf { it > 1f && historyFraction < 1f }
        ?.let { p -> ((p - 1f) * historyFraction / (1f - historyFraction)).coerceIn(0f, 1f) }
    val touchedIobForecast: IobReadingPoint? = forecastQ?.let { q -> predictions.minByOrNull { abs(it.progress - q) } }

    ChartShell(embedded = embedded, isDark = isDark, height = 120.dp) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                )
                if (touchedIob != null) {
                    val pt = touchedIob!!
                    val now = LocalTime.now()
                    val hoursAgo = timeRangeHours * (1f - pt.progress)
                    val pointTime = now.minusMinutes((hoursAgo * 60).toLong() + panMinutes)
                    Text(
                        text = String.format(Locale.US, "%02d:%02d  $valueFormat", pointTime.hour, pointTime.minute, pt.iob),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                } else if (touchedIobForecast != null) {
                    val horizonMin = timeRangeHours * 60f * (1f - historyFraction) / historyFraction
                    val at = LocalTime.now().plusMinutes(((forecastQ ?: 0f) * horizonMin).toLong())
                    Text(
                        text = String.format(Locale.US, "%02d:%02d  $valueFormat", at.hour, at.minute, touchedIobForecast.iob),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                } else {
                    Text(
                        text = String.format(Locale.US, valueFormat, currentIob),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val historyNow by rememberUpdatedState(historyFraction)
            val scrubNow by rememberUpdatedState(onScrub)
            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val chartW = size.width - 65f
                        val p = (down.position.x / (chartW * historyNow)).coerceIn(0f, 1f / historyNow)
                        scrubNow(snapToNow(p))

                        // The finger moves the marker line along the chart (the time is moved with the bar under it).
                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            if (event.changes.any { it.pressed }) {
                                // Two fingers are a zoom: the marker line stays where it is.
                                if (event.changes.count { it.pressed } == 1) {
                                    val px = (event.changes.first().position.x / (chartW * historyNow)).coerceIn(0f, 1f / historyNow)
                                    scrubNow(snapToNow(px))
                                    event.changes.first().consume()
                                }
                            } else {
                                break
                            }
                        }
                    }
                }
            ) {
                val w = size.width
                val h = size.height
                val paddingRight = 65f
                val chartW = w - paddingRight
                val chartH = h - 28f

                fun historyX(progress: Float): Float = progress * chartW * historyFraction
                fun predictionX(progress: Float): Float = (historyFraction + progress * (1f - historyFraction)) * chartW

                scrubProgress?.let { p ->
                    val x = historyX(p)
                    drawLine(
                        color = if (isDark) Color(0x99FFFFFF) else Color(0x99334155),
                        start = Offset(x, 0f),
                        end = Offset(x, chartH),
                        strokeWidth = 2f
                    )
                }

                val peakIob = maxOf(
                    iobReadings.maxByOrNull { it.iob }?.iob ?: 0f,
                    predictions.maxByOrNull { it.iob }?.iob ?: 0f,
                    currentIob
                ).coerceAtLeast(0f)
                val maxIob = when {
                    peakIob == 0f -> 1f
                    peakIob % 1f == 0f -> peakIob + 1f
                    else -> ceil(peakIob)
                }

                fun mapY(iob: Float): Float {
                    val clamped = iob.coerceIn(0f, maxIob)
                    return chartH - (clamped / maxIob) * chartH
                }

                // Baseline at 0U
                drawLine(
                    color = if (isDark) Color(0x1FFFFFFF) else Color(0x1F000000),
                    start = Offset(0f, chartH),
                    end = Offset(chartW, chartH),
                    strokeWidth = 1f
                )

                // Top line at the scale value
                drawLine(
                    color = if (isDark) Color(0x1FFFFFFF) else Color(0x1F000000),
                    start = Offset(0f, 0f),
                    end = Offset(chartW, 0f),
                    strokeWidth = 1f
                )

                drawTimeAxisGrid(timeRangeHours, panMinutes, chartW, historyFraction, chartH, if (isDark) Color(0x14FFFFFF) else Color(0x0D000000))

                val textPaint = Paint().apply {
                    color = if (isDark) android.graphics.Color.argb(160, 255, 255, 255) else android.graphics.Color.argb(180, 71, 85, 105)
                    textSize = 28f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                val scaleCenterX = chartW + paddingRight / 2f
                drawContext.canvas.nativeCanvas.drawText(
                    String.format(Locale.US, "%.1f", maxIob),
                    scaleCenterX,
                    12f,
                    textPaint
                )
                drawContext.canvas.nativeCanvas.drawText("0", scaleCenterX, chartH + 4f, textPaint)

                if (iobReadings.size >= 2) {
                    val path = Path()
                    val areaPath = Path()

                    val firstX = historyX(iobReadings.first().progress)
                    val firstY = mapY(iobReadings.first().iob)

                    path.moveTo(firstX, firstY)
                    areaPath.moveTo(firstX, chartH)
                    areaPath.lineTo(firstX, firstY)

                    for (i in 1 until iobReadings.size) {
                        val prev = iobReadings[i - 1]
                        val curr = iobReadings[i]

                        val pX = historyX(prev.progress)
                        val pY = mapY(prev.iob)
                        val cX = historyX(curr.progress)
                        val cY = mapY(curr.iob)

                        val midX = (pX + cX) / 2f
                        path.cubicTo(midX, pY, midX, cY, cX, cY)
                        areaPath.cubicTo(midX, pY, midX, cY, cX, cY)
                    }

                    val lastX = historyX(iobReadings.last().progress)
                    val lastY = mapY(iobReadings.last().iob)
                    areaPath.lineTo(lastX, chartH)
                    areaPath.close()

                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                accent.copy(alpha = if (isDark) 0.35f else 0.22f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = chartH
                        )
                    )

                    drawPath(
                        path = path,
                        color = accent,
                        style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    drawCircle(color = accent, radius = 8f, center = Offset(lastX, lastY))
                    drawCircle(color = Color.White, radius = 3.5f, center = Offset(lastX, lastY))
                }

                // IOB projection (no further treatments assumed) — same 5f stroke width as the solid
                // history curve above, just dashed, so the two curves read as one continuous line in
                // design terms, only the dash pattern changes at the boundary.
                if (predictions.size >= 2) {
                    val sorted = predictions.sortedBy { it.progress }
                    for (i in 1 until sorted.size) {
                        val prev = sorted[i - 1]
                        val curr = sorted[i]
                        drawLine(
                            color = accent,
                            start = Offset(predictionX(prev.progress), mapY(prev.iob)),
                            end = Offset(predictionX(curr.progress), mapY(curr.iob)),
                            strokeWidth = 5f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        )
                    }
                }

                // Hour labels on the X-axis (aligned with the BG chart)
                val hourPaint = Paint().apply {
                    color = if (isDark) android.graphics.Color.argb(130, 255, 255, 255) else android.graphics.Color.argb(150, 71, 85, 105)
                    textSize = 26f
                    textAlign = Paint.Align.CENTER
                    isAntiAlias = true
                }
                drawTimeAxisLabels(timeRangeHours, panMinutes, chartW, historyFraction, h - 6f, hourPaint)

                // Touched IOB point indicator (dashed line + circle)
                touchedIob?.let { pt ->
                    val ptX = historyX(pt.progress)
                    val ptY = mapY(pt.iob)

                    drawLine(
                        color = Color.White.copy(alpha = 0.4f),
                        start = Offset(ptX, 0f),
                        end = Offset(ptX, chartH),
                        strokeWidth = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                    )
                    drawCircle(color = accent, radius = 10f, center = Offset(ptX, ptY))
                    drawCircle(color = Color.White, radius = 4f, center = Offset(ptX, ptY))
                }
                touchedIobForecast?.let { pt ->
                    val ptX = predictionX(pt.progress)
                    val ptY = mapY(pt.iob)
                    drawCircle(color = accent, radius = 10f, center = Offset(ptX, ptY))
                    drawCircle(color = Color.White, radius = 4f, center = Offset(ptX, ptY))
                }
            }
        }
    }
}

/** Own glass card for a chart on its own, or just the sized area when the chart is part of the combined card. */
@Composable
private fun ChartShell(embedded: Boolean, isDark: Boolean, height: Dp, content: @Composable () -> Unit) {
    if (embedded) {
        Box(modifier = Modifier.fillMaxWidth().height(height)) { content() }
    } else {
        GlassContainer(
            isDark = isDark,
            modifier = Modifier.fillMaxWidth().height(if (height == 215.dp) 235.dp else 145.dp)
        ) { content() }
    }
}

/**
 * Lets the plot area dissolve at the left and right edge instead of being cut off hard. Only the plot is faded: the
 * band at the bottom with the hour numbers and the axis numbers on the right stay fully readable.
 */
internal fun Modifier.edgeFade(bottomBandPx: Float, fadeWidthPx: Float = 100f, rightAxisPx: Float = 65f): Modifier =
    this
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val plotRight = size.width - rightAxisPx
            val plotHeight = size.height - bottomBandPx
            if (plotRight > fadeWidthPx * 2 && plotHeight > 0f) {
                // Smooth ease-in curve (not a straight ramp): the picture melts away gently towards the edge.
                val ramp = arrayOf(
                    0f to Color.Black.copy(alpha = 0f),
                    0.2f to Color.Black.copy(alpha = 0.06f),
                    0.4f to Color.Black.copy(alpha = 0.22f),
                    0.6f to Color.Black.copy(alpha = 0.5f),
                    0.8f to Color.Black.copy(alpha = 0.82f),
                    1f to Color.Black,
                )
                val rampReversed = arrayOf(
                    0f to Color.Black,
                    0.2f to Color.Black.copy(alpha = 0.82f),
                    0.4f to Color.Black.copy(alpha = 0.5f),
                    0.6f to Color.Black.copy(alpha = 0.22f),
                    0.8f to Color.Black.copy(alpha = 0.06f),
                    1f to Color.Black.copy(alpha = 0f),
                )
                drawRect(
                    brush = Brush.horizontalGradient(colorStops = ramp, startX = 0f, endX = fadeWidthPx),
                    topLeft = Offset(0f, 0f),
                    size = Size(fadeWidthPx, plotHeight),
                    blendMode = BlendMode.DstIn
                )
                drawRect(
                    brush = Brush.horizontalGradient(colorStops = rampReversed, startX = plotRight - fadeWidthPx, endX = plotRight),
                    topLeft = Offset(plotRight - fadeWidthPx, 0f),
                    size = Size(fadeWidthPx, plotHeight),
                    blendMode = BlendMode.DstIn
                )
            }
        }

/** Short text for a dose: insulin as a plain number, carbs with a "g". */
private fun formatTreatmentLabel(isCarb: Boolean, amount: Float): String =
    if (isCarb) "${amount.roundToInt()}g" else String.format(Locale.US, if (amount < 1f) "%.2f" else "%.1f", amount).trimEnd('0').trimEnd('.')

/** Step between time marks for a window of [rangeMinutes]. */
private fun axisStepMinutes(rangeMinutes: Int): Int = when {
    rangeMinutes <= 20 -> 2
    rangeMinutes <= 30 -> 5
    rangeMinutes <= 60 -> 10
    rangeMinutes <= 180 -> 30
    rangeMinutes <= 360 -> 60
    rangeMinutes <= 720 -> 120
    else -> 180
}

/** Calls [block] with the position (0..1 along the history) and the clock minute for every time mark in the window. */
private inline fun forEachTimeMark(timeRangeHours: Float, panMinutes: Int, block: (progress: Float, minuteOfDay: Int) -> Unit) {
    val rangeMin = (timeRangeHours * 60f).roundToInt()
    val step = axisStepMinutes(rangeMin)
    val now = LocalTime.now()
    val nowMin = ((now.hour * 60 + now.minute - panMinutes) % 1440 + 1440) % 1440
    var ago = nowMin % step
    while (ago <= rangeMin) {
        val progress = 1f - ago.toFloat() / rangeMin
        block(progress, ((nowMin - ago) % 1440 + 1440) % 1440)
        ago += step
    }
}

private fun DrawScope.drawTimeAxisGrid(timeRangeHours: Float, panMinutes: Int, chartW: Float, historyFraction: Float, chartH: Float, color: Color) {
    forEachTimeMark(timeRangeHours, panMinutes) { progress, _ ->
        val x = progress * chartW * historyFraction
        drawLine(color = color, start = Offset(x, 0f), end = Offset(x, chartH), strokeWidth = 0.8f)
    }
}

private fun DrawScope.drawTimeAxisLabels(timeRangeHours: Float, panMinutes: Int, chartW: Float, historyFraction: Float, y: Float, paint: Paint) {
    val wholeHours = axisStepMinutes((timeRangeHours * 60f).roundToInt()) >= 60
    forEachTimeMark(timeRangeHours, panMinutes) { progress, minute ->
        val x = progress * chartW * historyFraction
        // Skip a label that would be cut by the left edge.
        if (x < 20f) return@forEachTimeMark
        val label = if (wholeHours) String.format(Locale.US, "%02d", minute / 60) else String.format(Locale.US, "%02d:%02d", minute / 60, minute % 60)
        drawContext.canvas.nativeCanvas.drawText(label, x, y, paint)
    }
}
