package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalClientRole
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Chart range shown when the home opens, in hours (the filter offers 6, 12, 18 and 24). */
private const val DEFAULT_RANGE_HOURS = 6

/**
 * The Glass home: status card, glucose and IOB charts, time filter, pump status line and the in-app
 * notifications, with the Tools and Personalize overlays. Port of `GlassOverviewComposeEmbedded` on
 * `dev_OAPSAIMI`.
 *
 * It replaces only the home content of `MainScreen`; the shell top bar, drawer and bottom bar stay. As on
 * DEV it draws no title or settings header of its own.
 *
 * @param notifications in-app notifications. `MainScreen` does not show them when a dashboard skin
 *   replaces the overview, so this home shows them, as DEV did.
 * @param availablePluginClassNames simple class names of the plugins in this build; Tools tiles for
 *   other plugins are disabled
 * @param bgSourcePluginClassName simple class name of the active BG source plugin (opened by the
 *   Sensor tile)
 * @param commandsAllowed false on an unpaired client: the pills that open treatment dialogs then do nothing
 * @param onOpenActions opens the host's Manage (actions) sheet, for the Actions tile
 * @param aimiDashboardData read-only AIMI values; only today's health store step total is used here
 * @param cgmWarmupStatus link state of the active CGM source, null when the source cannot report one
 */
