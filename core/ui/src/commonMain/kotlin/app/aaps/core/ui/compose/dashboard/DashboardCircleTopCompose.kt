package app.aaps.core.ui.compose.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualityTier
import kotlinx.coroutines.delay

/**
 * Dashboard hero in commonMain.
 *
 * Ported from `plugins/main` `DashboardCircleTopCompose` (935 lines). Hoisted:
 * - `OverviewViewModel.statusCardState` (LiveData) → [DashboardHeroUiState] param
 * - `DashboardEmbeddedComposeState` fields → explicit params/callbacks
 * - `Preferences` reads → [extendedMetrics]/[showAimiPulse] params + [onExtendedMetricsChanged]
 * - `AndroidView(FrameLayout)` auditor host → [auditorContent] slot
 * - `R.string`/`colorResource`/`dimensionResource` → [DashboardHeroStrings] + [DashboardHeroColors]
 * - `ToastUtils`/`OKDialog` → [onToast]/[onShowDialog] callbacks
 * - `DashboardComposeHeroUiMapper.buildHeroState` → [heroRingState] param (platform builds it)
 * - `trendArrowRes`/`unicornImageRes` → [TrendArrow]/[UnicornMood] enums
 */

/** Trend arrow direction (replaces the Android drawable resource ID). */
enum class TrendArrow { FLAT, UP, DOWN, DOUBLE_UP, DOUBLE_DOWN }

/** Unicorn mood (replaces the Android drawable resource ID). */
enum class UnicornMood { NORMAL_STABLE, HAPPY, WORRIED, SLEEPING }

/**
 * Hoisted hero UI state. All text is pre-formatted by the platform;
 * colors are ARGB ints (use `Color(argb)`).
 */
data class DashboardHeroUiState(
    val glucoseText: String = "---",
    val glucoseColorArgb: Int = 0xFFFFFFFF.toInt(),
    val trendArrow: TrendArrow? = null,
    val trendDescription: String = "",
    val deltaText: String = "--",
    val iobText: String = "--",
    val cobText: String = "0g",
    val loopStatusText: String = "Loop",
    val timeAgo: String = "--",
    val timeAgoDescription: String = "--",
    val isGlucoseActual: Boolean = false,
    val contentDescription: String = "Dashboard loading",
    val pumpStatusText: String = "",
    val targetText: String? = null,
    val glucoseMgdl: Int? = null,
    val noseAngleDeg: Float? = null,
    val reservoirText: String? = null,
    val infusionAgeText: String? = null,
    val pumpBatteryText: String? = null,
    val sensorAgeText: String? = null,
    val stepsText: String? = null,
    val hrText: String? = null,
    val tbrRateText: String? = null,
    val tbrRateCompactText: String? = null,
    val tirVeryLow: Double? = null,
    val tirLow: Double? = null,
    val tirTarget: Double? = null,
    val tirHigh: Double? = null,
    val tirVeryHigh: Double? = null,
    val tirStatsLine: String = "",
    val insightT3c: String? = null,
    val insightManoeuvre: String? = null,
    val insightFactor: String? = null,
    val aimiHealthScore: Double? = null,
    val aimiAdaptationSummary: String = "",
    val aimiAdaptationContentDescription: String = "",
    val aimiAdaptationHasAttention: Boolean = false,
    val aimiPulseTitle: String = "",
    val aimiPulseSummary: String = "",
    val aimiPulseMeta: String = "",
    val aimiPulseHypoRisk: Boolean = false,
    val adaptiveSmoothingQualityTier: AdaptiveSmoothingQualityTier? = null,
    val adaptiveSmoothingQualityBadgeText: String = "",
    val adaptiveSmoothingQualityDialogMessage: String = "",
    /** Pre-built hero ring state, or null to show the placeholder. */
    val heroRingState: GlucoseHeroUiState? = null,
    /** Warm-up countdown text (mm:ss), or null when not warming up. */
    val warmupCountdownText: String? = null,
    val warmupActive: Boolean = false,
    val warmupMessage: String? = null,
    /** Lifecycle subtext ("beginning of life" / "expires soon"), or null. */
    val lifecycleSubtext: String? = null,
    val contextIndicatorVisible: Boolean = false,
    val unicornMood: UnicornMood = UnicornMood.NORMAL_STABLE,
    /** Pre-built reading line for the CGM badge dialog. */
    val readingLine: String = "",
    /** Staging card state; null hides the staging card. */
    val stagingState: DashboardStagingUiState? = null,
)

