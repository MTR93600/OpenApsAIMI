package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.plugins.main.R
import kotlin.math.abs
import kotlin.math.roundToInt
import app.aaps.core.ui.R as CoreUiR

// Only ever called for GlassPillLocation.BOTTOM_ROW entries (see the bottom-row filter below), but the
// `when` must still cover every GlassPillId since top-grid IDs share the same enum.
private fun glassPillValue(id: GlassPillId, state: GlassUiState): String = when (id) {
    GlassPillId.IOB -> state.iobText
    GlassPillId.TARGET -> state.targetText
    GlassPillId.BASAL_RATE -> state.basalPercentText
    GlassPillId.LAST_BOLUS -> state.lastBolusText
    GlassPillId.LAST_CARBS -> state.lastCarbsText
    GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
    GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatusAgoraCard(
    state: GlassUiState,
    isDark: Boolean,
    onOpenLoop: () -> Unit,
    onOpenLoopDashboard: () -> Unit,
    onOpenTarget: () -> Unit,
    onOpenInsulin: () -> Unit,
    onOpenPump: () -> Unit,
    onOpenCannula: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenBasal: () -> Unit,
    onOpenSensorInsert: () -> Unit,
    onOpenSensorQuality: () -> Unit,
    onOpenTools: () -> Unit,
    selectedPills: List<GlassPillId>,
) {
    GlassContainer(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // LEFT: pump status
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (GlassPillId.PUMP_RESERVOIR in selectedPills) {
                        GlassPill(
                            label = state.insulinLabel,
                            value = state.insulinAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.insulinAgeStatus),
                            modifier = Modifier.width(100.dp).clickable { onOpenPump() },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_insulin),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    if (GlassPillId.CANNULA in selectedPills) {
                        GlassPill(
                            label = state.cannulaLabel,
                            value = state.cannulaAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.cannulaAgeStatus),
                            modifier = Modifier.width(100.dp).clickable { onOpenCannula() },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_cannula),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    if (GlassPillId.BATTERY in selectedPills) {
                        GlassPill(
                            label = state.batteryLabel,
                            value = state.batteryAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.batteryAgeStatus),
                            modifier = Modifier.width(100.dp).clickable { onOpenBattery() },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_battery),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    GlassPill(
                        label = stringResource(R.string.dashboard_glass_tools_label),
                        value = "",
                        isDark = isDark,
                        modifier = Modifier.width(100.dp).clickable { onOpenTools() },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_glyco_settings),
                                contentDescription = null,
                                tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }

                // CENTER: glucose, inside today's TIR ring
                Box(
                    modifier = Modifier
                        .weight(1.3f)
                        .offset(y = TIR_RING_LIFT)
                        .size(TIR_RING_SIZE),
                    contentAlignment = Alignment.Center
                ) {
                    TirRing(
                        tir = state.tir,
                        trackColor = (if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)).copy(alpha = 0.18f),
                        modifier = Modifier.matchParentSize()
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text(
                            text = state.currentBg,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(state.glucoseColor),
                            letterSpacing = (-1.2).sp,
                            lineHeight = 34.sp,
                            modifier = Modifier.clickable { onOpenLoop() }
                        )
                        Text(
                            text = state.unit,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (state.trendArrowRes != null) {
                                Icon(
                                    painter = painterResource(id = state.trendArrowRes),
                                    contentDescription = null,
                                    tint = Color(state.glucoseColor),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = state.deltaText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(state.glucoseColor),
                                modifier = Modifier.padding(start = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = state.timeAgo,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                        )
                        TirTrendText(
                            tir = state.tir,
                            neutralColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                            modifier = Modifier.padding(top = 1.dp)
                        )
                    }
                }

                // RIGHT: CGM & loop
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (GlassPillId.SENSOR in selectedPills) {
                        GlassPill(
                            label = state.sensorLabel,
                            value = state.sensorAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.sensorAgeStatus),
                            modifier = Modifier.width(100.dp).combinedClickable(
                                onClick = onOpenSensorQuality,
                                onLongClick = onOpenSensorInsert
                            ),
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_sensor),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                    if (GlassPillId.LOOP_STATUS in selectedPills) {
                        GlassPill(
                            label = stringResource(R.string.dashboard_glass_loop_label),
                            value = state.loopStatusText,
                            isDark = isDark,
                            modifier = Modifier.width(100.dp).combinedClickable(
                                onClick = onOpenLoopDashboard,
                                onLongClick = onOpenLoop
                            ),
                            leadingIcon = {
                                if (state.loopIsRunning) {
                                    val infiniteTransition = rememberInfiniteTransition()
                                    val pulseAlpha by infiniteTransition.animateFloat(
                                        initialValue = 0f,
                                        targetValue = 0.8f,
                                        animationSpec = infiniteRepeatable(
                                            animation = tween(1000, easing = LinearEasing),
                                            repeatMode = RepeatMode.Reverse
                                        )
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF22C55E).copy(alpha = pulseAlpha), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(Color(0xFF10B981), CircleShape)
                                        )
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF94A3B8), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(5.dp)
                                                .background(Color(0xFF94A3B8), CircleShape)
                                        )
                                    }
                                }
                            }
                        )
                    }
                    if (GlassPillId.ACTIVITY in selectedPills) {
                        GlassPill(
                            label = stringResource(R.string.dashboard_glass_activity_label),
                            value = stringResource(R.string.dashboard_glass_activity_value, state.stepsText, state.hrText),
                            isDark = isDark,
                            modifier = Modifier.width(100.dp),
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_settings),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
                }
            }

            val bottomRowPills = GLASS_PILL_CATALOG.filter { it.location == GlassPillLocation.BOTTOM_ROW && it.id in selectedPills }
            if (bottomRowPills.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    bottomRowPills.forEach { entry ->
                        val isTarget = entry.id == GlassPillId.TARGET
                        BottomMetricPill(
                            title = if (isTarget && state.isTempTargetActive) "" else stringResource(entry.labelRes),
                            value = glassPillValue(entry.id, state),
                            isDark = isDark,
                            onClick = when (entry.id) {
                                GlassPillId.IOB -> onOpenInsulin
                                GlassPillId.TARGET -> onOpenTarget
                                GlassPillId.BASAL_RATE -> onOpenBasal
                                GlassPillId.LAST_BOLUS -> onOpenInsulin
                                GlassPillId.LAST_CARBS -> onOpenInsulin
                                GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
                                GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY -> null
                            },
                            modifier = Modifier.weight(1f),
                            accentColor = if (isTarget && state.isTempTargetActive) Color(0xFFF4D700) else null
                        )
                    }
                }
            }
        }
    }
}