@Composable
fun GlassDashboardHome(
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    statusViewModel: StatusViewModel,
    notifications: List<AapsNotification>,
    onDismissNotification: (AapsNotification) -> Unit,
    onNotificationActionClick: (AapsNotification) -> Unit,
    availablePluginClassNames: Set<String>,
    bgSourcePluginClassName: String?,
    commandsAllowed: Boolean,
    onNavigate: (NavigationRequest) -> Unit,
    onOpenActions: () -> Unit,
    aimiDashboardData: Flow<AimiGlassDashboardData>,
    modifier: Modifier = Modifier,
    cgmWarmupStatus: StateFlow<CgmWarmupStatus?>? = null,
    homeViewModel: GlassHomeViewModel = metroViewModel(),
) {
    val preferences = LocalPreferences.current
    val profileUtil = LocalProfileUtil.current
    val decimalFormatter = LocalDecimalFormatter.current
    val dateUtil = LocalDateUtil.current
    val isDark = isGlassDarkMode()

    // Repairs installs whose pill selection was saved before the top-grid pills existed.
    LaunchedEffect(Unit) {
        if (!preferences.get(BooleanNonKey.GlassTopGridPillsMigrated)) {
            val current = parseSelectedGlassPills(preferences.get(StringNonKey.GlassSelectedPills)).toSet()
            preferences.put(StringNonKey.GlassSelectedPills, serializeSelectedGlassPills(withTopGridDefaultsMerged(current)))
            preferences.put(BooleanNonKey.GlassTopGridPillsMigrated, true)
        }
    }
    val selectedPillsRaw by preferences.observe(StringNonKey.GlassSelectedPills).collectAsStateWithLifecycle()
    val selectedPills = remember(selectedPillsRaw) { parseSelectedGlassPills(selectedPillsRaw) }
    val selectedToolsRaw by preferences.observe(StringNonKey.GlassSelectedTools).collectAsStateWithLifecycle()
    val selectedTools = remember(selectedToolsRaw) { parseSelectedGlassTools(selectedToolsRaw).toSet() }

    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    // 30 s ticker: moves the chart window, the "N min ago" text and the stale check between refreshes.
    val nowMs by graphViewModel.nowTimestamp.collectAsStateWithLifecycle()
    val noCgmWarmup = remember { MutableStateFlow<CgmWarmupStatus?>(null) }
    val cgmWarmup by (cgmWarmupStatus ?: noCgmWarmup).collectAsStateWithLifecycle()
    val iobState by chipsViewModel.iobUiState.collectAsStateWithLifecycle()
    val statusLights by statusViewModel.uiState.collectAsStateWithLifecycle()
    val homeStatus by homeViewModel.status.collectAsStateWithLifecycle()
    val aimiData by aimiDashboardData.collectAsStateWithLifecycle(initialValue = AimiGlassDashboardData())
    val healthStoreStepsToday = aimiData.safety?.stepsToday
    LaunchedEffect(healthStoreStepsToday) { homeViewModel.onHealthStoreStepsToday(healthStoreStepsToday) }
    val treatmentData by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
    val bgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
    val iobData by graphViewModel.iobGraphFlow.collectAsStateWithLifecycle()
    val basalData by graphViewModel.basalGraphFlow.collectAsStateWithLifecycle()
    val chartConfig by graphViewModel.chartConfigFlow.collectAsStateWithLifecycle()
    val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
    var rangeHours by remember { mutableIntStateOf(DEFAULT_RANGE_HOURS) }

    val bgInfo = bgInfoState.bgInfo
    // The short average delta first, as DEV did: it is calmer than the last 5 min step.
    val deltaValue = bgInfo?.shortAvgDeltaText?.takeIf { bgInfo.shortAvgDelta?.isFinite() == true }
        ?: bgInfo?.deltaText
        ?: stringResource(CoreUiStrings.value_unavailable_short)
    val deltaText = stringResource(UiStrings.dashboard_glass_delta, deltaValue)
    // Total IOB with no "IOB:" prefix, the pill has its own title. Empty text: not computed yet.
    val iobTotal = iobState.iobTotal.takeIf { iobState.text.isNotEmpty() }
    val iobText = iobTotal?.let {
        if (it >= 0) stringResource(InterfacesStrings.format_insulin_units, it)
        else stringResource(CoreUiStrings.format_insulin_units_signed, it)
    }
    // Short age of the shown glucose, like DEV's `dateUtil.minAgoShort`.
    val timeAgo = bgInfo?.let { dateUtil.minAgoShort(it.timestamp) } ?: ""

    val state = buildGlassUiState(
        bgInfo = bgInfo,
        timeAgo = timeAgo,
        deltaText = deltaText,
        unitText = profileUtil.units.displayLabel,
        iobText = iobText,
        lights = statusLights,
        homeStatus = homeStatus,
        treatmentData = treatmentData,
        warmup = cgmWarmup,
        nowMs = nowMs,
    )
    val chartState = remember(rangeHours, nowMs, bgReadings, iobData, basalData, treatmentData, chartConfig, predictions, iobTotal) {
        buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowMs,
            bgReadings = bgReadings,
            iobPoints = iobData.iob,
            iobPredictionPoints = iobData.predictions,
            basalData = basalData,
            boluses = treatmentData.boluses,
            carbs = treatmentData.carbs,
            chartConfig = chartConfig,
            mgdlToDisplayUnits = { profileUtil.fromMgdlToUnits(it) },
            predictions = predictions,
            iobTotalU = iobTotal,
        )
    }
    val clientRole = LocalClientRole.current
    val commands = remember(onNavigate, commandsAllowed, clientRole) {
        GlassNavigationCommands(onNavigate, commandsAllowed, clientRole)
    }

    CompositionLocalProvider(LocalGlassHeroCommands provides commands) {
        GlassOverviewContent(
            state = state,
            chartState = chartState,
            isDark = isDark,
            nowMs = nowMs,
            onSelectRange = { rangeHours = it },
            selectedPills = selectedPills,
            onTogglePill = { id, checked ->
                val next = selectedPills.toMutableSet()
                if (checked) next.add(id) else next.remove(id)
                preferences.put(StringNonKey.GlassSelectedPills, serializeSelectedGlassPills(next))
            },
            selectedTools = selectedTools,
            onToggleTool = { action, checked ->
                val next = selectedTools.toMutableSet()
                if (checked) next.add(action) else next.remove(action)
                preferences.put(StringNonKey.GlassSelectedTools, serializeSelectedGlassTools(next))
            },
            availablePluginClassNames = availablePluginClassNames,
            onToolAction = { action -> dispatchGlassToolAction(action, onNavigate, onOpenActions, bgSourcePluginClassName) },
            formatBgValue = { value ->
                if (profileUtil.units == GlucoseUnit.MMOL) decimalFormatter.to1Decimal(value.toDouble())
                else decimalFormatter.to0Decimal(value.toDouble())
            },
            notifications = notifications,
            onDismissNotification = onDismissNotification,
            onNotificationActionClick = onNotificationActionClick,
            pumpStatus = homeStatus.pumpStatus,
            modifier = modifier,
        )
    }
}

/**
 * Stateless body of the Glass home, so it can be tested without view models. The hero commands come
 * from [LocalGlassHeroCommands].
 */
