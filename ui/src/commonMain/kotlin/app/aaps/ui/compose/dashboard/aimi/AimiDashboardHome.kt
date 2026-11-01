package app.aaps.ui.compose.dashboard.aimi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.overview.graph.BgInfoData
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.CgmSensorStatusProvider
import app.aaps.core.interfaces.source.CgmStagingEvidence
import app.aaps.core.interfaces.source.CgmWarmupProvider
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntNonKey
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalClientRole
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.LocalSnackbarHostState
import app.aaps.core.ui.compose.dashboard.DashboardCardStrings
import app.aaps.core.ui.compose.dashboard.DashboardCircleTopCompose
import app.aaps.core.ui.compose.dashboard.DashboardHeroColors
import app.aaps.core.ui.compose.dashboard.DashboardHeroLayoutProfile
import app.aaps.core.ui.compose.dashboard.DashboardHeroPalette
import app.aaps.core.ui.compose.dashboard.DashboardHeroSensorStrings
import app.aaps.core.ui.compose.dashboard.DashboardHeroStrings
import app.aaps.core.ui.compose.dashboard.DashboardHeroUiMapper
import app.aaps.core.ui.compose.dashboard.DashboardHeroUiState
import app.aaps.core.ui.compose.dashboard.DashboardNotificationUiItem
import app.aaps.core.ui.compose.dashboard.DashboardNotificationsComposeList
import app.aaps.core.ui.compose.dashboard.DashboardStagingUiState
import app.aaps.core.ui.compose.dashboard.GlucoseHeroUiState
import app.aaps.core.ui.compose.dashboard.HeroStatusCardState
import app.aaps.core.ui.compose.dashboard.LocalDashboardHeroCommands
import app.aaps.core.ui.compose.dashboard.QuickActionsStrings
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.formatKmp
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.DashboardGraphSection
import app.aaps.ui.compose.dashboard.glass.GlassHomeStatus
import app.aaps.ui.compose.dashboard.glass.GlassHomeViewModel
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The AIMI dashboard home: glucose hero circle with quick actions and the new-sensor card, the in-app
 * notifications and the glucose graph. Port of `AimiDashboardComposeEmbedded` on `dev_OAPSAIMI`, the
 * home of its default `SkinMinimal`, for Android and iOS alike.
 *
 * Three layouts, as on DEV:
 * - extended metrics: one scrolling column (hero, full notification list, graph);
 * - simple, wider than high: hero and compact notifications on the left, graph on the right;
 * - simple, higher than wide: hero, compact notifications, and the graph in the space left.
 *
 * It replaces only the home content of `MainScreen`; the shell top bar, drawer and bottom bar stay.
 *
 * @param commandsAllowed false on an unpaired client: the hero commands that change the loop or start
 *   a treatment then do nothing, as the status lights hide the same buttons
 * @param cgmSource the active BG source when it reports a warm-up; also its staging slot when it has one
 * @param availablePluginClassNames simple class names of the plugins in this build; the AIMI actions
 *   need `OpenAPSAIMIPlugin` and are not offered without it
 */
