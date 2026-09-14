package app.aaps.plugins.main.general.dashboard.compose

import android.widget.FrameLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.dashboard.GlucoseHeroRing
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel
import app.aaps.plugins.main.general.dashboard.viewmodel.StatusCardState
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Pure-Compose AIMI dashboard hero (embedded shell): targets parity with
 * [app.aaps.plugins.main.general.dashboard.views.CircleTopDashboardView] without inflating its XML.
 */
@Composable
fun DashboardCircleTopCompose(
    viewModel: OverviewViewModel,
    embeddedState: DashboardEmbeddedComposeState,
    preferences: Preferences,
    onAuditorHostAttached: (FrameLayout) -> Unit,
    layoutProfile: DashboardHeroLayoutProfile = DashboardHeroLayoutProfile.Default,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val commands = LocalDashboardHeroCommands.current

    val status by viewModel.statusCardState.observeAsState()
    val state = status ?: placeholderStatusCardState()

    var extendedMetrics by remember {
        mutableStateOf(preferences.get(BooleanKey.OverviewDashboardExtendedMetrics))
    }
    var metricsMenuExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(embeddedState.metricsPreferencesSync) {
        extendedMetrics = preferences.get(BooleanKey.OverviewDashboardExtendedMetrics)
    }
    // One-shot staging-promotion feedback hoisted from the view model (no Compose tree there).
    LaunchedEffect(Unit) {
        viewModel.promotionEvents.collect { message ->
            ToastUtils.infoToast(context, message)
        }
    }

    val simpleOneScreen = layoutProfile == DashboardHeroLayoutProfile.SimpleOneScreen
    val heroSize = if (simpleOneScreen) {
        108.dp
    } else {
        dimensionResource(R.dimen.dashboard_glucose_hero_min_side)
    }
    // Warm-up hero: active source is warming up / (re)connecting and no fresh glucose to show.
    // Mirrors the gate in [DashboardComposeHeroUiMapper.buildHeroState] so the countdown ticks and the
    // a11y / delta handling stay in sync. Null for any source that doesn't report warm-up → no effect.
    val warmupHeroActive = state.warmup?.active == true &&
        (state.glucoseMgdl == null || !state.isGlucoseActual)
    // Drive a 1 s recompute of the mm:ss countdown only while the warm-up ring is shown; otherwise the
    // tick stays 0 and the hero memoization is byte-identical to before.
    val warmupSecondTick = remember { mutableStateOf(0L) }
    LaunchedEffect(warmupHeroActive) {
        if (warmupHeroActive) {
            while (true) {
                warmupSecondTick.value = System.currentTimeMillis() / 1000L
                delay(1000L)
            }
        }
    }
    val heroState = remember(state, context, warmupSecondTick.value) {
        DashboardComposeHeroUiMapper.buildHeroState(context, state)
    }
    val cardPaddingH = if (simpleOneScreen) 10.dp else 12.dp
    val cardPaddingV = if (simpleOneScreen) 6.dp else 10.dp
    val cardBottomPadding = if (simpleOneScreen) 4.dp else 8.dp
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = cardBottomPadding),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = cardPaddingH, vertical = cardPaddingV),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (simpleOneScreen) 4.dp else 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(heroSize),
                ) {
                    if (simpleOneScreen && heroState != null && !warmupHeroActive && state.deltaText.isNotBlank()) {
                        Text(
                            text = state.deltaText,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(state.glucoseColor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(bottom = 2.dp)
                                .semantics { contentDescription = state.deltaText },
                        )
                    }
                    Box(
                        modifier = Modifier.size(heroSize),
                        contentAlignment = Alignment.TopStart,
                    ) {
                    if (heroState != null) {
                        // In the simple layout the delta is drawn above the ring, so the phase/delta
                        // sub-label is normally cleared. During warm-up we keep it — it carries the
                        // honest phase (WARM-UP / Reconnecting…) and no delta is drawn above.
                        val ringState =
                            if (simpleOneScreen && !warmupHeroActive) heroState.copy(subLeftText = "") else heroState
                        val heroContentDescription = if (warmupHeroActive) {
                            state.warmup?.message?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.dashboard_warmup_a11y)
                        } else {
                            state.contentDescription
                        }
                        GlucoseHeroRing(
                            state = ringState,
                            modifier = Modifier
                                .fillMaxSize()
                                .semantics { contentDescription = heroContentDescription }
                                .clickable { commands.openLoopDialogFromHero() },
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "---", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    if (embeddedState.contextIndicatorVisible) {
                        Icon(
                            imageVector = Icons.Filled.School,
                            contentDescription = stringResource(R.string.aimi_context),
                            modifier = Modifier
                                .padding(if (simpleOneScreen) 4.dp else 8.dp)
                                .size(if (simpleOneScreen) 24.dp else 28.dp)
                                .clickable { commands.openContextFromBadge() },
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    AndroidView(
                        factory = { FrameLayout(it) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(if (simpleOneScreen) 4.dp else 8.dp)
                            .size(if (simpleOneScreen) 24.dp else 28.dp),
                        update = { fl -> onAuditorHostAttached(fl) },
                    )
                    }
                    // Non-alarming "beginning of life" / "expires soon" subtext under the normal glucose
                    // ring (the ring itself stays a normal glucose ring). Null → nothing rendered, so the
                    // no-lifecycle path is unchanged.
                    if (!warmupHeroActive) {
                        val lifecycleSubtext = remember(state, context) {
                            DashboardComposeHeroUiMapper.buildLifecycleSubtext(context, state)
                        }
                        if (lifecycleSubtext != null) {
                            Text(
                                text = lifecycleSubtext,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .semantics { contentDescription = lifecycleSubtext },
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = if (simpleOneScreen) 6.dp else 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (simpleOneScreen) 4.dp else 6.dp),
                ) {
                    val readingOnly = buildReadingLineOnly(context, state)
                    val sensor = state.sensorAgeText?.trim().orEmpty()
                    val smoothingLabel = state.adaptiveSmoothingQualityBadgeText.trim()
                    val showCgmBadge = sensor.isNotEmpty() || state.adaptiveSmoothingQualityTier != null
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        HeroLoopCompactBadge(
                            loopStatusText = state.loopStatusText,
                            compact = simpleOneScreen,
                            onClick = { commands.openLoopDialogFromHero() },
                        )
                        if (showCgmBadge) {
                            HeroCgmCompactBadge(
                                context = context,
                                state = state,
                                readingLine = readingOnly,
                                sensorAge = sensor,
                                smoothingLabel = smoothingLabel,
                                compact = simpleOneScreen,
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        val chipHeight = if (simpleOneScreen) 28.dp else 32.dp
                        if (simpleOneScreen) {
                            Box {
                                IconButton(
                                    onClick = { metricsMenuExpanded = true },
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                    ),
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.dashboard_metrics_overflow_a11y),
                                    )
                                }
                                DropdownMenu(
                                    expanded = metricsMenuExpanded,
                                    onDismissRequest = { metricsMenuExpanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = stringResource(R.string.dashboard_metrics_mode_simple),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                                if (!extendedMetrics) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.padding(start = 8.dp),
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            if (extendedMetrics) {
                                                preferences.put(BooleanKey.OverviewDashboardExtendedMetrics, false)
                                                extendedMetrics = false
                                                embeddedState.requestMetricsPreferenceResync()
                                            }
                                            metricsMenuExpanded = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = stringResource(R.string.dashboard_metrics_mode_extended),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                                if (extendedMetrics) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Check,
                                                        contentDescription = null,
                                                        modifier = Modifier.padding(start = 8.dp),
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            if (!extendedMetrics) {
                                                preferences.put(BooleanKey.OverviewDashboardExtendedMetrics, true)
                                                extendedMetrics = true
                                                embeddedState.requestMetricsPreferenceResync()
                                            }
                                            metricsMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilterChip(
                                    selected = !extendedMetrics,
                                    onClick = {
                                        if (extendedMetrics) {
                                            preferences.put(BooleanKey.OverviewDashboardExtendedMetrics, false)
                                            extendedMetrics = false
                                            embeddedState.requestMetricsPreferenceResync()
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(R.string.dashboard_metrics_mode_simple),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.16f),
                                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    modifier = Modifier.height(chipHeight),
                                )
                                FilterChip(
                                    selected = extendedMetrics,
                                    onClick = {
                                        if (!extendedMetrics) {
                                            preferences.put(BooleanKey.OverviewDashboardExtendedMetrics, true)
                                            extendedMetrics = true
                                            embeddedState.requestMetricsPreferenceResync()
                                        }
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(R.string.dashboard_metrics_mode_extended),
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.16f),
                                        selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    modifier = Modifier.height(chipHeight),
                                )
                            }
                        }
                    }
                    PumpCompactBadgeRow(
                        state = state,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                    if (extendedMetrics) {
                        Text(
                            text = state.iobText.ifBlank { "—" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = readingOnly,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // New-sensor (staging) card: shown only during a dual-sensor overlap. Collect-only until the
            // user taps Promote — the only path that swaps the loop's glucose source. Renders nothing when
            // there is no staging sensor, so the layout is unchanged for everyone else.
            DashboardStagingCard(
                state = state,
                onPromote = { viewModel.promoteStaging() },
                modifier = Modifier.padding(top = if (simpleOneScreen) 6.dp else 8.dp),
            )

            CompactMetricRow(
                state = state,
                usePillBadges = simpleOneScreen,
                modifier = Modifier.padding(top = if (simpleOneScreen) 6.dp else 8.dp),
            )

            if (extendedMetrics) {
                ExtendedMetricsBlock(state, Modifier.padding(top = 10.dp))
                TirComposeBar(state, Modifier.padding(top = 10.dp))
                InsightsBlock(state, Modifier.padding(top = 8.dp))
                AimiAdaptationSummaryCard(
                    state = state,
                    commands = commands,
                    modifier = Modifier.padding(top = AapsSpacing.medium),
                )
            }

            if (extendedMetrics && preferences.get(BooleanKey.OverviewShowHybridDashboardAimiPulse)) {
                AimiPulseCard(state, commands, Modifier.padding(top = 10.dp))
            }

            DashboardQuickActionsBar(
                onAdvisor = { commands.onAimiAdvisorClicked() },
                onAdjust = { commands.onAdjustClicked() },
                onMeal = { commands.onAimiPreferencesClicked() },
                onContext = { commands.onStatsClicked() },
                compact = simpleOneScreen,
                modifier = Modifier.padding(top = if (simpleOneScreen) 4.dp else 8.dp),
            )
        }
    }
}

@Composable
private fun CompactMetricRow(
    state: StatusCardState,
    usePillBadges: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (usePillBadges) Arrangement.spacedBy(6.dp) else Arrangement.SpaceEvenly,
    ) {
        if (usePillBadges) {
            MetricPill(
                label = stringResource(R.string.dashboard_compact_label_steps),
                value = state.stepsText ?: "—",
                modifier = Modifier.weight(1f),
            )
            MetricPill(
                label = stringResource(R.string.dashboard_compact_label_iob),
                value = state.iobText.trim().ifBlank { "—" },
                modifier = Modifier.weight(1f),
            )
            MetricPill(
                label = stringResource(R.string.dashboard_compact_label_hr),
                value = state.hrText ?: "—",
                modifier = Modifier.weight(1f),
            )
            MetricPill(
                label = stringResource(R.string.dashboard_compact_label_tbr),
                value = state.tbrRateCompactText ?: state.tbrRateText ?: "—",
                modifier = Modifier.weight(1f),
            )
        } else {
            MetricCell(
                stringResource(R.string.dashboard_compact_label_steps),
                state.stepsText ?: "—",
            )
            MetricCell(
                stringResource(R.string.dashboard_compact_label_iob),
                state.iobText.trim().ifBlank { "—" },
            )
            MetricCell(
                stringResource(R.string.dashboard_compact_label_hr),
                state.hrText ?: "—",
            )
            MetricCell(
                stringResource(R.string.dashboard_compact_label_tbr),
                state.tbrRateCompactText ?: state.tbrRateText ?: "—",
            )
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { contentDescription = "$label $value" },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = "$label $value" },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ExtendedMetricsBlock(state: StatusCardState, modifier: Modifier = Modifier) {
    val tirStats = state.tirStatsLine.ifBlank {
        stringResource(R.string.dashboard_tir_stats_placeholder)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // COB seul : réservoir / batterie / site sont dans [PumpCompactBadgeRow] sous la boucle.
        Text(
            "${stringResource(app.aaps.core.ui.R.string.cob)}: ${state.cobText.ifBlank { "0g" }}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = tirStats,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PumpCompactBadgeRow(state: StatusCardState, modifier: Modifier = Modifier) {
    val dash = stringResource(app.aaps.core.ui.R.string.value_unavailable_short)
    val resV = state.reservoirText?.trim().orEmpty().ifBlank { dash }
    val battV = state.pumpBatteryText?.trim().orEmpty().ifBlank { dash }
    val siteV = state.infusionAgeText?.trim().orEmpty().ifBlank { dash }
    val scroll = rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeroPumpBadge(
            text = stringResource(R.string.dashboard_pump_pill_reservoir, resV),
            contentDescription = stringResource(R.string.dashboard_pump_pill_reservoir_a11y, resV),
        )
        HeroPumpBadge(
            text = stringResource(R.string.dashboard_pump_pill_battery, battV),
            contentDescription = stringResource(R.string.dashboard_pump_pill_battery_a11y, battV),
        )
        HeroPumpBadge(
            text = stringResource(R.string.dashboard_pump_pill_site, siteV),
            contentDescription = stringResource(R.string.dashboard_pump_pill_site_a11y, siteV),
        )
    }
}

@Composable
private fun HeroPumpBadge(text: String, contentDescription: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TirComposeBar(state: StatusCardState, modifier: Modifier = Modifier) {
    val vl = state.tirVeryLow ?: 0.0
    val l = state.tirLow ?: 0.0
    val tr = state.tirTarget ?: 0.0
    val h = state.tirHigh ?: 0.0
    val vh = state.tirVeryHigh ?: 0.0
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
    ) {
        TirBarSegment(vl, R.color.dashboard_tir_very_low)
        TirBarSegment(l, R.color.dashboard_tir_low)
        TirBarSegment(tr, R.color.dashboard_tir_in_range)
        TirBarSegment(h, R.color.dashboard_tir_high)
        TirBarSegment(vh, R.color.dashboard_tir_very_low)
    }
}

@Composable
private fun RowScope.TirBarSegment(percent: Double, colorRes: Int) {
    val w = kotlin.math.max(0.00001, percent / 100.0).toFloat()
    Box(
        Modifier
            .weight(w)
            .fillMaxHeight()
            .background(colorResource(colorRes)),
        contentAlignment = Alignment.Center,
    ) {
        if (percent >= 5.0) {
            Text(
                text = String.format(Locale.getDefault(), "%.0f%%", percent),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HeroLoopCompactBadge(
    loopStatusText: String,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        modifier = Modifier
            .heightIn(max = if (compact) 30.dp else 34.dp)
            .widthIn(max = if (compact) 104.dp else 220.dp)
            .semantics { contentDescription = loopStatusText },
    ) {
        Text(
            text = loopStatusText,
            modifier = Modifier.padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 4.dp else 5.dp,
            ),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun HeroCgmCompactBadge(
    context: android.content.Context,
    state: StatusCardState,
    readingLine: String,
    sensorAge: String,
    smoothingLabel: String,
    compact: Boolean = false,
) {
    val cgmShort = if (sensorAge.isNotEmpty()) {
        stringResource(R.string.dashboard_hero_sensor_badge_short, sensorAge)
    } else {
        ""
    }
    val badgeLabel = if (compact) {
        when {
            sensorAge.isNotEmpty() -> cgmShort
            smoothingLabel.isNotEmpty() -> smoothingLabel
            else -> stringResource(R.string.dashboard_hero_sensor_dialog_title)
        }
    } else {
        when {
            sensorAge.isNotEmpty() && smoothingLabel.isNotEmpty() ->
                stringResource(R.string.dashboard_hero_cgm_smoothing_chip_two, cgmShort, smoothingLabel)
            sensorAge.isNotEmpty() -> cgmShort
            smoothingLabel.isNotEmpty() -> smoothingLabel
            else -> stringResource(R.string.dashboard_hero_sensor_dialog_title)
        }
    }
    val dialogTitle = stringResource(R.string.dashboard_hero_cgm_smoothing_dialog_title)
    val tier = state.adaptiveSmoothingQualityTier
    val smoothingBorder = when (tier) {
        null ->
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            )
        AdaptiveSmoothingQualityTier.OK ->
            BorderStroke(
                width = 1.dp,
                color = colorResource(R.color.dashboard_chip_border),
            )
        AdaptiveSmoothingQualityTier.UNCERTAIN ->
            BorderStroke(
                width = 2.dp,
                color = colorResource(R.color.dashboard_metric_attention),
            )
        AdaptiveSmoothingQualityTier.BAD ->
            BorderStroke(
                width = 2.dp,
                color = colorResource(R.color.dashboard_chip_border_warning),
            )
    }
    val labelColor = when (tier) {
        AdaptiveSmoothingQualityTier.UNCERTAIN ->
            colorResource(R.color.dashboard_metric_attention)
        AdaptiveSmoothingQualityTier.BAD ->
            colorResource(R.color.dashboard_chip_border_warning)
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        onClick = {
            val parts = buildList {
                add(readingLine)
                if (sensorAge.isNotEmpty()) {
                    add(context.getString(R.string.dashboard_hero_status_sensor_line, sensorAge))
                }
                if (state.adaptiveSmoothingQualityDialogMessage.isNotBlank()) {
                    add(state.adaptiveSmoothingQualityDialogMessage)
                }
            }
            OKDialog.show(
                context,
                dialogTitle,
                parts.joinToString("\n\n"),
            )
        },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
        border = smoothingBorder,
        modifier = Modifier
            .heightIn(max = if (compact) 28.dp else 30.dp)
            .widthIn(max = if (compact) 88.dp else 118.dp)
            .semantics { contentDescription = badgeLabel },
    ) {
        Text(
            text = badgeLabel,
            color = labelColor,
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 4.dp else 5.dp,
            ),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InsightsBlock(state: StatusCardState, modifier: Modifier = Modifier) {
    val health = state.aimiHealthScore ?: 1.0
    val bg: Color = when {
        health < 0.45 -> colorResource(R.color.dashboard_metric_attention).copy(alpha = 0.25f)
        health < 0.72 -> colorResource(R.color.dashboard_metric_info).copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        val sep = stringResource(R.string.dashboard_aimi_ml_strip_separator)
        val insightsLine = buildString {
            append(state.insightT3c ?: "🎯 --")
            append(sep)
            append(state.insightManoeuvre ?: "🌀 --")
            append(sep)
            append(state.insightFactor ?: "⚡ x1.0")
        }
        Text(
            text = insightsLine,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AimiAdaptationSummaryCard(
    state: StatusCardState,
    commands: DashboardHeroCommands,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (state.aimiAdaptationHasAttention) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(
        onClick = commands::onAimiAdaptationClicked,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = state.aimiAdaptationContentDescription
            },
        shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
        ) {
            Text(
                text = stringResource(R.string.dashboard_aimi_adaptation_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = state.aimiAdaptationSummary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AimiPulseCard(
    state: StatusCardState,
    commands: DashboardHeroCommands,
    modifier: Modifier = Modifier,
) {
    val bg = if (state.aimiPulseHypoRisk) {
        colorResource(R.color.dashboard_metric_attention).copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .clickable { commands.onAimiPulseClicked() }
            .padding(10.dp),
    ) {
        val sep = stringResource(R.string.dashboard_aimi_ml_strip_separator)
        Text(
            text = "${state.aimiPulseTitle}$sep${state.aimiPulseSummary}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.aimiPulseMeta.isNotBlank()) {
            Text(
                text = state.aimiPulseMeta,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun buildReadingLineOnly(context: android.content.Context, state: StatusCardState): String {
    val readingPart = state.timeAgoDescription.trim().ifBlank { "—" }
    return context.getString(R.string.dashboard_hero_status_reading_line, readingPart)
}

private fun placeholderStatusCardState(): StatusCardState =
    StatusCardState(
        glucoseText = "---",
        glucoseColor = android.graphics.Color.WHITE,
        trendArrowRes = null,
        trendDescription = "",
        deltaText = "--",
        iobText = "--",
        cobText = "0g",
        loopStatusText = "Loop",
        loopIsRunning = true,
        timeAgo = "--",
        timeAgoDescription = "--",
        isGlucoseActual = false,
        contentDescription = "Dashboard loading",
    )
