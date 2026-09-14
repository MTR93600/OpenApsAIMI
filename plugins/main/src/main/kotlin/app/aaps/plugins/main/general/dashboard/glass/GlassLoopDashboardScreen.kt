package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R

@Composable
fun GlassLoopDashboardScreen(
    uiState: GlassLoopDashboardState,
    onBack: () -> Unit,
    isDark: Boolean,
    onOpenAimiContext: () -> Unit,
) {
    val bgColorTop = if (isDark) Color(0xFF213145) else Color(0xFFF1F5F9)
    val bgColorBot = if (isDark) Color(0xFF0F1C2C) else Color(0xFFE8EEF8)
    val cardBgStart = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)
    val cardBgEnd = if (isDark) Color(0xFF0F1C2C) else Color(0xFFF8FAFC)
    val borderCard = if (isDark) Color(0x33EAF1FF) else Color(0xFFE2E8F0)
    val textBright = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0D1B2A)
    val textMuted = if (isDark) Color(0xFF778598) else Color(0xFF64748B)
    val cellBg = if (isDark) Color(0x14EAF1FF) else Color(0xFFF1F5F9)
    val cellBorder = if (isDark) Color(0x22EAF1FF) else Color(0xFFE2E8F0)
    val skyBlue = Color(0xFF0984E3)
    val emerald = Color(0xFF00B894)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFF43F5E)
    val indigo = Color(0xFF6366F1)
    val iconBgBlue = if (isDark) Color(0xFF15406B) else Color(0xFFDBEAFE)
    val iconBgGreen = if (isDark) Color(0xFF0B4A3A) else Color(0xFFD1FAE5)
    val iconBgAmber = if (isDark) Color(0xFF3D2E0A) else Color(0xFFFEF3C7)
    val dividerSoft = if (isDark) Color(0x1AEAF1FF) else Color(0x1A000000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgColorTop, bgColorBot, bgColorTop)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = textBright)
                }
                Text(
                    text = stringResource(R.string.dashboard_glass_loop_dashboard_title),
                    color = textBright,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // HEADER CARD
            GlassCardInner(isDark, cardBgStart, cardBgEnd, borderCard) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        if (uiState.lastRunTime.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.dashboard_glass_loop_dashboard_last_run, uiState.lastRunTime),
                                color = textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.dashboard_glass_loop_dashboard_pump_label),
                            color = textMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(emerald.copy(alpha = 0.12f))
                                .border(1.dp, emerald.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.dashboard_glass_loop_dashboard_requested, uiState.requestedSmbText),
                                color = emerald,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // CARD 1: GLUCOSE & INSULIN DYNAMICS
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Bolt, null, tint = skyBlue, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgBlue,
                title = stringResource(R.string.dashboard_glass_loop_dynamics_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_glucose_label),
                            value = uiState.glucoseText,
                            subContent = {
                                val deltaColor = if (uiState.delta5mIsPositive) emerald else red
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(uiState.delta5mText, color = deltaColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stringResource(R.string.dashboard_glass_loop_delta_5m_label),
                                        color = textMuted,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_delta_avg_label),
                            value = stringResource(R.string.dashboard_glass_loop_tir_low_value, uiState.shortAvgDeltaText, uiState.longAvgDeltaText),
                            valueColor = when {
                                uiState.shortAvgDeltaIsPositive && uiState.longAvgDeltaIsPositive -> emerald
                                !uiState.shortAvgDeltaIsPositive && !uiState.longAvgDeltaIsPositive -> red
                                else -> amber
                            },
                            valueFontSize = 13.sp,
                            subContent = {
                                Text(stringResource(R.string.dashboard_glass_loop_delta_avg_hint), color = textMuted, fontSize = 9.sp)
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_iob_label),
                            labelColor = indigo,
                            value = uiState.iobText,
                            valueColor = indigo,
                            subContent = {
                                Text(stringResource(R.string.dashboard_glass_loop_iob_hint), color = textMuted, fontSize = 9.sp)
                            }
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_target_label),
                            value = uiState.targetBgText,
                        )
                    }
                    GlassCardInner(isDark, cellBg, cellBg, cellBorder) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.dashboard_glass_loop_tdd_label), color = textMuted, fontSize = 11.sp)
                            Text(uiState.tdd7DaysPerHourText, color = textBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // CARD 1b: TRAJECTORY
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = skyBlue, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgBlue,
                title = stringResource(R.string.dashboard_glass_loop_trajectory_title)
            ) {
                if (uiState.hasTrajectory) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_trajectory_hybrid_label),
                            value = uiState.trajectoryHybridText,
                            valueFontSize = 16.sp,
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_trajectory_iob_label),
                            value = uiState.trajectoryIobText,
                            valueFontSize = 16.sp,
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_trajectory_cob_label),
                            value = uiState.trajectoryCobText,
                            valueFontSize = 16.sp,
                        )
                    }
                } else {
                    Text(stringResource(R.string.dashboard_glass_loop_trajectory_stale), color = textMuted, fontSize = 11.sp)
                }
            }

            // CARD 1c: PHYSIO
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Bolt, null, tint = indigo, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgBlue,
                title = stringResource(R.string.dashboard_glass_loop_physio_title)
            ) {
                if (uiState.hasPhysio) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCell(
                                isDark, cellBg, cellBorder, Modifier.weight(1f),
                                label = stringResource(R.string.dashboard_glass_loop_physio_mode_label),
                                value = uiState.physioModeText,
                                valueFontSize = 13.sp,
                            )
                            MetricCell(
                                isDark, cellBg, cellBorder, Modifier.weight(1f),
                                label = stringResource(R.string.dashboard_glass_loop_physio_intent_label),
                                value = uiState.physioIntentText,
                                valueFontSize = 13.sp,
                            )
                        }
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.fillMaxWidth(),
                            label = stringResource(R.string.dashboard_glass_loop_physio_thermal_label),
                            value = uiState.physioThermalText,
                            valueFontSize = 13.sp,
                        )
                        if (uiState.physioUpdatedText.isNotBlank()) {
                            Text(uiState.physioUpdatedText, color = textMuted, fontSize = 9.sp)
                        }
                        Text(
                            text = stringResource(R.string.dashboard_glass_loop_physio_open_context),
                            color = skyBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    onClickLabel = stringResource(R.string.dashboard_glass_loop_physio_open_context),
                                    role = Role.Button,
                                ) { onOpenAimiContext() }
                                .padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                } else {
                    Text(stringResource(R.string.dashboard_glass_loop_physio_stale), color = textMuted, fontSize = 11.sp)
                }
            }

            // CARD 2: KEY FACTORS
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Tune, null, tint = emerald, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgGreen,
                title = stringResource(R.string.dashboard_glass_loop_key_factors_title),
                badge = {
                    val dayLabel = if (uiState.isWeekend)
                        stringResource(R.string.dashboard_glass_loop_weekend)
                    else
                        stringResource(R.string.dashboard_glass_loop_weekday)
                    val hourLabel = "%02d".format(uiState.hourOfDay)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(if (isDark) Color(0x14EAF1FF) else Color(0xFFE2E8F0))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            stringResource(R.string.dashboard_glass_loop_hour_badge, hourLabel, dayLabel),
                            color = textMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_max_iob_label),
                            value = uiState.maxIobProfileText,
                            valueFontSize = 13.sp,
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_max_smb_label),
                            value = uiState.maxSmbProfileText,
                            valueFontSize = 13.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_isf_label),
                            value = uiState.isfText,
                            valueFontSize = 13.sp,
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_stable_bg_label),
                            value = uiState.stableMinutesText,
                            valueFontSize = 13.sp,
                        )
                    }
                }
            }

            // CARD 3: SAFETY & ALARMS
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Shield, null, tint = amber, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgAmber,
                title = stringResource(R.string.dashboard_glass_loop_safety_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    SafetyRow(
                        stringResource(R.string.dashboard_glass_loop_tir_low_label),
                        stringResource(R.string.dashboard_glass_loop_tir_low_value, uiState.tirLow1hText, uiState.tirLow24hText),
                        emerald,
                        labelColor = textMuted,
                        dividerColor = dividerSoft,
                    )
                    SafetyRow(
                        stringResource(R.string.dashboard_glass_loop_steps_label),
                        uiState.steps5mText,
                        textMuted,
                        labelColor = textMuted,
                        dividerColor = dividerSoft,
                        showDivider = false
                    )
                }
            }

            // CARD 2b: ML TRAINING
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = {
                    Icon(
                        Icons.Default.Psychology,
                        null,
                        tint = if (!uiState.hasMlTraining) textMuted else if (uiState.mlCircuitOpen) amber else emerald,
                        modifier = Modifier.size(16.dp)
                    )
                },
                iconBg = if (!uiState.hasMlTraining) cellBg else if (uiState.mlCircuitOpen) iconBgAmber else iconBgGreen,
                title = stringResource(R.string.dashboard_glass_loop_ml_training_title)
            ) {
                if (uiState.hasMlTraining) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCell(
                                isDark, cellBg, cellBorder, Modifier.weight(1f),
                                label = stringResource(R.string.dashboard_glass_loop_ml_last_trained_label),
                                value = uiState.mlLastTrainedText,
                                valueFontSize = 13.sp,
                            )
                            MetricCell(
                                isDark, cellBg, cellBorder, Modifier.weight(1f),
                                label = stringResource(R.string.dashboard_glass_loop_ml_samples_label),
                                value = uiState.mlSampleCountText,
                                valueFontSize = 13.sp,
                            )
                        }
                        Text(
                            text = stringResource(
                                if (uiState.mlCircuitOpen) R.string.dashboard_glass_loop_ml_circuit_open
                                else R.string.dashboard_glass_loop_ml_circuit_closed
                            ),
                            color = if (uiState.mlCircuitOpen) amber else emerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Text(stringResource(R.string.dashboard_glass_loop_ml_training_stale), color = textMuted, fontSize = 11.sp)
                }
            }

            // CARD 2c: MODEL FILES
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Tune, null, tint = indigo, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgBlue,
                title = stringResource(R.string.dashboard_glass_loop_ml_files_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SafetyRow(
                        stringResource(R.string.dashboard_glass_loop_ml_file_basal_label),
                        uiState.basalModelFileText,
                        textMuted,
                        labelColor = textMuted,
                        dividerColor = dividerSoft,
                    )
                    SafetyRow(
                        stringResource(R.string.dashboard_glass_loop_ml_file_smb_label),
                        uiState.smbModelFileText,
                        textMuted,
                        labelColor = textMuted,
                        dividerColor = dividerSoft,
                        showDivider = false,
                    )
                }
            }

            // FOOTER
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.dashboard_glass_loop_footer, uiState.requestedSmbText, uiState.buildVersionText),
                    color = textMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