@Composable
internal fun GlassOverviewContent(
    state: GlassUiState,
    chartState: GlassChartState,
    isDark: Boolean,
    nowMs: Long,
    onSelectRange: (Int) -> Unit,
    selectedPills: List<GlassPillId>,
    onTogglePill: (GlassPillId, Boolean) -> Unit,
    selectedTools: Set<DashboardV2ToolAction>,
    onToggleTool: (DashboardV2ToolAction, Boolean) -> Unit,
    availablePluginClassNames: Set<String>,
    onToolAction: (DashboardV2ToolAction) -> Unit,
    formatBgValue: (Float) -> String,
    notifications: List<AapsNotification>,
    onDismissNotification: (AapsNotification) -> Unit,
    onNotificationActionClick: (AapsNotification) -> Unit,
    pumpStatus: GlassPumpStatusLine? = null,
    modifier: Modifier = Modifier,
) {
    val commands = LocalGlassHeroCommands.current
    var showTools by remember { mutableStateOf(false) }
    var showPersonalize by remember { mutableStateOf(false) }
    LaunchedEffect(showTools) { if (!showTools) showPersonalize = false }

    // Back closes Personalize first, then Tools; with neither open it is left to the shell.
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = showTools,
        onBackCompleted = { if (showPersonalize) showPersonalize = false else showTools = false },
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusAgoraCard(
                state = state,
                isDark = isDark,
                nowMs = nowMs,
                commands = commands,
                onOpenTools = { showTools = true },
                selectedPills = selectedPills,
            )
            BgChartCard(
                readings = chartState.bgReadings,
                treatments = chartState.treatments,
                timeRangeHours = chartState.rangeHours,
                currentBgValue = chartState.currentBgValue,
                lowLine = chartState.lowLine,
                highLine = chartState.highLine,
                predictions = chartState.predictions,
                historyFraction = chartState.historyFraction,
                axisMinValue = chartState.axisMinValue,
                axisHeadroom = chartState.axisHeadroom,
                basalReadings = chartState.basalReadings,
                profileBasalReadings = chartState.profileBasalReadings,
                maxBasalRateUh = chartState.maxBasalRateUh,
                formatValue = formatBgValue,
                isDark = isDark,
            )
            IobChartCard(
                iobReadings = chartState.iobReadings,
                predictions = chartState.iobPredictions,
                currentIob = chartState.currentIob,
                timeRangeHours = chartState.rangeHours,
                historyFraction = chartState.historyFraction,
                isDark = isDark,
            )
            TimeFilterBar(
                selectedHours = chartState.rangeHours,
                onSelectHours = onSelectRange,
                onOpenStats = commands::openStatsScreen,
                onOpenTreatment = commands::openTreatmentsScreen,
                statsLabel = stringResource(UiStrings.dashboard_glass_stats_button),
                treatmentLabel = stringResource(CoreUiStrings.overview_treatment_label),
                isDark = isDark,
            )
            pumpStatus?.let { PumpStatusNotification(status = it, isDark = isDark) }
            if (notifications.isNotEmpty()) {
                NotificationsSection(
                    notifications = notifications,
                    isDark = isDark,
                    onDismiss = onDismissNotification,
                    onActionClick = onNotificationActionClick,
                )
            }
        }
        if (showTools) {
            GlassToolsOverlay(
                isDark = isDark,
                showPersonalize = showPersonalize,
                onShowPersonalize = { showPersonalize = it },
                selectedPills = selectedPills,
                onTogglePill = onTogglePill,
                selectedTools = selectedTools,
                onToggleTool = onToggleTool,
                availablePluginClassNames = availablePluginClassNames,
                onToolAction = { action ->
                    showTools = false
                    onToolAction(action)
                },
            )
        }
    }
}

/** Full-size overlay over the home: the Tools grid, or the Personalize list when [showPersonalize]. */
@Composable
private fun GlassToolsOverlay(
    isDark: Boolean,
    showPersonalize: Boolean,
    onShowPersonalize: (Boolean) -> Unit,
    selectedPills: List<GlassPillId>,
    onTogglePill: (GlassPillId, Boolean) -> Unit,
    selectedTools: Set<DashboardV2ToolAction>,
    onToggleTool: (DashboardV2ToolAction, Boolean) -> Unit,
    availablePluginClassNames: Set<String>,
    onToolAction: (DashboardV2ToolAction) -> Unit,
) {
    val accent = glassOverlayAccent(isDark)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(GlassColors.screenBgTop(isDark), GlassColors.screenBgBottom(isDark), GlassColors.screenBgTop(isDark))
                )
            )
    ) {
        if (showPersonalize) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(UiStrings.dashboard_glass_personalize_title),
                        color = GlassColors.textBright(isDark),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    OverlayTextButton(
                        text = stringResource(CoreUiStrings.ok),
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        onClick = { onShowPersonalize(false) },
                    )
                }
                GlassPersonalizeScreen(
                    selectedPills = selectedPills.toSet(),
                    onToggle = onTogglePill,
                    selectedTools = selectedTools,
                    onToolToggle = onToggleTool,
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OverlayTextButton(
                        text = stringResource(UiStrings.dashboard_glass_personalize_button),
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        onClick = { onShowPersonalize(true) },
                    )
                }
                DashboardV2ToolsScreen(
                    availablePluginClassNames = availablePluginClassNames,
                    onAction = onToolAction,
                    modifier = Modifier.fillMaxSize(),
                    visibleActions = selectedTools,
                )
            }
        }
    }
}

@Composable
private fun OverlayTextButton(text: String, color: Color, fontWeight: FontWeight, onClick: () -> Unit) {
    Text(
        text = text,
        color = color,
        fontWeight = fontWeight,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = text, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