@Composable
fun AimiDashboardHome(
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    notifications: List<AapsNotification>,
    onDismissNotification: (AapsNotification) -> Unit,
    onNotificationActionClick: (AapsNotification) -> Unit,
    aimiDashboardData: Flow<AimiGlassDashboardData>,
    cgmSource: CgmWarmupProvider?,
    availablePluginClassNames: Set<String>,
    commandsAllowed: Boolean,
    onNavigate: (NavigationRequest) -> Unit,
    modifier: Modifier = Modifier,
    homeViewModel: GlassHomeViewModel = metroViewModel(),
    dashboardViewModel: AimiDashboardViewModel = metroViewModel(),
) {
    val preferences = LocalPreferences.current
    val dateUtil = LocalDateUtil.current
    val profileUtil = LocalProfileUtil.current
    val clientRole = LocalClientRole.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    val extendedMetrics by preferences.observe(BooleanKey.OverviewDashboardExtendedMetrics).collectAsStateWithLifecycle()
    val showAimiPulse by preferences.observe(BooleanKey.OverviewShowHybridDashboardAimiPulse).collectAsStateWithLifecycle()
    val storedRange by preferences.observe(IntNonKey.RangeToDisplay).collectAsStateWithLifecycle()
    val rangeHours = AimiDashboardGraphRange.clamp(storedRange)

    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    // 30 s ticker: moves the "N min ago" text between refreshes.
    val nowMs by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()
    val iobState by chipsViewModel.iobUiState.collectAsStateWithLifecycle()
    val cobState by chipsViewModel.cobUiState.collectAsStateWithLifecycle()
    val homeStatus by homeViewModel.status.collectAsStateWithLifecycle()
    val status by dashboardViewModel.status.collectAsStateWithLifecycle()
    val aimiData by aimiDashboardData.collectAsStateWithLifecycle(initialValue = AimiGlassDashboardData())
    val healthStoreStepsToday = aimiData.safety?.stepsToday
    LaunchedEffect(healthStoreStepsToday) { homeViewModel.onHealthStoreStepsToday(healthStoreStepsToday) }
    LaunchedEffect(dashboardViewModel) {
        dashboardViewModel.messages.collect { message -> snackbar.showSnackbar(message) }
    }

    // A source without warm-up or staging reports reads these empty flows instead.
    val sensor = cgmSource as? CgmSensorStatusProvider
    val noWarmup = remember { MutableStateFlow<CgmWarmupStatus?>(null) }
    val noLifecycle = remember { MutableStateFlow<CgmSensorLifecycle?>(null) }
    val noStaging = remember { MutableStateFlow(StagingState.ABSENT) }
    val noEvidence = remember { MutableStateFlow<CgmStagingEvidence?>(null) }
    val warmup by (cgmSource?.warmupStatus ?: noWarmup).collectAsStateWithLifecycle()
    val lifecycle by (sensor?.lifecycle ?: noLifecycle).collectAsStateWithLifecycle()
    val stagingSlot by (sensor?.stagingState ?: noStaging).collectAsStateWithLifecycle()
    val stagingWarmup by (sensor?.stagingWarmupStatus ?: noWarmup).collectAsStateWithLifecycle()
    val stagingLifecycle by (sensor?.stagingLifecycle ?: noLifecycle).collectAsStateWithLifecycle()
    val stagingEvidence by (sensor?.stagingEvidence ?: noEvidence).collectAsStateWithLifecycle()

    val sensorStrings = rememberSensorStrings()
    val palette = heroPalette()
    val bgInfo = bgInfoState.bgInfo
    val glucoseMgdl = bgInfo?.let { profileUtil.convertToMgdl(it.bgValue, profileUtil.units) }
    val deltaMgdl = bgInfo?.delta?.let { profileUtil.convertToMgdl(it, profileUtil.units) }
    val isGlucoseActual = bgInfo != null && !bgInfo.isOutdated
    val timeAgo = remember(bgInfo, nowMs) { bgInfo?.let { dateUtil.minAgoShort(it.timestamp) } ?: "" }
    val deltaValue = bgInfo?.shortAvgDeltaText?.takeIf { bgInfo.shortAvgDelta?.isFinite() == true }
        ?: bgInfo?.deltaText
        ?: stringResource(CoreUiStrings.value_unavailable_short)
    val deltaText = stringResource(UiStrings.dashboard_glass_delta, deltaValue)
    val glucoseColor = bgColor(bgInfo)
    val ringInput = HeroStatusCardState(
        glucoseText = bgInfo?.bgText ?: NO_GLUCOSE,
        glucoseColor = glucoseColor,
        deltaText = deltaText,
        timeAgo = timeAgo,
        isGlucoseActual = isGlucoseActual,
        glucoseMgdl = glucoseMgdl?.toInt(),
        noseAngleDeg = AimiDashboardHeroRules.noseAngle(deltaMgdl),
        trajectoryRelevanceScore = status.trajectoryRelevanceScore,
        aimiHealthScore = status.aimiHealthScore,
        adaptiveSmoothingQualityTier = status.smoothingTier,
        warmup = warmup,
        lifecycle = lifecycle,
    )
    val heroState = buildHeroState(
        ringInput = ringInput,
        bgInfo = bgInfo,
        status = status,
        homeStatus = homeStatus,
        iobTotal = iobState.iobTotal.takeIf { iobState.text.isNotEmpty() },
        cobText = cobState.text,
        heroRing = DashboardHeroUiMapper.buildHeroState(ringInput, palette, sensorStrings) { dateUtil.timeString(it) },
        lifecycleSubtext = DashboardHeroUiMapper.buildLifecycleSubtext(ringInput, sensorStrings),
        staging = DashboardStagingUiState(stagingSlot, stagingWarmup, stagingLifecycle, stagingEvidence)
            .takeIf { stagingSlot != StagingState.ABSENT },
    )

    val commands = remember(onNavigate, commandsAllowed, clientRole, availablePluginClassNames) {
        AimiDashboardNavigationCommands(onNavigate, commandsAllowed, clientRole, availablePluginClassNames)
    }
    val heroStrings = rememberHeroStrings()
    val cardStrings = dashboardCardStrings()
    val quickActionsStrings = rememberQuickActionsStrings()
    val heroColors = DashboardHeroColors(
        chipBorder = MaterialTheme.colorScheme.outline,
        metricAttention = AapsTheme.generalColors.statusWarning,
        metricInfo = MaterialTheme.colorScheme.primary,
        chipBorderWarning = AapsTheme.generalColors.statusCritical,
    )
    var dialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    dialog?.let { (title, message) -> OkDialog(title = title, message = message, onDismiss = { dialog = null }) }

    val notificationItems = notifications.map { it.toDashboardItem() }
    val onDismissItem: (Int) -> Unit = { id ->
        notifications.firstOrNull { it.instanceKey == id }?.let { notification ->
            notification.actions.firstOrNull()?.let { action ->
                action.action()
                onNotificationActionClick(notification)
            }
            onDismissNotification(notification)
        }
    }

    val hero: @Composable (DashboardHeroLayoutProfile) -> Unit = { profile ->
        DashboardCircleTopCompose(
            state = heroState,
            strings = heroStrings,
            colors = heroColors,
            cardStrings = cardStrings,
            extendedMetrics = extendedMetrics,
            onExtendedMetricsChanged = { preferences.put(BooleanKey.OverviewDashboardExtendedMetrics, it) },
            showAimiPulse = showAimiPulse,
            auditorContent = {
                if (commands.auditorAvailable) {
                    Icon(
                        imageVector = Icons.Filled.Assessment,
                        contentDescription = stringResource(UiStrings.dashboard_v2_tool_auditor_report),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(onClick = commands::openAuditorReport),
                    )
                }
            },
            onPromoteStaging = { sensor?.let { dashboardViewModel.promoteStaging(it) } },
            onToast = { message -> scope.launch { snackbar.showSnackbar(message) } },
            onShowDialog = { title, message -> dialog = title to message },
            quickActionsStrings = quickActionsStrings,
            layoutProfile = profile,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    val graph: @Composable (Modifier, Boolean) -> Unit = { graphModifier, expand ->
        DashboardGraphSection(
            graphViewModel = graphViewModel,
            selectedRangeHours = rangeHours,
            onSelectRange = { preferences.put(IntNonKey.RangeToDisplay, AimiDashboardGraphRange.clamp(it)) },
            onNavigate = onNavigate,
            hideDetailedGraphStatus = !extendedMetrics,
            expandVertically = expand,
            // As on DEV: a double tap shows the next range, a long press goes back to 6 h.
            onDoubleTap = { preferences.put(IntNonKey.RangeToDisplay, AimiDashboardGraphRange.next(rangeHours)) },
            onLongPress = { preferences.put(IntNonKey.RangeToDisplay, AimiDashboardGraphRange.DEFAULT) },
            modifier = graphModifier,
        )
    }
    val notificationList: @Composable (Boolean, Modifier) -> Unit = { compact, listModifier ->
        DashboardNotificationsComposeList(
            notifications = notificationItems,
            strings = cardStrings,
            onDismissNotification = onDismissItem,
            compact = compact,
            modifier = listModifier,
        )
    }

    CompositionLocalProvider(LocalDashboardHeroCommands provides commands) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            when {
                extendedMetrics -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    hero(DashboardHeroLayoutProfile.Default)
                    notificationList(
                        false,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AapsSpacing.large)
                            .padding(top = AapsSpacing.medium, bottom = AapsSpacing.medium),
                    )
                    graph(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AapsSpacing.large)
                            .padding(bottom = AapsSpacing.medium),
                        false,
                    )
                }

                landscape       -> Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(LANDSCAPE_HERO_WEIGHT)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(start = AapsSpacing.large),
                    ) {
                        hero(DashboardHeroLayoutProfile.SimpleOneScreen)
                        notificationList(
                            true,
                            Modifier
                                .fillMaxWidth()
                                .padding(end = AapsSpacing.large)
                                .padding(top = AapsSpacing.small, bottom = AapsSpacing.small),
                        )
                    }
                    graph(
                        Modifier
                            .weight(1f - LANDSCAPE_HERO_WEIGHT)
                            .fillMaxHeight()
                            .padding(start = AapsSpacing.medium, end = AapsSpacing.large, bottom = AapsSpacing.medium),
                        true,
                    )
                }

                else            -> Column(modifier = Modifier.fillMaxSize()) {
                    hero(DashboardHeroLayoutProfile.SimpleOneScreen)
                    notificationList(
                        true,
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AapsSpacing.large)
                            .padding(top = AapsSpacing.small, bottom = AapsSpacing.small),
                    )
                    graph(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = AapsSpacing.large)
                            .padding(bottom = AapsSpacing.medium),
                        true,
                    )
                }
            }
        }
    }
}

