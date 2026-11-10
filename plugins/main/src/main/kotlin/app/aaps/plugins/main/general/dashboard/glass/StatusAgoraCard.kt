package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.compose.icons.IcLoopSuperbolus
import app.aaps.core.ui.compose.icons.IcLoopPausedPump
import app.aaps.core.ui.compose.icons.IcLoopPausedDst
import app.aaps.core.ui.compose.icons.IcLoopPaused
import app.aaps.core.ui.compose.icons.IcLoopOpen
import app.aaps.core.ui.compose.icons.IcLoopLgs
import app.aaps.core.ui.compose.icons.IcLoopDisconnected
import app.aaps.core.ui.compose.icons.IcLoopDisabled
import app.aaps.core.ui.compose.icons.IcLoopClosed
import app.aaps.core.ui.compose.loopColor
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.data.model.RM
import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.plugins.main.R
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// Only ever called for GlassPillLocation.BOTTOM_ROW entries (see the bottom-row filter below), but the
// `when` must still cover every GlassPillId since top-grid IDs share the same enum.
private fun glassPillValue(id: GlassPillId, state: GlassUiState): String = when (id) {
    GlassPillId.IOB -> state.iobText
    GlassPillId.TARGET -> state.targetText
    GlassPillId.BASAL_RATE -> state.basalPercentText
    GlassPillId.LAST_BOLUS -> state.lastBolusText
    GlassPillId.LAST_CARBS -> state.lastCarbsText
    GlassPillId.RESERVOIR_LEVEL -> state.reservoirText
    GlassPillId.AIMI_INSIGHT -> "\u25B8"
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
    val context = LocalContext.current
    // Minute clock: keeps "last value N min ago" and the stale grey moving between view-model refreshes.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val glucoseStale = GlassCgmLinkStatus.isStale(state.isGlucoseActual, state.glucoseTimestampMs, nowMs)
    val linkBanner = GlassCgmLinkStatus.banner(state.warmup, state.isGlucoseActual, state.glucoseTimestampMs, nowMs)
    val neutralColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    GlassContainer(isDark = isDark, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // The enabled blocks fill the left and the right column in turn, half and half.
                val sidePills = buildList<@Composable () -> Unit> {
                    if (GlassPillId.PUMP_RESERVOIR in selectedPills) add {
                            GlassPill(
                                label = state.insulinLabel,
                                value = state.insulinAge,
                                isDark = isDark,
                                valueColor = statusLevelToColor(state.insulinAgeStatus),
                                modifier = Modifier.width(84.dp).clickable { onOpenPump() },
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
                    if (GlassPillId.CANNULA in selectedPills) add {
                            GlassPill(
                                label = state.cannulaLabel,
                                value = state.cannulaAge,
                                isDark = isDark,
                                valueColor = statusLevelToColor(state.cannulaAgeStatus),
                                modifier = Modifier.width(84.dp).clickable { onOpenCannula() },
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
                    if (GlassPillId.BATTERY in selectedPills) add {
                            GlassPill(
                                label = state.batteryLabel,
                                value = state.batteryAge,
                                isDark = isDark,
                                valueColor = statusLevelToColor(state.batteryAgeStatus),
                                modifier = Modifier.width(84.dp).clickable { onOpenBattery() },
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
                    if (GlassPillId.SENSOR in selectedPills) add {
                            GlassPill(
                                label = state.sensorLabel,
                                value = state.sensorAge,
                                isDark = isDark,
                                valueColor = statusLevelToColor(state.sensorAgeStatus),
                                modifier = Modifier.width(84.dp).combinedClickable(
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
                    if (GlassPillId.ACTIVITY in selectedPills) add {
                            GlassPill(
                                label = stringResource(R.string.dashboard_glass_activity_label),
                                value = stringResource(R.string.dashboard_glass_activity_value, state.stepsText, state.hrText),
                                isDark = isDark,
                                modifier = Modifier.width(84.dp),
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
                    add {
                        GlassPill(
                            label = stringResource(R.string.dashboard_glass_tools_label),
                            value = "",
                            isDark = isDark,
                            modifier = Modifier.width(84.dp).clickable { onOpenTools() },
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
                val leftCount = (sidePills.size + 1) / 2

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // LEFT
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) { sidePills.take(leftCount).forEach { it() } }

                // CENTER: big glucose value (the daily numbers are in the dashboard row below)
                Box(
                    modifier = Modifier.weight(1.3f),
                    contentAlignment = Alignment.Center
                ) {
                    // A value with four characters (10.2) is smaller, so it always fits between the side blocks.
                    val glucoseFontSize = if (state.currentBg.length >= 4) GLUCOSE_FONT_SIZE_LONG else GLUCOSE_FONT_SIZE
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        // The running mode: a small, half transparent icon above the glucose (tap: loop screen, hold: mode list).
                        state.loopMode?.let { mode ->
                            Icon(
                                imageVector = loopModeIcon(mode),
                                contentDescription = state.loopStatusText,
                                tint = mode.loopColor(AapsTheme.generalColors).copy(alpha = 0.5f),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .combinedClickable(onClick = onOpenLoopDashboard, onLongClick = onOpenLoop)
                            )
                        }
                        // An old value stays visible, greyed and struck through, like the classic overview.
                        Text(
                            text = state.currentBg,
                            fontSize = glucoseFontSize,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (glucoseStale) neutralColor else Color(if (state.glucoseDigitsColor != 0) state.glucoseDigitsColor else state.glucoseColor),
                            textDecoration = if (glucoseStale) TextDecoration.LineThrough else null,
                            letterSpacing = (-2).sp,
                            lineHeight = glucoseFontSize,
                            maxLines = 1,
                            softWrap = false,
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
                    }
                }

                // RIGHT
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) { sidePills.drop(leftCount).forEach { it() } }
            }

            linkBanner?.let { banner ->
                Spacer(modifier = Modifier.height(8.dp))
                CgmLinkBannerText(banner)
            }

            DailyDashboard(state = state, isDark = isDark)

            val bottomRowPills = GLASS_PILL_CATALOG.filter { it.location == GlassPillLocation.BOTTOM_ROW && it.id in selectedPills }
            // The "I eat" button sits above the Assistant button and opens the eating screen of the Assistant.
            if (GlassPillId.AIMI_INSIGHT in selectedPills) {
                Spacer(modifier = Modifier.height(8.dp))
                Spacer(modifier = Modifier.height(8.dp))
                EatingButton(isDark = isDark)
            }
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
                                GlassPillId.RESERVOIR_LEVEL -> onOpenPump
                                GlassPillId.AIMI_INSIGHT -> {
                                    {
                                        // Same app: an explicit intent works although the activity is not exported.
                                        context.startActivity(
                                            Intent().setClassName(
                                                context,
                                                "app.aaps.plugins.aps.openAPSAIMI.advisor.assistant.AimiAssistantActivity"
                                            )
                                        )
                                    }
                                }
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
 * Button "Я ем" (I eat): opens the eating screen. While the eating mode runs it is green and shows the minutes.
 * It reads the state that the eating engine of the AIMI plugin keeps in its preferences file.
 */
@Composable
private fun EatingButton(isDark: Boolean) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            now = System.currentTimeMillis()
        }
    }
    val prefs = context.getSharedPreferences("aimi_eating", Context.MODE_PRIVATE)
    // Read the tick always, so the button redraws also when the mode is off and then starts.
    val tick = now
    val active = tick > 0L && prefs.getBoolean("active", false)
    val openScreen = {
        context.startActivity(
            Intent().setClassName(
                context,
                "app.aaps.plugins.aps.openAPSAIMI.advisor.assistant.AimiEatingActivity"
            )
        )
    }
    if (active) {
        val startMs = prefs.getLong("start_ms", now)
        val size = prefs.getInt("size", 4).let { if (it == 3) 4 else it }
        val fat = prefs.getBoolean("fat", false) || prefs.getInt("size", 4) == 3
        val mode = listOf("Малый", "Средний", "Большой", "Авто", "Авто")[size.coerceIn(0, 4)] + if (fat) " + жирная" else ""
        val windowMin = if (fat) 300L else 180L
        // The engine writes the end of the window ("end_ms"); a new rise can make it longer than the normal length.
        val endMs = prefs.getLong("end_ms", 0L).takeIf { it > startMs } ?: (startMs + windowMin * 60_000L)
        val remainingMin = ((endMs - now + 59_999L) / 60_000L).coerceAtLeast(0L)
        // Carbohydrates counted so far in bread units (the engine keeps grams) and insulin given in the window.
        val carbs = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("est_g", 0f) / 10f)
        val insulin = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("deliv_u", 0f))
        // In brackets: the most that the system counted in this meal (carbs) and the most insulin it found needed.
        val carbsPeakG = prefs.getFloat("counted_g", 0f)
        val carbsPeak = String.format(Locale.getDefault(), "%.1f", carbsPeakG / 10f)
        val insulinNeed = String.format(Locale.getDefault(), "%.1f", prefs.getFloat("need_max_u", 0f))
        EatingRunningPill(
            mode = mode,
            remainingMin = remainingMin,
            carbs = carbs,
            insulin = insulin,
            carbsPeak = carbsPeak,
            insulinNeed = insulinNeed,
            isDark = isDark,
            onClick = openScreen,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        BottomMetricPill(
            title = "Я ем",
            value = "",
            isDark = isDark,
            onClick = openScreen,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Row of the daily numbers that matter: time in range today (with the change against yesterday), time low and
 * high, average glucose, variability (CV) and the estimated A1c. All values come from the dashboard state.
 */
@Composable
private fun DailyDashboard(state: GlassUiState, isDark: Boolean) {
    val neutral = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val tir = state.tir
    val delta = tir?.deltaVsYesterday
    val tirSub = when {
        delta == null || delta == 0 -> ""
        delta > 0                   -> "↑$delta"
        else                        -> "↓${abs(delta)}"
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        DashboardTile("TIR", tir?.let { "${it.inRangePct.roundToInt()}%" } ?: "--", tirSub, GlassColors.emerald, neutral, Modifier.weight(1f))
        DashboardTile("Low", tir?.let { "${it.belowPct.roundToInt()}%" } ?: "--", "", GlassColors.red, neutral, Modifier.weight(1f))
        DashboardTile("High", tir?.let { "${it.abovePct.roundToInt()}%" } ?: "--", "", GlassColors.amber, neutral, Modifier.weight(1f))
        DashboardTile("Avg", state.avgBgText, "", if (isDark) Color.White else Color(0xFF0F172A), neutral, Modifier.weight(1f))
        DashboardTile("CV", state.cvText, "", if (isDark) Color.White else Color(0xFF0F172A), neutral, Modifier.weight(1f))
        DashboardTile("eA1c", state.gmiText, "", if (isDark) Color.White else Color(0xFF0F172A), neutral, Modifier.weight(1f))
    }
}

@Composable
private fun DashboardTile(label: String, value: String, sub: String, valueColor: Color, labelColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = if (sub.isEmpty()) label else "$label $sub",
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Size of the main glucose number. */
private val GLUCOSE_FONT_SIZE = 76.sp
private val GLUCOSE_FONT_SIZE_LONG = 60.sp

/** "Reconnecting… · last value 14 min ago", in the attention colour of the other dashboard skins. */
@Composable
private fun CgmLinkBannerText(banner: GlassCgmLinkStatus.Banner) {
    val phase = stringResource(
        when (banner.phase) {
            CgmWarmupStatus.Phase.CONNECTING -> R.string.dashboard_warmup_phase_connecting
            CgmWarmupStatus.Phase.RECONNECTING -> R.string.dashboard_warmup_phase_reconnecting
            CgmWarmupStatus.Phase.PAIRING -> R.string.dashboard_warmup_phase_pairing
            CgmWarmupStatus.Phase.WARMING,
            CgmWarmupStatus.Phase.OTHER -> R.string.dashboard_warmup_label
        }
    )
    Text(
        text = banner.minutesSinceLastValue?.let { stringResource(R.string.dashboard_glass_cgm_link_banner, phase, it.toInt()) } ?: phase,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = colorResource(R.color.dashboard_metric_attention),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

/** Icon of the running mode, the same set as in the mode list. */
private fun loopModeIcon(mode: RM.Mode): ImageVector = when (mode) {
    RM.Mode.CLOSED_LOOP       -> IcLoopClosed
    RM.Mode.CLOSED_LOOP_LGS   -> IcLoopLgs
    RM.Mode.OPEN_LOOP         -> IcLoopOpen
    RM.Mode.DISABLED_LOOP     -> IcLoopDisabled
    RM.Mode.SUPER_BOLUS       -> IcLoopSuperbolus
    RM.Mode.DISCONNECTED_PUMP -> IcLoopDisconnected
    RM.Mode.SUSPENDED_BY_PUMP -> IcLoopPausedPump
    RM.Mode.SUSPENDED_BY_DST  -> IcLoopPausedDst
    RM.Mode.SUSPENDED_BY_USER -> IcLoopPaused
    RM.Mode.RESUME            -> IcLoopClosed
}
