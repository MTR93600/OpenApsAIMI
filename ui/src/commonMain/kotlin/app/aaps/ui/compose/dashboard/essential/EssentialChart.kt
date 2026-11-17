package app.aaps.ui.compose.dashboard.essential

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The series of the chart, in the user's glucose unit, straight from `GraphViewModel` (the same data
 * Glass draws).
 *
 * @property prediction one prediction curve (the one the 60 min value comes from), or empty
 */
data class EssentialChartData(
    val bg: List<BgDataPoint>,
    val prediction: List<BgDataPoint>,
    val basal: List<GraphDataPoint>,
    val boluses: List<BolusGraphPoint>,
    val lowMark: Double,
    val highMark: Double,
)

/** What the readout shows for the time under the finger, already formatted. */
data class EssentialReadout(val time: String, val bg: String, val basal: String, val smb: String?)

private const val STEP_MS = 5L * 60L * 1000L
private const val PREDICTION_SPACE_MS = 60L * 60L * 1000L

/**
 * The glucose chart: zone-coloured curve, quiet target band, dotted prediction, delivered basal as steps
 * at the bottom and SMB as dots sized by dose. No COB.
 *
 * Scrub: a horizontal drag moves a cursor and shows the readout of that time (glucose, basal decided,
 * SMB delivered). On release the cursor slides back to now and the readout hides. Left/right keys move
 * it by 5 minutes. The chart takes horizontal drags only, so the page still scrolls.
 */