/** Puts the values of the shared view models and of [AimiDashboardViewModel] into the hero state. */
@Composable
private fun buildHeroState(
    ringInput: HeroStatusCardState,
    bgInfo: BgInfoData?,
    status: AimiDashboardStatus,
    homeStatus: GlassHomeStatus,
    iobTotal: Double?,
    cobText: String,
    heroRing: GlucoseHeroUiState?,
    lifecycleSubtext: String?,
    staging: DashboardStagingUiState?,
): DashboardHeroUiState {
    val dash = stringResource(CoreUiStrings.value_unavailable_short)
    val iobText = iobTotal?.let {
        if (it >= 0) stringResource(InterfacesStrings.format_insulin_units, it)
        else stringResource(CoreUiStrings.format_insulin_units_signed, it)
    } ?: dash
    val pump = homeStatus.pumpStatus
    val reservoir = pump?.reservoirText ?: dash
    val battery = status.pumpBatteryText ?: dash
    val site = pump?.siteAgeText ?: dash
    val warmupActive = ringInput.warmup?.active == true && !ringInput.isGlucoseActual
    return DashboardHeroUiState(
        glucoseText = ringInput.glucoseText,
        glucoseColorArgb = ringInput.glucoseColor,
        trendDescription = bgInfo?.trendDescription.orEmpty(),
        deltaText = ringInput.deltaText,
        iobText = iobText,
        cobText = if (cobText.isNotBlank()) stringResource(UiStrings.dashboard_cob_line, cobText) else "",
        loopStatusText = homeStatus.loopStatusText ?: stringResource(CoreUiStrings.closedloop),
        timeAgo = ringInput.timeAgo,
        timeAgoDescription = ringInput.timeAgo,
        isGlucoseActual = ringInput.isGlucoseActual,
        contentDescription = stringResource(UiStrings.dashboard_hero_a11y, ringInput.glucoseText, ringInput.timeAgo),
        glucoseMgdl = ringInput.glucoseMgdl,
        noseAngleDeg = ringInput.noseAngleDeg,
        reservoirText = stringResource(UiStrings.dashboard_pump_pill_reservoir, reservoir),
        infusionAgeText = stringResource(UiStrings.dashboard_pump_pill_site, site),
        pumpBatteryText = stringResource(UiStrings.dashboard_pump_pill_battery, battery),
        sensorAgeText = pump?.sensorAgeText,
        stepsText = homeStatus.stepsText,
        hrText = homeStatus.hrText,
        tbrRateText = status.tbrRateText,
        tbrRateCompactText = status.tbrRateCompactText,
        tirVeryLow = status.tir?.veryLow,
        tirLow = status.tir?.low,
        tirTarget = status.tir?.target,
        tirHigh = status.tir?.high,
        tirVeryHigh = status.tir?.veryHigh,
        tirStatsLine = status.tirStatsLine,
        insightT3c = status.insightT3c,
        insightManoeuvre = status.insightTrajectory,
        insightFactor = status.insightFactor,
        aimiHealthScore = status.aimiHealthScore,
        aimiAdaptationSummary = status.adaptationSummary,
        aimiAdaptationContentDescription = status.adaptationContentDescription,
        aimiAdaptationHasAttention = status.adaptationHasAttention,
        aimiPulseTitle = status.pulseTitle,
        aimiPulseSummary = status.pulseSummary,
        aimiPulseMeta = status.pulseMeta,
        aimiPulseHypoRisk = status.pulseHypoRisk,
        adaptiveSmoothingQualityTier = status.smoothingTier,
        adaptiveSmoothingQualityBadgeText = status.smoothingBadgeText,
        adaptiveSmoothingQualityDialogMessage = status.smoothingDialogMessage,
        heroRingState = heroRing,
        warmupActive = warmupActive,
        warmupMessage = if (warmupActive) stringResource(UiStrings.dashboard_warmup_a11y) else null,
        lifecycleSubtext = lifecycleSubtext,
        contextIndicatorVisible = status.contextIndicatorVisible,
        readingLine = stringResource(UiStrings.dashboard_hero_status_reading_line, ringInput.timeAgo.ifBlank { dash }),
        stagingState = staging,
    )
}

