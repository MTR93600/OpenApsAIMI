package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.time.Clock

/** Y-axis value labels: `Paint.textSize = 28f` (pixels) on `dev_OAPSAIMI`. */
private const val AXIS_VALUE_TEXT_PX = 28f

/** X-axis hour labels: `Paint.textSize = 26f` (pixels) on `dev_OAPSAIMI`. */
private const val AXIS_HOUR_TEXT_PX = 26f

/**
 * Draws [text] centred on [centerX] with its baseline at [baselineY], the way
 * `android.graphics.Canvas.drawText` with `Paint.Align.CENTER` does. [textSizePx] is in pixels, like
 * `Paint.textSize`: it is turned into sp here and multiplied back by the same density and font scale
 * when drawn, so the glyphs end up exactly [textSizePx] pixels tall, as on `dev_OAPSAIMI`.
 */
private fun DrawScope.drawCenteredText(
    textMeasurer: TextMeasurer,
    text: String,
    centerX: Float,
    baselineY: Float,
    color: Color,
    textSizePx: Float,
) {
    val layout = textMeasurer.measure(
        text = text,
        style = TextStyle(color = color, fontSize = textSizePx.toSp()),
        density = this,
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(centerX - layout.size.width / 2f, baselineY - layout.firstBaseline),
    )
}

