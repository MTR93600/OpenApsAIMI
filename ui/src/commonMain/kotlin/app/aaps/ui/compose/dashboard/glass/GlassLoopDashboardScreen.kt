package app.aaps.ui.compose.dashboard.glass

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.ui.compose.glass.GlassScreenBackground
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/**
 * The loop dashboard route: collects the AIMI snapshot feed, hands it to the ViewModel, and opens the
 * AIMI context screen through the shared navigation (with its protection).
 */
@Composable
fun GlassLoopDashboardRoute(
    aimiDashboardData: Flow<AimiGlassDashboardData>,
    onBack: () -> Unit,
    onNavigate: (NavigationRequest) -> Unit,
    viewModel: GlassLoopDashboardViewModel = metroViewModel(),
) {
    val aimiData by aimiDashboardData.collectAsStateWithLifecycle(initialValue = AimiGlassDashboardData())
    // Glucose, IOB and the last run are read by the ViewModel, not taken from the feed. When AIMI
    // publishes nothing new the feed value does not change, so rebuild every minute and on every
    // return to the screen (the collection restarts on resume), as DEV refreshed on resume.
    val refreshTick by remember { tickerFlow(60_000L).map { Clock.System.now().toEpochMilliseconds() } }
        .collectAsStateWithLifecycle(initialValue = 0L)
    LaunchedEffect(aimiData, refreshTick) { viewModel.onAimiData(aimiData) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    GlassLoopDashboardScreen(
        uiState = uiState,
        onBack = onBack,
        isDark = isGlassDarkMode(),
        // No link to a screen this build does not have.
        onOpenAimiContext = if (aimiData.contextScreenAvailable) ({ onNavigate(aimiContextNavigationRequest()) }) else null,
    )
}

/** The colors of the DEV loop dashboard. */
private class LoopPalette(isDark: Boolean) {

    val cardBgStart = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)
    val cardBgEnd = if (isDark) Color(0xFF0F1C2C) else Color(0xFFF8FAFC)
    val borderCard = if (isDark) Color(0x33EAF1FF) else Color(0xFFE2E8F0)
    val textBright = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0D1B2A)
    val cellTextBright = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0F172A)
    val textMuted = if (isDark) Color(0xFF778598) else Color(0xFF64748B)
    val cellBg = if (isDark) Color(0x14EAF1FF) else Color(0xFFF1F5F9)
    val cellBorder = if (isDark) Color(0x22EAF1FF) else Color(0xFFE2E8F0)
    val iconBgBlue = if (isDark) Color(0xFF15406B) else Color(0xFFDBEAFE)
    val iconBgGreen = if (isDark) Color(0xFF0B4A3A) else Color(0xFFD1FAE5)
    val iconBgAmber = if (isDark) Color(0xFF3D2E0A) else Color(0xFFFEF3C7)
    val dividerSoft = if (isDark) Color(0x1AEAF1FF) else Color(0x1A000000)
    val sectionDivider = if (isDark) Color(0x1AEAF1FF) else Color(0xFFE2E8F0)
    val badgeBg = if (isDark) Color(0x14EAF1FF) else Color(0xFFE2E8F0)

    companion object {

        val skyBlue = Color(0xFF0984E3)
        val emerald = Color(0xFF00B894)
        val amber = Color(0xFFF59E0B)
        val red = Color(0xFFF43F5E)
        val indigo = Color(0xFF6366F1)
    }
}

/**
 * The Glass loop dashboard: last loop run, glucose dynamics, AIMI trajectory, physio, key factors,
 * safety and ML training. Port of `GlassLoopDashboardScreen` on `dev_OAPSAIMI`. Display only.
 *
 * @param onOpenAimiContext opens the AIMI context screen; null hides the link (no such screen)
 */
