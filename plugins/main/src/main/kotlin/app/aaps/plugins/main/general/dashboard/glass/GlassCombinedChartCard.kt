package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

/**
 * One glass card for the glucose chart and the IOB chart: a header with a slim hour selector, the glucose chart on
 * top and the IOB chart under it on the same time axis. The finger position is shared by the two charts.
 */
@Composable
internal fun GlassCombinedChartCard(
    isDark: Boolean,
    selectedMinutes: Int,
    onSelectMinutes: (Int) -> Unit,
    onOpenStats: () -> Unit,
    /** True while the window is moved back in time; a small button brings it back to now. */
    panned: Boolean,
    onResetPan: () -> Unit,
    /** Visible window and the shift back from now, both in minutes, for the time bar under the glucose chart. */
    windowMinutes: Int,
    panMinutes: Int,
    onPanToMinutes: (Int) -> Unit,
    statsLabel: String,
    bgChart: @Composable () -> Unit,
    iobChart: @Composable () -> Unit,
    balanceChart: @Composable () -> Unit,
    cobChart: @Composable () -> Unit,
    showCob: Boolean,
    onToggleCob: () -> Unit,
    showIob: Boolean,
    showBalance: Boolean,
    onToggleIob: () -> Unit,
    onToggleBalance: () -> Unit,
    /** Called with the change of scale between two touch events when two fingers are on the card. */
    onPinch: (Float) -> Unit,
) {
    val muted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    GlassContainer(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().pointerInput(Unit) { detectPinch(onPinch) }) {
            // One slim line: Stats, the layer switches and the hour selector.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = statsLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = muted,
                    maxLines = 1,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onOpenStats() }
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                )
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    Text(
                        text = "\u25BE",
                        fontSize = 16.sp,
                        color = muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { menuOpen = true }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        LayerMenuItem("IOB", showIob) { onToggleIob() }
                        LayerMenuItem("COB", showCob) { onToggleCob() }
                        LayerMenuItem("Коэффициенты", showBalance) { onToggleBalance() }
                    }
                }
                if (panned) {
                    Text(
                        text = "\u25B8| now",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isDark) Color(0x14FFFFFF) else Color(0x0F000000), RoundedCornerShape(50))
                            .clickable { onResetPan() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                HourSegment(selectedMinutes = selectedMinutes, onSelectMinutes = onSelectMinutes, isDark = isDark)
            }
            bgChart()
            TimeBar(windowMinutes = windowMinutes, panMinutes = panMinutes, onPanToMinutes = onPanToMinutes, isDark = isDark)
            if (showIob) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(if (isDark) Color(0x1FFFFFFF) else Color(0x1A000000))
                )
                iobChart()
            }
            if (showCob) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(if (isDark) Color(0x1FFFFFFF) else Color(0x1A000000))
                )
                cobChart()
            }
            if (showBalance) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(if (isDark) Color(0x1FFFFFFF) else Color(0x1A000000))
                )
                balanceChart()
            }
        }
    }
}

/** Shows the visible window as a small chip ("6h ▾"); a tap opens a list with all window sizes. */
@Composable
private fun HourSegment(selectedMinutes: Int, onSelectMinutes: (Int) -> Unit, isDark: Boolean) {
    val shape = RoundedCornerShape(50)
    val textColor = if (isDark) Color(0xFFCBD5E1) else Color(0xFF334155)
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            text = windowLabel(selectedMinutes) + " \u25BE",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .background(if (isDark) Color(0x14FFFFFF) else Color(0x0F000000), shape)
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(180, 360, 720, 1080, 1440).forEach { minutes ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = (if (minutes == selectedMinutes) "\u2713  " else "     ") + windowLabel(minutes),
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        onSelectMinutes(minutes)
                        open = false
                    }
                )
            }
        }
    }
}

/** "15m" for short windows, "6h" for whole hours. */
private fun windowLabel(minutes: Int): String = if (minutes < 60) "${minutes}m" else "${minutes / 60}h"

/** One layer in the drop-down: a check mark when it is on. */
@Composable
private fun LayerMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text = (if (selected) "\u2713  " else "     ") + label, fontSize = 14.sp) },
        onClick = onClick
    )
}

/**
 * Two-finger pinch: reports the change of distance between the two fingers as a factor (above 1 when they move
 * apart). It watches the touches before the children and consumes nothing, so a single finger still moves the charts' line.
 */
private suspend fun PointerInputScope.detectPinch(onPinch: (Float) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var previous = 0f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val dx = pressed[0].position.x - pressed[1].position.x
                val dy = pressed[0].position.y - pressed[1].position.y
                val distance = sqrt(dx * dx + dy * dy)
                if (previous > 0f && distance > 0f) onPinch(distance / previous)
                previous = distance
            } else {
                previous = 0f
            }
        } while (event.changes.any { it.pressed })
    }
}

/** The charts keep one day of history: the window plus the shift back can never be longer than this. */
private const val HISTORY_MINUTES = 24 * 60

/**
 * A slim bar along the whole chart with a rounded handle. Dragging the handle to the left moves the charts back in
 * time, dragging it to the right brings them to now. The handle is as wide as the visible window is long.
 */
@Composable
private fun TimeBar(windowMinutes: Int, panMinutes: Int, onPanToMinutes: (Int) -> Unit, isDark: Boolean) {
    val trackColor = if (isDark) Color(0x1FFFFFFF) else Color(0x14000000)
    val thumbColor = if (isDark) Color(0x66CBD5E1) else Color(0x66334155)
    val total = HISTORY_MINUTES.toFloat()
    val maxPan = (HISTORY_MINUTES - windowMinutes).coerceAtLeast(0)
    val currentMaxPan by rememberUpdatedState(maxPan)
    val currentPan by rememberUpdatedState(panMinutes)
    val currentOnPan by rememberUpdatedState(onPanToMinutes)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .padding(horizontal = 16.dp, vertical = 7.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dx ->
                    change.consume()
                    // Handle to the left = further back in time.
                    val minutesPerPx = HISTORY_MINUTES / size.width.toFloat()
                    currentOnPan((currentPan - dx * minutesPerPx).toInt().coerceIn(0, currentMaxPan))
                }
            }
    ) {
        val thumbFrac = (windowMinutes / total).coerceIn(0.06f, 1f)
        val startFrac = ((maxPan - panMinutes) / total).coerceIn(0f, 1f - thumbFrac)
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(thumbFrac)
                .graphicsLayer { translationX = startFrac * (size.width / thumbFrac) }
                .clip(RoundedCornerShape(50))
                .background(thumbColor)
        )
    }
}