@Composable
internal fun BgChartCard(
    readings: List<BgReadingPoint>,
    treatments: List<TreatmentPoint>,
    timeRangeHours: Int,
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
) {
    var touchedReading by remember { mutableStateOf<BgReadingPoint?>(null) }
    var selectedTreatment by remember { mutableStateOf<TreatmentPoint?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val timeZone = TimeZone.currentSystemDefault()

    // When new data arrives, reset the drag back to the current reading
    LaunchedEffect(readings) {
        touchedReading = null
        selectedTreatment = null
    }

    GlassContainer(
        isDark = isDark,
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(CoreUiStrings.bg_label),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                )
                val pt = touchedReading
                if (pt != null) {
                    val hoursAgo = timeRangeHours * (1f - pt.progress)
                    val timeStr = glassClockLabel(Clock.System.now().toEpochMilliseconds(), (hoursAgo * 60).toLong(), timeZone)
                    val nearbyTreatment = treatments.minByOrNull { abs(it.progress - pt.progress) }
                    val bgStr = formatValue(pt.value)
                    val bgColor = when {
                        pt.value < lowLine -> Color(0xFFEF4444)
                        pt.value > highLine -> Color(0xFFF59E0B)
                        else -> Color(0xFF22C55E)
                    }
                    if (nearbyTreatment != null && abs(nearbyTreatment.progress - pt.progress) < 0.025f) {
                        Text(
                            text = stringResource(UiStrings.dashboard_glass_chart_point_treatment, timeStr, nearbyTreatment.label, bgStr),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = bgColor
                        )
                    } else {
                        Text(
                            text = stringResource(UiStrings.dashboard_glass_chart_point, timeStr, bgStr),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = bgColor
                        )
                    }
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

            Spacer(modifier = Modifier.height(4.dp))

            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(readings, treatments, timeRangeHours) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val chartW = size.width - 65f
                        val chartH = size.height - 36f
                        val treatmentY = chartH - 8f
                        val p = (down.position.x / (chartW * historyFraction)).coerceIn(0f, 1f)
                        touchedReading = readings.minByOrNull { abs(it.progress - p) }

                        // Check if the touch landed near a treatment marker (X + Y)
                        val nearTreatment = treatments.minByOrNull { abs(it.progress - p) }
                        selectedTreatment = if (nearTreatment != null
                            && abs(nearTreatment.progress - p) < 0.035f
                            && abs(down.position.y - treatmentY) < 50f
                        ) {
                            nearTreatment
                        } else {
                            null
                        }

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            if (event.changes.any { it.pressed }) {
                                val px = (event.changes.first().position.x / (chartW * historyFraction)).coerceIn(0f, 1f)
                                touchedReading = readings.minByOrNull { abs(it.progress - px) }
                                event.changes.first().consume()
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
                val axisTextColor = if (isDark) Color(0xA0FFFFFF) else Color(0xB4475569)
                val scaleCenterX = chartW + paddingRight / 2f
                drawCenteredText(textMeasurer, formatValue(highLine), scaleCenterX, yHigh + 8f, axisTextColor, AXIS_VALUE_TEXT_PX)
                drawCenteredText(textMeasurer, formatValue(lowLine), scaleCenterX, yLow + 8f, axisTextColor, AXIS_VALUE_TEXT_PX)

                // 3. Vertical time lines and labels
                val timeTicks = 6
                for (i in 0 until timeTicks) {
                    val x = historyX(i.toFloat() / (timeTicks - 1))
                    drawLine(
                        color = if (isDark) Color(0x14FFFFFF) else Color(0x0D000000),
                        start = Offset(x, 0f),
                        end = Offset(x, chartH),
                        strokeWidth = 0.8f
                    )
                }

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
                // (profile rate, or the temp rate while one is running). Confined to the bottom ~25% of the
                // chart height (maxY = maxBasalRateUh * 4) so it never competes visually with the BG curve.
                val maxYBasal = (maxBasalRateUh * 4f).coerceAtLeast(0.01f)
                fun mapBasalY(rateUh: Float): Float {
                    val clamped = rateUh.coerceIn(0f, maxYBasal)
                    return chartH - (clamped / maxYBasal) * chartH
                }

                fun stepPath(points: List<BasalReadingPoint>, endX: Float): Path {
                    val path = Path()
                    if (points.isEmpty()) return path
                    var y = mapBasalY(points.first().rateUh)
                    path.moveTo(historyX(points.first().progress), y)
                    for (i in 1 until points.size) {
                        val nx = historyX(points[i].progress)
                        path.lineTo(nx, y)
                        y = mapBasalY(points[i].rateUh)
                        path.lineTo(nx, y)
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

                    // Current reading point at the right edge (glowing circle with white center)
                    val lastX = historyX(readings.last().progress)
                    val lastY = mapY(readings.last().value)
                    val lastColor = zoneColor(readings.last().value)
                    drawCircle(color = lastColor, radius = 10f, center = Offset(lastX, lastY))
                    drawCircle(color = Color.White, radius = 4.5f, center = Offset(lastX, lastY))
                }

                // 5. Treatment markers (carbs light blue, bolus/SMB dark blue)
                for (t in treatments) {
                    val tx = historyX(t.progress)
                    val ty = chartH - 8f
                    if (t.isCarb) {
                        drawCircle(color = Color(0xFF38BDF8), radius = 10f, center = Offset(tx, ty))
                    } else {
                        drawCircle(color = Color(0xFF0284C7), radius = 10f, center = Offset(tx, ty))
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
                                    // Same 6f width as the solid history curve above, so the dashed line
                                    // reads as a continuation of the same stroke, not a thinner overlay.
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
                val hourTextColor = if (isDark) Color(0x82FFFFFF) else Color(0x96475569)
                val nowEpochMs = Clock.System.now().toEpochMilliseconds()
                for (i in 0 until timeTicks) {
                    val progress = i.toFloat() / (timeTicks - 1)
                    val x = historyX(progress)
                    val hoursAgo = timeRangeHours * (1f - progress)
                    val label = glassHourLabel(nowEpochMs, (hoursAgo * 60).toLong(), timeZone)
                    drawCenteredText(textMeasurer, label, x, h - 6f, hourTextColor, AXIS_HOUR_TEXT_PX)
                }

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
            }
        }
    }

    // Treatment details dialog (SMB/Bolus/Carbs)
    selectedTreatment?.let { t ->
        val dateStr = glassDateLabel(t.timestamp, timeZone)
        val timeStr = glassClockLabel(t.timestamp, timeZone)
        val labelColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
        val valueColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)

        AlertDialog(
            onDismissRequest = { selectedTreatment = null },
            confirmButton = {
                TextButton(onClick = { selectedTreatment = null }) {
                    Text(stringResource(CoreUiStrings.ok), color = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7))
                }
            },
            title = {
                Text(
                    text = stringResource(UiStrings.dashboard_glass_treatment_details_title),
                    fontWeight = FontWeight.Bold,
                    color = valueColor
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TreatmentDetailRow(
                        label = stringResource(UiStrings.dashboard_glass_treatment_type_label),
                        value = stringResource(if (t.isCarb) UiStrings.aaps_carbs_icon_label else UiStrings.aaps_bolus_icon_label),
                        labelColor = labelColor,
                        valueColor = valueColor
                    )
                    TreatmentDetailRow(
                        label = stringResource(UiStrings.dashboard_glass_treatment_date_label),
                        value = dateStr,
                        labelColor = labelColor,
                        valueColor = valueColor
                    )
                    TreatmentDetailRow(
                        label = stringResource(UiStrings.dashboard_glass_treatment_time_label),
                        value = timeStr,
                        labelColor = labelColor,
                        valueColor = valueColor
                    )
                    TreatmentDetailRow(
                        label = stringResource(UiStrings.dashboard_glass_treatment_amount_label),
                        value = t.label,
                        labelColor = labelColor,
                        valueColor = if (t.isCarb) Color(0xFF22C55E) else Color(0xFF38BDF8)
                    )
                }
            },
            containerColor = if (isDark) Color(0xCC0F172A) else Color(0xF2FFFFFF),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun TreatmentDetailRow(label: String, value: String, labelColor: Color, valueColor: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = labelColor, fontSize = 13.sp)
        Text(text = value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// ==========================================
// IOB (ACTIVE INSULIN) CHART
// ==========================================
@Composable
internal fun IobChartCard(
    iobReadings: List<IobReadingPoint>,
    currentIob: Float,
    timeRangeHours: Int,
    historyFraction: Float,
    isDark: Boolean,
    predictions: List<IobReadingPoint> = emptyList(),
) {
    var touchedIob by remember { mutableStateOf<IobReadingPoint?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val timeZone = TimeZone.currentSystemDefault()

    // When new data arrives, reset the drag back to the current reading
    LaunchedEffect(iobReadings) {
        touchedIob = null
    }

    GlassContainer(
        isDark = isDark,
        modifier = Modifier
            .fillMaxWidth()
            .height(145.dp)
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(CoreUiStrings.iob),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                )
                val pt = touchedIob
                if (pt != null) {
                    val hoursAgo = timeRangeHours * (1f - pt.progress)
                    val timeStr = glassClockLabel(Clock.System.now().toEpochMilliseconds(), (hoursAgo * 60).toLong(), timeZone)
                    Text(
                        text = stringResource(
                            UiStrings.dashboard_glass_chart_point,
                            timeStr,
                            stringResource(InterfacesStrings.format_insulin_units, pt.iob.toDouble())
                        ),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                } else {
                    Text(
                        text = stringResource(InterfacesStrings.format_insulin_units, currentIob.toDouble()),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF38BDF8)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Canvas(modifier = Modifier
                .fillMaxSize()
                .pointerInput(iobReadings, timeRangeHours, historyFraction) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val chartW = size.width - 65f
                        val p = (down.position.x / (chartW * historyFraction)).coerceIn(0f, 1f)
                        touchedIob = iobReadings.minByOrNull { abs(it.progress - p) }

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Main)
                            if (event.changes.any { it.pressed }) {
                                val px = (event.changes.first().position.x / (chartW * historyFraction)).coerceIn(0f, 1f)
                                touchedIob = iobReadings.minByOrNull { abs(it.progress - px) }
                                event.changes.first().consume()
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

                // Vertical grid lines
                for (i in 0 until 6) {
                    val x = historyX(i.toFloat() / 5f)
                    drawLine(
                        color = if (isDark) Color(0x14FFFFFF) else Color(0x0D000000),
                        start = Offset(x, 0f),
                        end = Offset(x, chartH),
                        strokeWidth = 0.8f
                    )
                }

                val axisTextColor = if (isDark) Color(0xA0FFFFFF) else Color(0xB4475569)
                val scaleCenterX = chartW + paddingRight / 2f
                drawCenteredText(textMeasurer, glassFixedDecimals(maxIob, 1), scaleCenterX, 12f, axisTextColor, AXIS_VALUE_TEXT_PX)
                drawCenteredText(textMeasurer, "0", scaleCenterX, chartH + 4f, axisTextColor, AXIS_VALUE_TEXT_PX)

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
                                Color(0xFF38BDF8).copy(alpha = if (isDark) 0.35f else 0.22f),
                                Color.Transparent
                            ),
                            startY = 0f,
                            endY = chartH
                        )
                    )

                    drawPath(
                        path = path,
                        color = Color(0xFF38BDF8),
                        style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    drawCircle(color = Color(0xFF38BDF8), radius = 8f, center = Offset(lastX, lastY))
                    drawCircle(color = Color.White, radius = 3.5f, center = Offset(lastX, lastY))
                }

                // IOB projection (no further treatments assumed) — same 5f stroke width as the solid
                // history curve above, just dashed, so the two curves read as one continuous line.
                if (predictions.size >= 2) {
                    val sorted = predictions.sortedBy { it.progress }
                    for (i in 1 until sorted.size) {
                        val prev = sorted[i - 1]
                        val curr = sorted[i]
                        drawLine(
                            color = Color(0xFF38BDF8),
                            start = Offset(predictionX(prev.progress), mapY(prev.iob)),
                            end = Offset(predictionX(curr.progress), mapY(curr.iob)),
                            strokeWidth = 5f,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                        )
                    }
                }

                // Hour labels on the X-axis (aligned with the BG chart)
                val hourTextColor = if (isDark) Color(0x82FFFFFF) else Color(0x96475569)
                val nowEpochMs = Clock.System.now().toEpochMilliseconds()
                for (i in 0 until 6) {
                    val progress = i.toFloat() / 5f
                    val x = historyX(progress)
                    val hoursAgo = timeRangeHours * (1f - progress)
                    val label = glassHourLabel(nowEpochMs, (hoursAgo * 60).toLong(), timeZone)
                    drawCenteredText(textMeasurer, label, x, h - 6f, hourTextColor, AXIS_HOUR_TEXT_PX)
                }

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
                    drawCircle(color = Color(0xFF38BDF8), radius = 10f, center = Offset(ptX, ptY))
                    drawCircle(color = Color.White, radius = 4f, center = Offset(ptX, ptY))
                }
            }
        }
    }
}