@Composable
fun GlassLoopDashboardScreen(
    uiState: GlassLoopDashboardState,
    onBack: () -> Unit,
    isDark: Boolean,
    onOpenAimiContext: (() -> Unit)?,
) {
    val p = LoopPalette(isDark)

    GlassScreenBackground(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            GlassDetailHeader(title = UiStrings.dashboard_glass_loop_dashboard_title, isDark = isDark, onBack = onBack)

            // HEADER CARD
            LoopCard(p.cardBgStart, p.cardBgEnd, p.borderCard) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        if (uiState.lastRunTime.isNotEmpty()) {
                            Text(
                                text = stringResource(UiStrings.dashboard_glass_loop_dashboard_last_run, uiState.lastRunTime),
                                color = p.textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(UiStrings.dashboard_glass_loop_dashboard_pump_label),
                            color = p.textMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(LoopPalette.emerald.copy(alpha = 0.12f))
                                .border(1.dp, LoopPalette.emerald.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(UiStrings.dashboard_glass_loop_dashboard_requested, uiState.requestedSmbText),
                                color = LoopPalette.emerald,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // CARD 1: GLUCOSE & INSULIN DYNAMICS
            SectionCard(
                p,
                icon = { Icon(Icons.Default.Bolt, null, tint = LoopPalette.skyBlue, modifier = Modifier.size(16.dp)) },
                iconBg = p.iconBgBlue,
                title = stringResource(UiStrings.dashboard_glass_loop_dynamics_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_glucose_label),
                            value = uiState.glucoseText,
                            subContent = {
                                val deltaColor = if (uiState.delta5mIsPositive) LoopPalette.emerald else LoopPalette.red
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(uiState.delta5mText, color = deltaColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    Text(stringResource(UiStrings.dashboard_glass_loop_delta_5m_label), color = p.textMuted, fontSize = 9.sp)
                                }
                            }
                        )
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_delta_avg_label),
                            value = stringResource(UiStrings.dashboard_glass_loop_tir_low_value, uiState.shortAvgDeltaText, uiState.longAvgDeltaText),
                            valueColor = when {
                                uiState.shortAvgDeltaIsPositive && uiState.longAvgDeltaIsPositive   -> LoopPalette.emerald
                                !uiState.shortAvgDeltaIsPositive && !uiState.longAvgDeltaIsPositive -> LoopPalette.red
                                else                                                                -> LoopPalette.amber
                            },
                            valueFontSize = 13.sp,
                            subContent = {
                                Text(stringResource(UiStrings.dashboard_glass_loop_delta_avg_hint), color = p.textMuted, fontSize = 9.sp)
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_iob_label),
                            labelColor = LoopPalette.indigo,
                            value = uiState.iobText,
                            valueColor = LoopPalette.indigo,
                            subContent = {
                                Text(stringResource(UiStrings.dashboard_glass_loop_iob_hint), color = p.textMuted, fontSize = 9.sp)
                            }
                        )
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_target_label),
                            value = uiState.targetBgText,
                        )
                    }
                    LoopCard(p.cellBg, p.cellBg, p.cellBorder) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(UiStrings.dashboard_glass_loop_tdd_label), color = p.textMuted, fontSize = 11.sp)
                            Text(uiState.tdd7DaysPerHourText, color = p.textBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // CARD 1b: TRAJECTORY
            SectionCard(
                p,
                icon = { Icon(Icons.AutoMirrored.Filled.TrendingUp, null, tint = LoopPalette.skyBlue, modifier = Modifier.size(16.dp)) },
                iconBg = p.iconBgBlue,
                title = stringResource(UiStrings.dashboard_glass_loop_trajectory_title)
            ) {
                if (uiState.hasTrajectory) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_trajectory_hybrid_label),
                            value = uiState.trajectoryHybridText,
                            valueFontSize = 16.sp,
                        )
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_trajectory_iob_label),
                            value = uiState.trajectoryIobText,
                            valueFontSize = 16.sp,
                        )
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_trajectory_cob_label),
                            value = uiState.trajectoryCobText,
                            valueFontSize = 16.sp,
                        )
                    }
                } else {
                    Text(stringResource(UiStrings.dashboard_glass_loop_trajectory_stale), color = p.textMuted, fontSize = 11.sp)
                }
            }

            // CARD 1c: PHYSIO
            SectionCard(
                p,
                icon = { Icon(Icons.Default.Bolt, null, tint = LoopPalette.indigo, modifier = Modifier.size(16.dp)) },
                iconBg = p.iconBgBlue,
                title = stringResource(UiStrings.dashboard_glass_loop_physio_title)
            ) {
                if (uiState.hasPhysio) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCell(
                                p, Modifier.weight(1f),
                                label = stringResource(UiStrings.dashboard_glass_loop_physio_mode_label),
                                value = uiState.physioModeText,
                                valueFontSize = 13.sp,
                            )
                            MetricCell(
                                p, Modifier.weight(1f),
                                label = stringResource(UiStrings.dashboard_glass_loop_physio_intent_label),
                                value = uiState.physioIntentText,
                                valueFontSize = 13.sp,
                            )
                        }
                        MetricCell(
                            p, Modifier.fillMaxWidth(),
                            label = stringResource(UiStrings.dashboard_glass_loop_physio_thermal_label),
                            value = uiState.physioThermalText,
                            valueFontSize = 13.sp,
                        )
                        if (uiState.physioUpdatedText.isNotBlank()) {
                            Text(uiState.physioUpdatedText, color = p.textMuted, fontSize = 9.sp)
                        }
                        if (onOpenAimiContext != null) {
                            val openContext = stringResource(UiStrings.dashboard_glass_loop_open_context)
                            Text(
                                text = openContext,
                                color = LoopPalette.skyBlue,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClickLabel = openContext, role = Role.Button) { onOpenAimiContext() }
                                    .padding(horizontal = 4.dp, vertical = 6.dp)
                            )
                        }
                    }
                } else {
                    Text(stringResource(UiStrings.dashboard_glass_loop_physio_stale), color = p.textMuted, fontSize = 11.sp)
                }
            }

            // CARD 2: KEY FACTORS
            SectionCard(
                p,
                icon = { Icon(Icons.Default.Tune, null, tint = LoopPalette.emerald, modifier = Modifier.size(16.dp)) },
                iconBg = p.iconBgGreen,
                title = stringResource(UiStrings.dashboard_glass_loop_key_factors_title),
                badge = {
                    val dayLabel = stringResource(
                        if (uiState.isWeekend) UiStrings.dashboard_glass_loop_weekend else UiStrings.dashboard_glass_loop_weekday
                    )
                    val hourLabel = uiState.hourOfDay.toString().padStart(2, '0')
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(p.badgeBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            stringResource(UiStrings.dashboard_glass_loop_hour_badge, hourLabel, dayLabel),
                            color = p.textMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_max_iob_label),
                            value = uiState.maxIobProfileText,
                            valueFontSize = 13.sp,
                        )
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_max_smb_label),
                            value = uiState.maxSmbProfileText,
                            valueFontSize = 13.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_isf_label),
                            value = uiState.isfText,
                            valueFontSize = 13.sp,
                        )
                        MetricCell(
                            p, Modifier.weight(1f),
                            label = stringResource(UiStrings.dashboard_glass_loop_stable_bg_label),
                            value = uiState.stableMinutesText,
                            valueFontSize = 13.sp,
                        )
                    }
                }
            }

            // CARD 3: SAFETY & ALARMS
            SectionCard(
                p,
                icon = { Icon(Icons.Default.Shield, null, tint = LoopPalette.amber, modifier = Modifier.size(16.dp)) },
                iconBg = p.iconBgAmber,
                title = stringResource(UiStrings.dashboard_glass_loop_safety_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    SafetyRow(
                        stringResource(UiStrings.dashboard_glass_loop_tir_low_label),
                        stringResource(UiStrings.dashboard_glass_loop_tir_low_value, uiState.tirLow1hText, uiState.tirLow24hText),
                        LoopPalette.emerald,
                        labelColor = p.textMuted,
                        dividerColor = p.dividerSoft,
                    )
                    SafetyRow(
                        stringResource(UiStrings.dashboard_glass_loop_steps_label),
                        uiState.steps5mText,
                        p.textMuted,
                        labelColor = p.textMuted,
                        dividerColor = p.dividerSoft,
                        showDivider = false
                    )
                }
            }

            // CARD 2b: ML TRAINING
            SectionCard(
                p,
                icon = {
                    Icon(
                        Icons.Default.Psychology,
                        null,
                        tint = if (!uiState.hasMlTraining) p.textMuted else if (uiState.mlCircuitOpen) LoopPalette.amber else LoopPalette.emerald,
                        modifier = Modifier.size(16.dp)
                    )
                },
                iconBg = if (!uiState.hasMlTraining) p.cellBg else if (uiState.mlCircuitOpen) p.iconBgAmber else p.iconBgGreen,
                title = stringResource(UiStrings.dashboard_glass_loop_ml_training_title)
            ) {
                if (uiState.hasMlTraining) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCell(
                                p, Modifier.weight(1f),
                                label = stringResource(UiStrings.dashboard_glass_loop_ml_last_trained_label),
                                value = uiState.mlLastTrainedText,
                                valueFontSize = 13.sp,
                            )
                            MetricCell(
                                p, Modifier.weight(1f),
                                label = stringResource(UiStrings.dashboard_glass_loop_ml_samples_label),
                                value = uiState.mlSampleCountText,
                                valueFontSize = 13.sp,
                            )
                        }
                        Text(
                            text = stringResource(
                                if (uiState.mlCircuitOpen) UiStrings.dashboard_glass_loop_ml_circuit_open
                                else UiStrings.dashboard_glass_loop_ml_circuit_closed
                            ),
                            color = if (uiState.mlCircuitOpen) LoopPalette.amber else LoopPalette.emerald,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Text(stringResource(UiStrings.dashboard_glass_loop_ml_training_stale), color = p.textMuted, fontSize = 11.sp)
                }
                SafetyRow(
                    stringResource(UiStrings.dashboard_glass_loop_ml_smb_label),
                    uiState.smbTrainingStatusText,
                    p.textMuted,
                    labelColor = p.textMuted,
                    dividerColor = p.dividerSoft,
                    showDivider = false,
                )
                if (uiState.smbTrainingDetailText.isNotEmpty()) {
                    SafetyRow(
                        stringResource(UiStrings.dashboard_glass_loop_ml_smb_detail_label),
                        uiState.smbTrainingDetailText,
                        p.textMuted,
                        labelColor = p.textMuted,
                        dividerColor = p.dividerSoft,
                        showDivider = false,
                    )
                }
            }

            // CARD 2c: MODEL FILES
            SectionCard(
                p,
                icon = { Icon(Icons.Default.Tune, null, tint = LoopPalette.indigo, modifier = Modifier.size(16.dp)) },
                iconBg = p.iconBgBlue,
                title = stringResource(UiStrings.dashboard_glass_loop_ml_files_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SafetyRow(
                        stringResource(UiStrings.dashboard_glass_loop_ml_file_basal_label),
                        uiState.basalModelFileText,
                        p.textMuted,
                        labelColor = p.textMuted,
                        dividerColor = p.dividerSoft,
                    )
                    SafetyRow(
                        stringResource(UiStrings.dashboard_glass_loop_ml_file_smb_label),
                        uiState.smbModelFileText,
                        p.textMuted,
                        labelColor = p.textMuted,
                        dividerColor = p.dividerSoft,
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
                    stringResource(UiStrings.dashboard_glass_loop_footer, uiState.requestedSmbText, uiState.buildVersionText),
                    color = p.textMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun LoopCard(
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
    p: LoopPalette,
    icon: @Composable () -> Unit,
    iconBg: Color,
    title: String,
    badge: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    LoopCard(p.cardBgStart, p.cardBgEnd, p.borderCard) {
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
                    Text(title.uppercase(), color = p.textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
                badge?.invoke()
            }
            HorizontalDivider(color = p.sectionDivider, thickness = 0.5.dp)
            content()
        }
    }
}

@Composable
private fun MetricCell(
    p: LoopPalette,
    modifier: Modifier,
    label: String,
    labelColor: Color = Color.Unspecified,
    value: String,
    valueColor: Color = Color.Unspecified,
    valueFontSize: TextUnit = 20.sp,
    subContent: @Composable (() -> Unit)? = null
) {
    val finalValueColor = if (valueColor != Color.Unspecified) valueColor else p.cellTextBright
    LoopCard(p.cellBg, p.cellBg, p.cellBorder, modifier) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                color = if (labelColor != Color.Unspecified) labelColor else p.textMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
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
        if (showDivider) HorizontalDivider(color = dividerColor, thickness = 0.5.dp)
    }
}