/** BG text color by range, from the theme. */
@Composable
private fun bgColor(bgInfo: BgInfoData?): Int = when (bgInfo?.bgRange) {
    BgRange.HIGH     -> AapsTheme.generalColors.bgHigh
    BgRange.IN_RANGE -> AapsTheme.generalColors.bgInRange
    BgRange.LOW      -> AapsTheme.generalColors.bgLow
    null             -> MaterialTheme.colorScheme.onSurface
}.toArgb()

/** Ring colors from the theme: in range, slightly high, high, very high, as the stepped ring on DEV. */
@Composable
private fun heroPalette() = DashboardHeroPalette(
    step1 = AapsTheme.generalColors.bgInRange.toArgb(),
    step2 = AapsTheme.generalColors.statusWarning.toArgb(),
    step3 = AapsTheme.generalColors.bgHigh.toArgb(),
    step4 = AapsTheme.generalColors.bgVeryHigh.toArgb(),
    surface = MaterialTheme.colorScheme.surface.toArgb(),
    textSecondary = MaterialTheme.colorScheme.onSurfaceVariant.toArgb(),
    attention = AapsTheme.generalColors.statusWarning.toArgb(),
    warmupTeal = MaterialTheme.colorScheme.tertiary.toArgb(),
)

@Composable
private fun rememberSensorStrings(): DashboardHeroSensorStrings {
    val endOfLife = stringResource(UiStrings.dashboard_sensor_end_of_life)
    val earlyLife = stringResource(UiStrings.dashboard_sensor_early_life)
    val endsAt = stringResource(UiStrings.dashboard_warmup_ends_at)
    return DashboardHeroSensorStrings(
        sensorEndOfLife = { hours -> endOfLife.formatKmp(hours) },
        sensorEarlyLife = { day, hours -> earlyLife.formatKmp(day, hours) },
        warmupEndsAt = { time -> endsAt.formatKmp(time) },
        warmupPhaseConnecting = stringResource(UiStrings.dashboard_glass_cgm_phase_connecting),
        warmupPhaseReconnecting = stringResource(UiStrings.dashboard_glass_cgm_phase_reconnecting),
        warmupPhasePairing = stringResource(UiStrings.dashboard_glass_cgm_phase_pairing),
        warmupPhaseLabel = stringResource(UiStrings.dashboard_glass_cgm_phase_warmup),
    )
}

