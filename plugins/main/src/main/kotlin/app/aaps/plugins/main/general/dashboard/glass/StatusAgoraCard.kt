package app.aaps.plugins.main.general.dashboard.glass

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.plugins.main.R

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
            Spacer(modifier = Modifier.height(10.dp))

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

                // CENTER: glucose
                Column(
                    modifier = Modifier.weight(1.3f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = state.currentBg,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(state.glucoseColor),
                        letterSpacing = (-1.5).sp,
                        lineHeight = 42.sp,
                        modifier = Modifier.clickable { onOpenLoop() }
                    )
                    Text(
                        text = state.unit,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (state.trendArrowRes != null) {
                            Icon(
                                painter = painterResource(id = state.trendArrowRes),
                                contentDescription = null,
                                tint = Color(state.glucoseColor),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Text(
                            text = state.deltaText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(state.glucoseColor),
                            modifier = Modifier.padding(start = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = state.timeAgo,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                    )
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
                Spacer(modifier = Modifier.height(14.dp))

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