@Composable
fun EssentialChart(
    data: EssentialChartData,
    nowMs: Long,
    rangeHours: Int,
    onRangeChange: (Int) -> Unit,
    readoutAt: @Composable (Long) -> EssentialReadout,
    timeLabel: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val general = AapsTheme.generalColors
    val scope = rememberCoroutineScope()
    val fromMs = nowMs - rangeHours * 60L * 60L * 1000L
    val toMs = nowMs + if (data.prediction.isEmpty()) 0L else PREDICTION_SPACE_MS
    // Null: no scrub. The cursor slides back to now through this animation, then goes away.
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val slideBack = remember { Animatable(0f) }

    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            listOf(6, 12, 18, 24).forEach { hours ->
                FilterChip(selected = hours == rangeHours, onClick = { onRangeChange(hours) }, label = { Text(stringResource(UiStrings.dashboard_glass_range_hours, hours)) })
            }
            Spacer(Modifier.weight(1f))
            // The same 5 min steps as the arrow keys, for a screen reader or a hand that cannot slide
            TextButton(onClick = { scrubMs = ((scrubMs ?: nowMs) - STEP_MS).coerceAtLeast(fromMs) }) {
                Text(stringResource(UiStrings.essential_previous_step))
            }
            TextButton(onClick = { scrubMs = scrubMs?.let { it + STEP_MS }?.takeIf { it < nowMs } }, enabled = scrubMs != null) {
                Text(stringResource(UiStrings.essential_next_step))
            }
        }
        val readout = scrubMs?.let { readoutAt(it) }
        val readoutText = readout?.let {
            stringResource(UiStrings.essential_readout_description, it.time, it.bg, it.basal, it.smb ?: stringResource(UiStrings.essential_no_smb))
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(168.dp)
                .semantics { contentDescription = readoutText ?: "" }
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val current = scrubMs ?: nowMs
                    when (event.key) {
                        Key.DirectionLeft  -> { scrubMs = (current - STEP_MS).coerceAtLeast(fromMs); true }
                        Key.DirectionRight -> { scrubMs = (current + STEP_MS).takeIf { it < nowMs }; true }
                        else               -> false
                    }
                }
                .pointerInput(fromMs, nowMs) {
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
            val values = data.bg.map { it.value } + data.prediction.map { it.value } + listOf(data.lowMark, data.highMark)
            val minY = min(values.minOrNull() ?: data.lowMark, data.lowMark) * 0.85
            val maxY = max(values.maxOrNull() ?: data.highMark, data.highMark) * 1.08
            val basalHeight = size.height * 0.18f
            val plotHeight = size.height - basalHeight
            fun x(t: Long) = ((t - fromMs).toFloat() / (toMs - fromMs)) * size.width
            fun y(v: Double) = (plotHeight * (1.0 - (v - minY) / (maxY - minY))).toFloat()

            // Target band and the low/high marks
            drawRect(general.bgTargetRangeArea, Offset(0f, y(data.highMark)), Size(size.width, y(data.lowMark) - y(data.highMark)))
            val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
            listOf(data.lowMark, data.highMark).forEach { mark ->
                drawLine(general.graphGrid, Offset(0f, y(mark)), Offset(size.width, y(mark)), strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }

            // Delivered basal as steps at the bottom
            val inWindow = data.basal.filter { it.timestamp <= nowMs }.sortedBy { it.timestamp }
            val maxBasal = max(inWindow.maxOfOrNull { it.value } ?: 0.0, 0.1)
            if (inWindow.isNotEmpty()) {
                val steps = Path()
                var started = false
                inWindow.forEachIndexed { i, p ->
                    val startX = x(max(p.timestamp, fromMs)).coerceAtLeast(0f)
                    val endX = x(inWindow.getOrNull(i + 1)?.timestamp ?: nowMs)
                    if (endX < 0f) return@forEachIndexed
                    val top = size.height - (p.value / maxBasal).toFloat() * basalHeight * 0.9f
                    if (!started) { steps.moveTo(startX, size.height); started = true }
                    steps.lineTo(startX, top)
                    steps.lineTo(endX, top)
                }
                steps.lineTo(x(nowMs), size.height)
                drawPath(steps, general.graphGrid, style = Stroke(width = 1.5.dp.toPx()))
            }

            // Glucose, each segment in the colour of its zone
            val bg = data.bg.filter { it.timestamp in fromMs..nowMs }.sortedBy { it.timestamp }
            fun zone(p: BgDataPoint): Color = when (p.range) {
                BgRange.HIGH     -> general.bgHigh
                BgRange.LOW      -> general.bgLow
                BgRange.IN_RANGE -> general.bgInRange
            }
            bg.zipWithNext().forEach { (a, b) ->
                if (b.timestamp - a.timestamp > 15 * 60_000L) return@forEach // a gap stays a gap
                drawLine(zone(b), Offset(x(a.timestamp), y(a.value)), Offset(x(b.timestamp), y(b.value)), strokeWidth = 2.5.dp.toPx())
            }

            // Prediction, dotted, from the last glucose on
            val prediction = data.prediction.filter { it.timestamp >= nowMs }.sortedBy { it.timestamp }
            if (prediction.size > 1) {
                val path = Path().apply {
                    moveTo(x(prediction.first().timestamp), y(prediction.first().value))
                    prediction.drop(1).forEach { lineTo(x(it.timestamp), y(it.value)) }
                }
                drawPath(path, general.uamPrediction, style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))))
            }

            // SMB on the curve, sized by dose
            val smbs = data.boluses.filter { it.bolusType == BolusType.SMB && it.isValid && it.timestamp in fromMs..nowMs }
            val maxSmb = max(smbs.maxOfOrNull { it.amount } ?: 0.0, 0.1)
            smbs.forEach { smb ->
                val near = bg.minByOrNull { abs(it.timestamp - smb.timestamp) } ?: return@forEach
                val r = (2.5f + 4f * sqrt((smb.amount / maxSmb).toFloat())).dp.toPx()
                drawCircle(general.onBadge, radius = r, center = Offset(x(smb.timestamp), y(near.value)))
                drawCircle(zone(near), radius = r, center = Offset(x(smb.timestamp), y(near.value)), style = Stroke(width = 1.5.dp.toPx()))
            }

            // Cursor
            scrubMs?.let { t ->
                val cx = x(t)
                drawLine(general.graphGrid, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.2.dp.toPx())
                bg.minByOrNull { abs(it.timestamp - t) }?.let { p ->
                    drawCircle(zone(p), radius = 6.dp.toPx(), center = Offset(cx, y(p.value)))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(timeLabel(fromMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(timeLabel(nowMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (readout != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                ReadoutCell(readout.time, stringResource(UiStrings.essential_readout_time))
                ReadoutCell(readout.bg, stringResource(UiStrings.essential_readout_bg))
                ReadoutCell(readout.basal, stringResource(UiStrings.essential_readout_basal))
                ReadoutCell(readout.smb ?: stringResource(UiStrings.essential_no_smb), stringResource(UiStrings.essential_readout_smb))
            }
        } else {
            Text(
                stringResource(UiStrings.essential_scrub_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ReadoutCell(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