/** Resolved strings for the hero. */
data class DashboardHeroStrings(
    val back: String,
    val aimiContext: String,
    val warmupA11y: String,
    val metricsOverflowA11y: String,
    val metricsModeSimple: String,
    val metricsModeExtended: String,
    val dashboardMetricsTitle: String,
    val stepsLabel: String,
    val iobLabel: String,
    val hrLabel: String,
    val tbrLabel: String,
    val sensorBadgeShort: (String) -> String,
    val sensorDialogTitle: String,
    val cgmSmoothingChipTwo: (String, String) -> String,
    val cgmSmoothingDialogTitle: String,
    val heroStatusSensorLine: (String) -> String,
    val heroStatusReadingLine: (String) -> String,
    val aimiAdaptationLabel: String,
    val mlStripSeparator: String,
    val tirVeryLowLabel: String,
    val tirLowLabel: String,
    val tirTargetLabel: String,
    val tirHighLabel: String,
    val tirVeryHighLabel: String,
)

/** Hoisted colors (replaces `colorResource`). */
data class DashboardHeroColors(
    val chipBorder: Color,
    val metricAttention: Color,
    val metricInfo: Color,
    val chipBorderWarning: Color,
)

@Composable
fun DashboardCircleTopCompose(
    state: DashboardHeroUiState,
    strings: DashboardHeroStrings,
    colors: DashboardHeroColors,
    cardStrings: DashboardCardStrings,
    extendedMetrics: Boolean,
    onExtendedMetricsChanged: (Boolean) -> Unit,
    showAimiPulse: Boolean,
    /** Slot replacing the Android `AndroidView(FrameLayout)` auditor host. */
    auditorContent: @Composable () -> Unit,
    onPromoteStaging: () -> Unit,
    onToast: (String) -> Unit,
    onShowDialog: (title: String, message: String) -> Unit,
    quickActionsStrings: QuickActionsStrings,
    layoutProfile: DashboardHeroLayoutProfile = DashboardHeroLayoutProfile.Default,
    modifier: Modifier = Modifier,
) {
    val commands = LocalDashboardHeroCommands.current
    var metricsMenuExpanded by remember { mutableStateOf(false) }

    // One-shot staging-promotion feedback was hoisted from the view model.
    // The platform calls onToast when promotion events arrive.

    val simpleOneScreen = layoutProfile == DashboardHeroLayoutProfile.SimpleOneScreen
    val heroSize = if (simpleOneScreen) 108.dp else 120.dp
    val warmupHeroActive = state.warmupActive && !state.isGlucoseActual

    // Warm-up countdown ticks every second while active.
    var warmupTick by remember { mutableStateOf(0L) }
    LaunchedEffect(warmupHeroActive) {
        if (warmupHeroActive) {
            while (true) {
                warmupTick = (warmupTick + 1) % Long.MAX_VALUE
                delay(1000L)
            }
        }
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
                    if (simpleOneScreen && state.heroRingState != null && !warmupHeroActive && state.deltaText.isNotBlank()) {
                        Text(
                            text = state.deltaText,
                            style = MaterialTheme.typography.titleSmall,
                            color = Color(state.glucoseColorArgb),
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
                        val ringState = state.heroRingState
                        if (ringState != null) {
                            val heroContentDescription = if (warmupHeroActive) {
                                state.warmupMessage?.takeIf { it.isNotBlank() }
                                    ?: strings.warmupA11y
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
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant,
                                        RoundedCornerShape(999.dp)
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(text = "---", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        if (state.contextIndicatorVisible) {
                            Icon(
                                imageVector = Icons.Filled.School,
                                contentDescription = strings.aimiContext,
                                modifier = Modifier
                                    .padding(if (simpleOneScreen) 4.dp else 8.dp)
                                    .size(if (simpleOneScreen) 24.dp else 28.dp)
                                    .clickable { commands.openContextFromBadge() },
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        // Auditor host slot (was AndroidView(FrameLayout)).
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(if (simpleOneScreen) 4.dp else 8.dp)
                                .size(if (simpleOneScreen) 24.dp else 28.dp),
                        ) {
                            auditorContent()
                        }
                    }
                    if (!warmupHeroActive) {
                        state.lifecycleSubtext?.let { subtext ->
                            Text(
                                text = subtext,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .semantics { contentDescription = subtext },
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
                                state = state,
                                strings = strings,
                                colors = colors,
                                sensorAge = sensor,
                                smoothingLabel = smoothingLabel,
                                compact = simpleOneScreen,
                                onShowDialog = onShowDialog,
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
                                        contentDescription = strings.metricsOverflowA11y,
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
                                                    text = strings.metricsModeSimple,
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
                                            onExtendedMetricsChanged(false)
                                            metricsMenuExpanded = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = strings.metricsModeExtended,
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
                                            onExtendedMetricsChanged(true)
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
                                    onClick = { onExtendedMetricsChanged(false) },
                                    label = {
                                        Text(
                                            text = strings.metricsModeSimple,
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
                                    onClick = { onExtendedMetricsChanged(true) },
                                    label = {
                                        Text(
                                            text = strings.metricsModeExtended,
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
                        text = state.readingLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            state.stagingState?.let { staging ->
                DashboardStagingCard(
                    state = staging,
                    strings = cardStrings,
                    onPromote = onPromoteStaging,
                    modifier = Modifier.padding(top = if (simpleOneScreen) 6.dp else 8.dp),
                )
            }

            CompactMetricRow(
                state = state,
                strings = strings,
                usePillBadges = simpleOneScreen,
                modifier = Modifier.padding(top = if (simpleOneScreen) 6.dp else 8.dp),
            )

            if (extendedMetrics) {
                ExtendedMetricsBlock(state, Modifier.padding(top = 10.dp))
                TirComposeBar(state, strings, Modifier.padding(top = 10.dp))
                InsightsBlock(state, strings, Modifier.padding(top = 8.dp))
                AimiAdaptationSummaryCard(
                    state = state,
                    strings = strings,
                    commands = commands,
                    modifier = Modifier.padding(top = AapsSpacing.medium),
                )
            }

            if (extendedMetrics && showAimiPulse) {
                AimiPulseCard(state, strings, colors, commands, Modifier.padding(top = 10.dp))
            }

            DashboardQuickActionsBar(
                onAdvisor = { commands.onAimiAdvisorClicked() },
                onAdjust = { commands.onAdjustClicked() },
                onMeal = { commands.onAimiPreferencesClicked() },
                onContext = { commands.onStatsClicked() },
                strings = quickActionsStrings,
                compact = simpleOneScreen,
                modifier = Modifier.padding(top = if (simpleOneScreen) 4.dp else 8.dp),
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
private fun HeroCgmCompactBadge(
    state: DashboardHeroUiState,
    strings: DashboardHeroStrings,
    colors: DashboardHeroColors,
    sensorAge: String,
    smoothingLabel: String,
    compact: Boolean = false,
    onShowDialog: (title: String, message: String) -> Unit,
) {
    val cgmShort = if (sensorAge.isNotEmpty()) {
        strings.sensorBadgeShort(sensorAge)
    } else {
        ""
    }
    val badgeLabel = if (compact) {
        when {
            sensorAge.isNotEmpty() -> cgmShort
            smoothingLabel.isNotEmpty() -> smoothingLabel
            else -> strings.sensorDialogTitle
        }
    } else {
        when {
            sensorAge.isNotEmpty() && smoothingLabel.isNotEmpty() ->
                strings.cgmSmoothingChipTwo(cgmShort, smoothingLabel)
            sensorAge.isNotEmpty() -> cgmShort
            smoothingLabel.isNotEmpty() -> smoothingLabel
            else -> strings.sensorDialogTitle
        }
    }
    val tier = state.adaptiveSmoothingQualityTier
    val smoothingBorder = when (tier) {
        null -> BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
        )
        AdaptiveSmoothingQualityTier.OK -> BorderStroke(
            width = 1.dp,
            color = colors.chipBorder,
        )
        AdaptiveSmoothingQualityTier.UNCERTAIN -> BorderStroke(
            width = 2.dp,
            color = colors.metricAttention,
        )
        AdaptiveSmoothingQualityTier.BAD -> BorderStroke(
            width = 2.dp,
            color = colors.chipBorderWarning,
        )
    }
    val labelColor = when (tier) {
        AdaptiveSmoothingQualityTier.UNCERTAIN -> colors.metricAttention
        AdaptiveSmoothingQualityTier.BAD -> colors.chipBorderWarning
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        onClick = {
            val parts = buildList {
                add(state.readingLine)
                if (sensorAge.isNotEmpty()) {
                    add(strings.heroStatusSensorLine(sensorAge))
                }
                if (state.adaptiveSmoothingQualityDialogMessage.isNotBlank()) {
                    add(state.adaptiveSmoothingQualityDialogMessage)
                }
            }
            onShowDialog(
                strings.cgmSmoothingDialogTitle,
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
private fun CompactMetricRow(
    state: DashboardHeroUiState,
    strings: DashboardHeroStrings,
    usePillBadges: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (usePillBadges) Arrangement.spacedBy(6.dp) else Arrangement.SpaceEvenly,
    ) {
        if (usePillBadges) {
            MetricPill(label = strings.stepsLabel, value = state.stepsText ?: "—", modifier = Modifier.weight(1f))
            MetricPill(label = strings.iobLabel, value = state.iobText.trim().ifBlank { "—" }, modifier = Modifier.weight(1f))
            MetricPill(label = strings.hrLabel, value = state.hrText ?: "—", modifier = Modifier.weight(1f))
            MetricPill(
                label = strings.tbrLabel,
                value = state.tbrRateCompactText ?: state.tbrRateText ?: "—",
                modifier = Modifier.weight(1f),
            )
        } else {
            MetricCell(strings.stepsLabel, state.stepsText ?: "—")
            MetricCell(strings.iobLabel, state.iobText.trim().ifBlank { "—" })
            MetricCell(strings.hrLabel, state.hrText ?: "—")
            MetricCell(strings.tbrLabel, state.tbrRateCompactText ?: state.tbrRateText ?: "—")
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            Text(text = value, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ExtendedMetricsBlock(state: DashboardHeroUiState, modifier: Modifier = Modifier) {
    // Extended metrics are rendered by the platform-specific block;
    // the commonMain hero keeps the slot for layout parity.
    Box(modifier = modifier.fillMaxWidth())
}

@Composable
private fun PumpCompactBadgeRow(state: DashboardHeroUiState, modifier: Modifier = Modifier) {
    val badges = listOfNotNull(
        state.reservoirText,
        state.infusionAgeText,
        state.pumpBatteryText,
    ).filter { it.isNotBlank() }
    if (badges.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        badges.forEach { text ->
            HeroPumpBadge(text = text, contentDescription = text)
        }
    }
}

@Composable
private fun HeroPumpBadge(text: String, contentDescription: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TirComposeBar(
    state: DashboardHeroUiState,
    strings: DashboardHeroStrings,
    modifier: Modifier = Modifier,
) {
    val segments = listOf(
        (state.tirVeryLow ?: 0.0) to Color(0xFF8B0000),
        (state.tirLow ?: 0.0) to Color(0xFFFF7043),
        (state.tirTarget ?: 0.0) to Color(0xFF4CAF50),
        (state.tirHigh ?: 0.0) to Color(0xFFFFB300),
        (state.tirVeryHigh ?: 0.0) to Color(0xFF8B0000),
    )
    val total = segments.sumOf { it.first }.takeIf { it > 0 } ?: 100.0
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().height(8.dp)) {
            segments.forEach { (percent, color) ->
                if (percent > 0) {
                    Box(
                        modifier = Modifier
                            .weight(percent.toFloat())
                            .height(8.dp)
                            .background(color, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        if (state.tirStatsLine.isNotBlank()) {
            Text(
                text = state.tirStatsLine,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun InsightsBlock(
    state: DashboardHeroUiState,
    strings: DashboardHeroStrings,
    modifier: Modifier = Modifier,
) {
    val health = state.aimiHealthScore ?: 1.0
    val bg: Color = when {
        health < 0.45 -> MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
        health < 0.72 -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        val sep = strings.mlStripSeparator
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
    state: DashboardHeroUiState,
    strings: DashboardHeroStrings,
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
                text = strings.aimiAdaptationLabel,
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
    state: DashboardHeroUiState,
    strings: DashboardHeroStrings,
    colors: DashboardHeroColors,
    commands: DashboardHeroCommands,
    modifier: Modifier = Modifier,
) {
    val bg = if (state.aimiPulseHypoRisk) {
        colors.metricAttention.copy(alpha = 0.2f)
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
        val sep = strings.mlStripSeparator
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