@Composable
private fun rememberHeroStrings(): DashboardHeroStrings {
    val sensorBadge = stringResource(UiStrings.dashboard_hero_sensor_badge_short)
    val chipTwo = stringResource(UiStrings.dashboard_hero_cgm_smoothing_chip_two)
    val sensorLine = stringResource(UiStrings.dashboard_hero_status_sensor_line)
    val insights = stringResource(UiStrings.dashboard_aimi_insights_line)
    val pulse = stringResource(UiStrings.dashboard_aimi_pulse_line)
    return DashboardHeroStrings(
        aimiContext = stringResource(UiStrings.dashboard_quick_context),
        warmupA11y = stringResource(UiStrings.dashboard_warmup_a11y),
        metricsOverflowA11y = stringResource(UiStrings.dashboard_metrics_overflow_a11y),
        metricsModeSimple = stringResource(UiStrings.dashboard_metrics_mode_simple),
        metricsModeExtended = stringResource(UiStrings.dashboard_metrics_mode_extended),
        stepsLabel = stringResource(UiStrings.dashboard_compact_label_steps),
        iobLabel = stringResource(UiStrings.dashboard_compact_label_iob),
        hrLabel = stringResource(UiStrings.dashboard_compact_label_hr),
        tbrLabel = stringResource(UiStrings.dashboard_compact_label_tbr),
        sensorBadgeShort = { age -> sensorBadge.formatKmp(age) },
        sensorDialogTitle = stringResource(UiStrings.dashboard_hero_sensor_dialog_title),
        cgmSmoothingChipTwo = { badge, quality -> chipTwo.formatKmp(badge, quality) },
        cgmSmoothingDialogTitle = stringResource(UiStrings.dashboard_hero_cgm_smoothing_dialog_title),
        heroStatusSensorLine = { age -> sensorLine.formatKmp(age) },
        aimiAdaptationLabel = stringResource(UiStrings.dashboard_aimi_adaptation_label),
        insightsLine = { t3c, trajectory, factor -> insights.formatKmp(t3c, trajectory, factor) },
        pulseLine = { title, summary -> pulse.formatKmp(title, summary) },
    )
}

