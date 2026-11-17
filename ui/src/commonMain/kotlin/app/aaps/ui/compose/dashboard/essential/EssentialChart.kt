package app.aaps.ui.compose.dashboard.essential

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The series of the chart, in the user's glucose unit, straight from `GraphViewModel` (the same data
 * Glass draws).
 *
 * @property prediction one prediction curve (the one the 60 min value comes from), or empty
 * @property scaleFloor lowest value the vertical axis always shows (40 mg/dL in the user's unit)
 * @property scaleCeiling highest value the vertical axis always shows (250 mg/dL in the user's unit)
 */
data class EssentialChartData(
    val bg: List<BgDataPoint>,
    val prediction: List<BgDataPoint>,
    val basal: List<GraphDataPoint>,
    val boluses: List<BolusGraphPoint>,
    val lowMark: Double,
    val highMark: Double,
    val scaleFloor: Double,
    val scaleCeiling: Double,
)

/** What the readout shows for the time under the finger, already formatted. */
data class EssentialReadout(val time: String, val bg: String, val basal: String, val smb: String?)

/** Ranges the chart offers, in hours. The first is the default. */
val ESSENTIAL_RANGES = listOf(3, 6, 12, 24)

private const val STEP_MS = 5L * 60L * 1000L
private const val GAP_MS = 15L * 60L * 1000L
private const val BASAL_BARS = 36

/** Height of the drawing, glucose and basal bars together. Small enough that the whole home fits a phone screen. */
private val CHART_HEIGHT = 136.dp

/**
 * The glucose chart in its card: quiet target band, the curve over an area in the colour of the current
 * situation, a short dotted prediction after now, delivered basal as bars at the bottom, SMB as dots
 * sized by dose. No COB.
 *
 * A cursor always stands on a time, by default now, and the readout under the chart shows that time
 * (glucose, basal decided, SMB delivered). A horizontal drag moves it; on release it slides back to now.
 * The arrow buttons and the left/right keys move it by 5 minutes, for a hand or a screen reader that
 * cannot slide. The chart takes horizontal drags only, so the page still scrolls.
 *
 * @param tint the colour of the current situation, for the area under the curve
 */
@Composable
fun EssentialChart(
    data: EssentialChartData,
    nowMs: Long,
    rangeHours: Int,
    onRangeChange: (Int) -> Unit,
    tint: Color,
    readoutAt: @Composable (Long) -> EssentialReadout,
    timeLabel: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val general = AapsTheme.generalColors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val scope = rememberCoroutineScope()
    val fromMs = nowMs - rangeHours * 60L * 60L * 1000L
    // Only the next 60 min of the prediction, in its own space after now
    val prediction = data.prediction
        .filter { it.timestamp in nowMs..nowMs + EssentialSeries.PREDICTION_HORIZON_MS }
        .sortedBy { it.timestamp }
    val toMs = nowMs + if (prediction.size > 1) EssentialSeries.PREDICTION_HORIZON_MS else 0L
    val nowFraction = ((nowMs - fromMs).toFloat() / (toMs - fromMs)).coerceIn(0.01f, 1f)
    // Null: the cursor stands on now. While it slides back after a drag it goes through this animation.
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val slideBack = remember { Animatable(0f) }
    val cursorMs = scrubMs ?: nowMs

    fun stepBack() { scrubMs = (cursorMs - STEP_MS).coerceAtLeast(fromMs) }
    fun stepForward() { scrubMs = (cursorMs + STEP_MS).takeIf { it < nowMs } }

    Surface(modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = AapsTheme.spacing.large, vertical = AapsTheme.spacing.medium)) {
            // Title and range
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(UiStrings.essential_chart_title, rangeHours),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = muted,
                    modifier = Modifier.weight(1f),
                )
                ESSENTIAL_RANGES.forEach { hours ->
                    val selected = hours == rangeHours
                    Text(
                        stringResource(UiStrings.dashboard_glass_range_hours, hours),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary else muted,
                        modifier = Modifier
                            .clickable { onRangeChange(hours) }
                            .padding(horizontal = AapsTheme.spacing.small, vertical = AapsTheme.spacing.extraSmall),
                    )
                }
            }

            val readout = readoutAt(cursorMs)
            val readoutText = stringResource(
                UiStrings.essential_readout_description,
                readout.time, readout.bg, readout.basal, readout.smb ?: stringResource(UiStrings.essential_no_smb),
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT)
                    .padding(top = AapsTheme.spacing.small)
                    .clipToBounds()
                    .semantics { contentDescription = readoutText }
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft  -> { stepBack(); true }
                            Key.DirectionRight -> { stepForward(); true }
                            else               -> false
                        }
                    }
                    .pointerInput(fromMs, nowMs, toMs) {
                        fun timeAt(x: Float): Long = (fromMs + (x / size.width) * (toMs - fromMs)).toLong().coerceIn(fromMs, nowMs)
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> scope.launch { slideBack.snapTo(0f) }; scrubMs = timeAt(offset.x) },
                            onHorizontalDrag = { change, _ -> change.consume(); scrubMs = timeAt(change.position.x) },
                            onDragEnd = {
                                val from = scrubMs ?: return@detectHorizontalDragGestures
                                scope.launch {
                                    slideBack.snapTo(0f)
                                    slideBack.animateTo(1f, tween(250)) { scrubMs = (from + (nowMs - from) * value).toLong() }
                                    scrubMs = null
                                }
                            },
                            onDragCancel = { scrubMs = null },
                        )
                    },
            ) {
                val bg = data.bg.filter { it.timestamp in fromMs..nowMs }.sortedBy { it.timestamp }
                val scale = EssentialSeries.chartScale(
                    bg.map { it.value } + prediction.map { it.value },
                    data.scaleFloor,
                    data.scaleCeiling,
                )
                val basalHeight = size.height * 0.16f
                val plotHeight = size.height - basalHeight - 6.dp.toPx()
                fun x(t: Long) = ((t - fromMs).toFloat() / (toMs - fromMs)) * size.width
                fun y(v: Double) = (plotHeight * (1.0 - (v - scale.start) / (scale.endInclusive - scale.start))).toFloat()
                fun zone(p: BgDataPoint): Color = when (p.range) {
                    BgRange.HIGH     -> general.bgHigh
                    BgRange.LOW      -> general.bgLow
                    BgRange.IN_RANGE -> general.bgInRange
                }

                // Target band: neutral, dashed edges
                drawRect(muted.copy(alpha = 0.08f), Offset(0f, y(data.highMark)), Size(size.width, y(data.lowMark) - y(data.highMark)))
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                listOf(data.lowMark, data.highMark).forEach { mark ->
                    drawLine(muted.copy(alpha = 0.45f), Offset(0f, y(mark)), Offset(size.width, y(mark)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                }

                // Area under the curve, in the colour of the situation
                bg.zipWithNext().forEach { (a, b) ->
                    if (b.timestamp - a.timestamp > GAP_MS) return@forEach
                    val area = Path().apply {
                        moveTo(x(a.timestamp), y(a.value))
                        lineTo(x(b.timestamp), y(b.value))
                        lineTo(x(b.timestamp), plotHeight)
                        lineTo(x(a.timestamp), plotHeight)
                        close()
                    }
                    drawPath(area, tint.copy(alpha = 0.16f))
                }

                // Delivered basal as bars; a rate near zero shows as a small dot
                val bars = EssentialSeries.basalBars(data.basal, fromMs, nowMs, BASAL_BARS)
                val maxBasal = max(bars.maxOfOrNull { it ?: 0.0 } ?: 0.0, 0.1)
                val slotWidth = x(nowMs) / BASAL_BARS
                val barWidth = slotWidth * 0.55f
                bars.forEachIndexed { i, rate ->
                    if (rate == null) return@forEachIndexed
                    val h = max((rate / maxBasal).toFloat() * basalHeight, barWidth)
                    drawRoundRect(
                        muted.copy(alpha = 0.5f),
                        topLeft = Offset(slotWidth * i + (slotWidth - barWidth) / 2f, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = CornerRadius(barWidth / 2f),
                    )
                }

                // Glucose, each segment in the colour of its zone
                bg.zipWithNext().forEach { (a, b) ->
                    if (b.timestamp - a.timestamp > GAP_MS) return@forEach // a gap stays a gap
                    drawLine(zone(b), Offset(x(a.timestamp), y(a.value)), Offset(x(b.timestamp), y(b.value)), strokeWidth = 2.5.dp.toPx())
                }

                // Prediction, dotted, after now
                if (prediction.size > 1) {
                    val path = Path().apply {
                        moveTo(x(prediction.first().timestamp), y(prediction.first().value))
                        prediction.drop(1).forEach { lineTo(x(it.timestamp), y(it.value)) }
                    }
                    drawPath(path, general.uamPrediction, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
                }

                // SMB on the curve, sized by dose: white, ringed in the colour of the zone
                val smbs = data.boluses.filter { it.bolusType == BolusType.SMB && it.isValid && it.timestamp in fromMs..nowMs }
                val maxSmb = max(smbs.maxOfOrNull { it.amount } ?: 0.0, 0.1)
                smbs.forEach { smb ->
                    val near = bg.minByOrNull { abs(it.timestamp - smb.timestamp) } ?: return@forEach
                    val r = (2.5f + 4f * sqrt((smb.amount / maxSmb).toFloat())).dp.toPx()
                    val at = Offset(x(smb.timestamp), y(near.value))
                    drawCircle(general.onBadge, radius = r, center = at)
                    drawCircle(zone(near), radius = r, center = at, style = Stroke(width = 1.5.dp.toPx()))
                }

                // Cursor: a line and a large point on the glucose at that time (now by default)
                val cx = x(cursorMs)
                drawLine(muted.copy(alpha = 0.7f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.dp.toPx())
                bg.minByOrNull { abs(it.timestamp - cursorMs) }?.let { p ->
                    val at = Offset(cx, y(p.value))
                    drawCircle(zone(p), radius = 7.dp.toPx(), center = at)
                    drawCircle(general.onBadge, radius = 7.dp.toPx(), center = at, style = Stroke(width = 2.dp.toPx()))
                }
            }

            // Time labels under the past part only: the right label stands at now, not at the end of the prediction
            Row(Modifier.fillMaxWidth(nowFraction), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel(fromMs), style = MaterialTheme.typography.labelSmall, color = muted)
                Text(timeLabel((fromMs + nowMs) / 2), style = MaterialTheme.typography.labelSmall, color = muted)
                Text(timeLabel(nowMs), style = MaterialTheme.typography.labelSmall, color = muted)
            }

            HorizontalDivider(Modifier.padding(top = AapsTheme.spacing.small))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { stepBack() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(UiStrings.essential_previous_step))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                    ReadoutCell(readout.time, stringResource(UiStrings.essential_readout_time))
                    ReadoutCell(readout.bg, stringResource(UiStrings.essential_readout_bg))
                    ReadoutCell(readout.basal, stringResource(UiStrings.essential_readout_basal))
                    ReadoutCell(readout.smb ?: stringResource(UiStrings.essential_no_smb), stringResource(UiStrings.essential_readout_smb))
                }
                IconButton(onClick = { stepForward() }, enabled = scrubMs != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(UiStrings.essential_next_step))
                }
            }
        }
    }
}

@Composable
private fun ReadoutCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