/**
 * Thin ring with today's time-in-range split: in range (green) from 12 o'clock, then high (amber),
 * then low (red), clockwise. A small gap separates the parts. Without data ([tir] null) only the empty
 * track is drawn.
 */
@Composable
private fun TirRing(tir: GlassTir?, trackColor: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokePx = TIR_RING_STROKE.toPx()
        val diameter = size.minDimension - strokePx
        if (diameter <= 0f) return@Canvas
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx)
        )
        if (tir == null) return@Canvas
        val parts = listOf(
            tir.inRangePct to GlassColors.emerald,
            tir.abovePct to GlassColors.amber,
            tir.belowPct to GlassColors.red,
        ).filter { it.first > 0f }
        val total = parts.sumOf { it.first.toDouble() }.toFloat()
        if (total <= 0f) return@Canvas
        val gap = if (parts.size > 1) TIR_RING_GAP_DEG else 0f
        var start = -90f
        for ((pct, color) in parts) {
            val sweep = pct / total * 360f
            // A few minutes low must stay visible: never draw a part thinner than the minimum.
            drawArc(
                color = color,
                startAngle = start + gap / 2f,
                sweepAngle = (sweep - gap).coerceAtLeast(TIR_RING_MIN_PART_DEG),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Butt)
            )
            start += sweep
        }
    }
}

/** "TIR 78% ↑6" under the glucose: green when better than yesterday, amber when worse. */
@Composable
private fun TirTrendText(tir: GlassTir?, neutralColor: Color, modifier: Modifier = Modifier) {
    val delta = tir?.deltaVsYesterday
    val percent = tir?.let { stringResource(CoreUiR.string.format_percent, it.inRangePct.roundToInt()) }
    val text = when {
        percent == null             -> stringResource(R.string.dashboard_glass_tir_empty)
        delta == null || delta == 0 -> stringResource(R.string.dashboard_glass_tir_value, percent)
        delta > 0                   -> stringResource(R.string.dashboard_glass_tir_up, percent, delta)
        else                        -> stringResource(R.string.dashboard_glass_tir_down, percent, abs(delta))
    }
    val color = when {
        delta == null || delta == 0 -> neutralColor
        delta > 0                   -> GlassColors.emerald
        else                        -> GlassColors.amber
    }
    Text(
        text = text,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier
    )
}

/** Fixed ring size: the glucose block is laid out to fit inside it, so the card height stays small. */
private val TIR_RING_SIZE = 130.dp
/** Moves the ring a little up, into the card top padding, without making the card taller. */
private val TIR_RING_LIFT = (-6).dp
private val TIR_RING_STROKE = 5.dp
private const val TIR_RING_GAP_DEG = 3f
private const val TIR_RING_MIN_PART_DEG = 2f
