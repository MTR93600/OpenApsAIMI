package app.aaps.ui.compose.dashboard.glass

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.icons.IcGlycoBattery
import app.aaps.core.ui.compose.icons.IcGlycoCannula
import app.aaps.core.ui.compose.icons.IcGlycoInsulin
import app.aaps.core.ui.compose.icons.IcGlycoSensor
import app.aaps.core.ui.compose.icons.IcGlycoSettings
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.extensions.directionToIcon
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.GlassCgmLinkStatus
import kotlin.math.abs
import kotlin.math.roundToInt

/** Neutral grey for a glucose with an unknown range, and for a stale value. */
private val NEUTRAL_DARK = Color(0xFF94A3B8)
private val NEUTRAL_LIGHT = Color(0xFF64748B)
private val LOOP_RUNNING_HALO = Color(0xFF22C55E)
private val TEMP_TARGET_ACCENT = Color(0xFFF4D700)

// Only ever called for GlassPillLocation.BOTTOM_ROW entries (see the bottom-row filter below), but the
// `when` must still cover every GlassPillId since top-grid IDs share the same enum.
private fun glassPillValue(id: GlassPillId, state: GlassUiState): String = when (id) {
    GlassPillId.IOB            -> state.iobText
    GlassPillId.TARGET         -> state.targetText
    GlassPillId.BASAL_RATE     -> state.basalPercentText
    GlassPillId.LAST_BOLUS     -> state.lastBolusText
    GlassPillId.LAST_CARBS     -> state.lastCarbsText
    GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
    GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY -> ""
}

/**
 * The status card at the top of the Glass home: glucose inside today's time-in-range ring, the
 * device pills left and right, and the selected bottom-row pills. Port of `StatusAgoraCard` on
 * `dev_OAPSAIMI`.
 *
 * @param nowMs current time, from a ticker: keeps "last value N min ago" and the stale grey moving
 *   between two refreshes of the data
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatusAgoraCard(
    state: GlassUiState,
    isDark: Boolean,
    nowMs: Long,
    commands: GlassHeroCommands,
    onOpenTools: () -> Unit,
    selectedPills: List<GlassPillId>,
) {
    val glucoseStale = GlassCgmLinkStatus.isStale(state.isGlucoseActual, state.glucoseTimestampMs, nowMs)
    val linkBanner = GlassCgmLinkStatus.banner(state.warmup, state.isGlucoseActual, state.glucoseTimestampMs, nowMs)
    val neutralColor = if (isDark) NEUTRAL_DARK else NEUTRAL_LIGHT
    val glucoseColor = glucoseRangeColor(state.glucoseRange, isDark)

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
                            label = state.insulinLabel ?: stringResource(CoreUiStrings.insulin_label),
                            value = state.insulinAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.insulinAgeStatus),
                            modifier = Modifier.width(PILL_WIDTH).clickable { commands.openPump() },
                            leadingIcon = { PillIcon(IcGlycoInsulin, neutralColor) }
                        )
                    }
                    if (GlassPillId.CANNULA in selectedPills) {
                        GlassPill(
                            label = state.cannulaLabel ?: stringResource(CoreUiStrings.cannula),
                            value = state.cannulaAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.cannulaAgeStatus),
                            modifier = Modifier.width(PILL_WIDTH).clickable { commands.openCannula() },
                            leadingIcon = { PillIcon(IcGlycoCannula, neutralColor) }
                        )
                    }
                    if (GlassPillId.BATTERY in selectedPills) {
                        GlassPill(
                            label = state.batteryLabel ?: stringResource(CoreUiStrings.pb_label),
                            value = state.batteryAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.batteryAgeStatus),
                            modifier = Modifier.width(PILL_WIDTH).clickable { commands.openBattery() },
                            leadingIcon = { PillIcon(IcGlycoBattery, neutralColor) }
                        )
                    }
                    GlassPill(
                        label = stringResource(UiStrings.dashboard_glass_tools_label),
                        value = "",
                        isDark = isDark,
                        modifier = Modifier.width(PILL_WIDTH).clickable { onOpenTools() },
                        leadingIcon = { PillIcon(IcGlycoSettings, neutralColor) }
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
                        trackColor = neutralColor.copy(alpha = 0.18f),
                        modifier = Modifier.matchParentSize()
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        // An old value stays visible, greyed and struck through, like the classic overview.
                        Text(
                            text = state.currentBg,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (glucoseStale) neutralColor else glucoseColor,
                            textDecoration = if (glucoseStale) TextDecoration.LineThrough else null,
                            letterSpacing = (-1.2).sp,
                            lineHeight = 34.sp,
                            modifier = Modifier.clickable { commands.openLoop() }
                        )
                        Text(
                            text = state.unit,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = neutralColor,
                            modifier = Modifier.padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            state.trendArrow?.let { arrow ->
                                Icon(
                                    imageVector = arrow.directionToIcon(),
                                    contentDescription = null,
                                    tint = glucoseColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = state.deltaText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = glucoseColor,
                                modifier = Modifier.padding(start = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = state.timeAgo,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = neutralColor
                        )
                        TirTrendText(
                            tir = state.tir,
                            neutralColor = neutralColor,
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
                            label = state.sensorLabel ?: stringResource(CoreUiStrings.sensor_label),
                            value = state.sensorAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.sensorAgeStatus),
                            modifier = Modifier.width(PILL_WIDTH).combinedClickable(
                                onClick = commands::openSensorQuality,
                                onLongClick = commands::openSensorInsert
                            ),
                            leadingIcon = { PillIcon(IcGlycoSensor, neutralColor) }
                        )
                    }
                    if (GlassPillId.LOOP_STATUS in selectedPills) {
                        GlassPill(
                            label = stringResource(UiStrings.dashboard_glass_loop_label),
                            value = state.loopStatusText ?: stringResource(UiStrings.dashboard_glass_loop_label),
                            isDark = isDark,
                            modifier = Modifier.width(PILL_WIDTH).combinedClickable(
                                onClick = commands::openLoopDashboard,
                                onLongClick = commands::openLoop
                            ),
                            leadingIcon = { LoopDot(running = state.loopIsRunning) }
                        )
                    }
                    if (GlassPillId.ACTIVITY in selectedPills) {
                        GlassPill(
                            label = stringResource(UiStrings.dashboard_glass_activity_label),
                            value = stringResource(UiStrings.dashboard_glass_activity_value, state.stepsText, state.hrText),
                            isDark = isDark,
                            modifier = Modifier.width(PILL_WIDTH),
                            leadingIcon = { PillIcon(IcGlycoSettings, neutralColor) }
                        )
                    }
                }
            }

            linkBanner?.let { banner ->
                Spacer(modifier = Modifier.height(8.dp))
                CgmLinkBannerText(banner)
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
                            title = if (isTarget && state.isTempTargetActive) "" else stringResource(entry.label),
                            value = glassPillValue(entry.id, state),
                            isDark = isDark,
                            onClick = when (entry.id) {
                                GlassPillId.IOB        -> commands::openInsulin
                                GlassPillId.TARGET     -> commands::openTarget
                                GlassPillId.BASAL_RATE -> commands::openBasal
                                GlassPillId.LAST_BOLUS -> commands::openInsulin
                                GlassPillId.LAST_CARBS -> commands::openInsulin
                                GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
                                GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY -> null
                            },
                            modifier = Modifier.weight(1f),
                            accentColor = if (isTarget && state.isTempTargetActive) TEMP_TARGET_ACCENT else null
                        )
                    }
                }
            }
        }
    }
}

/** Glucose colour by range, from the app theme. Unknown range: the neutral grey. */
@Composable
private fun glucoseRangeColor(range: BgRange?, isDark: Boolean): Color = when (range) {
    BgRange.HIGH     -> AapsTheme.generalColors.bgHigh
    BgRange.IN_RANGE -> AapsTheme.generalColors.bgInRange
    BgRange.LOW      -> AapsTheme.generalColors.bgLow
    null             -> if (isDark) NEUTRAL_DARK else NEUTRAL_LIGHT
}