internal fun GlassCardInner(
    isDark: Boolean,
    cardBgStart: Color,
    cardBgEnd: Color,
    borderCard: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(cardBgStart, cardBgEnd)))
            .border(1.dp, borderCard, RoundedCornerShape(16.dp)),
        content = content
    )
}

@Composable
private fun SectionCard(
    isDark: Boolean,
    cardBgStart: Color,
    cardBgEnd: Color,
    borderCard: Color,
    icon: @Composable () -> Unit,
    iconBg: Color,
    title: String,
    badge: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val textMuted = if (isDark) Color(0xFF778598) else Color(0xFF64748B)
    GlassCardInner(isDark, cardBgStart, cardBgEnd, borderCard) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(iconBg), contentAlignment = Alignment.Center) {
                        icon()
                    }
                    Text(title.uppercase(), color = textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
                badge?.invoke()
            }
            Divider(color = if (isDark) Color(0x1AEAF1FF) else Color(0xFFE2E8F0), thickness = 0.5.dp)
            content()
        }
    }
}

@Composable
private fun MetricCell(
    isDark: Boolean,
    cellBg: Color,
    cellBorder: Color,
    modifier: Modifier,
    label: String,
    labelColor: Color = Color.Unspecified,
    value: String,
    valueColor: Color = Color.Unspecified,
    valueFontSize: TextUnit = 20.sp,
    subContent: @Composable (() -> Unit)? = null
) {
    val textBright = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0F172A)
    val textMuted = if (isDark) Color(0xFF778598) else Color(0xFF64748B)
    val finalValueColor = if (valueColor != Color.Unspecified) valueColor else textBright

    GlassCardInner(isDark, cellBg, cellBg, cellBorder, modifier) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = if (labelColor != Color.Unspecified) labelColor else textMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Text(
                value,
                color = finalValueColor,
                fontSize = valueFontSize,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subContent?.invoke()
        }
    }
}

@Composable
private fun SafetyRow(
    label: String,
    value: String,
    valueColor: Color,
    labelColor: Color,
    dividerColor: Color,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (showDivider) Divider(color = dividerColor, thickness = 0.5.dp)
    }
}