/** Texts of the dashboard cards (adjustments, notifications, new sensor), shared with the details screen. */
@Composable
internal fun dashboardCardStrings() = DashboardCardStrings(
    unavailableShort = stringResource(CoreUiStrings.value_unavailable_short),
    adjustmentsTitle = stringResource(UiStrings.dashboard_adjustments),
    pumpReservoir = stringResource(UiStrings.dashboard_pump_pill_reservoir),
    pumpReservoirA11y = stringResource(UiStrings.dashboard_pump_pill_reservoir_a11y),
    pumpSite = stringResource(UiStrings.dashboard_pump_pill_site),
    pumpSiteA11y = stringResource(UiStrings.dashboard_pump_pill_site_a11y),
    pumpSensor = stringResource(UiStrings.dashboard_pump_pill_sensor),
    pumpSensorA11y = stringResource(UiStrings.dashboard_pump_pill_sensor_a11y),
    noAdjustments = stringResource(UiStrings.dashboard_no_adjustments),
    runLoop = stringResource(UiStrings.dashboard_run_loop),
    statsButton = stringResource(UiStrings.dashboard_glass_stats_button),
    range6h = stringResource(UiStrings.dashboard_graph_scale_6h),
    range12h = stringResource(UiStrings.dashboard_graph_scale_12h),
    range18h = stringResource(UiStrings.dashboard_graph_scale_18h),
    range24h = stringResource(UiStrings.dashboard_graph_scale_24h),
    treatmentsLabel = stringResource(CoreUiStrings.overview_treatment_label),
    notificationsCompactHeader = stringResource(UiStrings.dashboard_notifications_compact_header),
    snooze = stringResource(CoreUiStrings.snooze),
    stagingTitleWarmup = stringResource(UiStrings.dashboard_staging_title_warmup),
    stagingWarmupCountdown = stringResource(UiStrings.dashboard_staging_warmup_countdown),
    stagingTitleSettling = stringResource(UiStrings.dashboard_staging_title_settling),
    stagingReadings = stringResource(UiStrings.dashboard_staging_readings),
    stagingReady = stringResource(UiStrings.dashboard_staging_ready),
    stagingPromoteButton = stringResource(UiStrings.dashboard_staging_promote_button),
)

@Composable
private fun rememberQuickActionsStrings() = QuickActionsStrings(
    advisorLabel = stringResource(UiStrings.dashboard_quick_advisor),
    advisorContentDescription = stringResource(UiStrings.dashboard_quick_advisor_a11y),
    advisorAnnounced = stringResource(UiStrings.dashboard_quick_advisor_announced),
    adjustLabel = stringResource(UiStrings.dashboard_quick_adjust),
    adjustContentDescription = stringResource(UiStrings.dashboard_quick_adjust_a11y),
    adjustAnnounced = stringResource(UiStrings.dashboard_quick_adjust_announced),
    mealLabel = stringResource(UiStrings.dashboard_quick_meal),
    mealContentDescription = stringResource(UiStrings.dashboard_quick_meal_a11y),
    mealAnnounced = stringResource(UiStrings.dashboard_quick_meal_announced),
    contextLabel = stringResource(UiStrings.dashboard_quick_context),
    contextContentDescription = stringResource(UiStrings.dashboard_quick_context_a11y),
    contextAnnounced = stringResource(UiStrings.dashboard_quick_context_announced),
)

/** A notification for the dashboard list. The button runs its first action, else it dismisses. */
@Composable
private fun AapsNotification.toDashboardItem() = DashboardNotificationUiItem(
    id = instanceKey,
    text = text,
    dismissText = actions.firstOrNull()?.let { stringResource(it.buttonText) } ?: "",
    level = when (level) {
        NotificationLevel.URGENT       -> Notification.URGENT
        NotificationLevel.IMPORTANT,
        NotificationLevel.NORMAL       -> Notification.NORMAL
        NotificationLevel.LOW          -> Notification.LOW
        NotificationLevel.INFO         -> Notification.INFO
        NotificationLevel.ANNOUNCEMENT -> Notification.ANNOUNCEMENT
    },
)

private const val NO_GLUCOSE = "---"
private const val LANDSCAPE_HERO_WEIGHT = 0.42f