@Composable
private fun PillIcon(icon: ImageVector, tint: Color) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(14.dp)
    )
}

/** Pulsing green dot while the loop runs, a grey dot when it is paused. */
@Composable
private fun LoopDot(running: Boolean) {
    if (running) {
        val infiniteTransition = rememberInfiniteTransition()
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 0.8f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
        // pulseAlpha is read in the draw phase only: reading it in composition
        // recomposed this dot on every frame and kept the render thread busy.
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .drawBehind { drawCircle(LOOP_RUNNING_HALO, alpha = pulseAlpha) },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(GlassColors.emerald, CircleShape)
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(NEUTRAL_DARK, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(NEUTRAL_DARK, CircleShape)
            )
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
    val percent = tir?.let { stringResource(CoreUiStrings.units_format_percent, it.inRangePct.roundToInt()) }
    val text = when {
        percent == null             -> stringResource(UiStrings.dashboard_glass_tir_empty)
        delta == null || delta == 0 -> stringResource(UiStrings.dashboard_glass_tir_value, percent)
        delta > 0                   -> stringResource(UiStrings.dashboard_glass_tir_up, percent, delta)
        else                        -> stringResource(UiStrings.dashboard_glass_tir_down, percent, abs(delta))
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

/** Width of every top-grid pill. */
private val PILL_WIDTH = 100.dp

/** Fixed ring size: the glucose block is laid out to fit inside it, so the card height stays small. */
private val TIR_RING_SIZE = 130.dp

/** Moves the ring a little up, into the card top padding, without making the card taller. */
private val TIR_RING_LIFT = (-6).dp
private val TIR_RING_STROKE = 5.dp
private const val TIR_RING_GAP_DEG = 3f
private const val TIR_RING_MIN_PART_DEG = 2f

/** "Reconnecting… · last value 14 min ago", in the attention colour of the other dashboard skins. */
@Composable
private fun CgmLinkBannerText(banner: GlassCgmLinkStatus.Banner) {
    val phase = stringResource(
        when (banner.phase) {
            CgmWarmupStatus.Phase.CONNECTING   -> UiStrings.dashboard_glass_cgm_phase_connecting
            CgmWarmupStatus.Phase.RECONNECTING -> UiStrings.dashboard_glass_cgm_phase_reconnecting
            CgmWarmupStatus.Phase.PAIRING      -> UiStrings.dashboard_glass_cgm_phase_pairing
            CgmWarmupStatus.Phase.WARMING,
            CgmWarmupStatus.Phase.OTHER        -> UiStrings.dashboard_glass_cgm_phase_warmup
        }
    )
    Text(
        text = banner.minutesSinceLastValue?.let { stringResource(UiStrings.dashboard_glass_cgm_link_banner, phase, it.toInt()) } ?: phase,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = GlassColors.metricAttention,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}
