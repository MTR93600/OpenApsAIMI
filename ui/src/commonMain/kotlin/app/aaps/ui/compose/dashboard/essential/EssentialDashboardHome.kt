package app.aaps.ui.compose.dashboard.essential

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.aps.AimiBoostUnavailableReason
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalClientRole
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.DisplayForecast
import app.aaps.ui.compose.dashboard.glass.DashboardV2ToolAction
import app.aaps.ui.compose.dashboard.glass.aimiContextNavigationRequest
import app.aaps.ui.compose.dashboard.glass.GlassHomeViewModel
import app.aaps.ui.compose.dashboard.glass.dispatchGlassToolAction
import app.aaps.ui.compose.dashboard.glass.icon
import app.aaps.ui.compose.dashboard.glass.buildGlassUiState
import app.aaps.ui.compose.notificationsSheet.NotificationBottomSheet
import app.aaps.ui.compose.notificationsSheet.NotificationFab
import app.aaps.ui.compose.notificationsSheet.mostUrgentLevel
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

private const val MESSAGE_MS = 4_000L

/** The loop pill's dot and chevron. Local to this skin, like the shape sizes. */
private val LOOP_DOT = 8.dp
private val LOOP_CHEVRON = 16.dp
private const val RECENT_DECISIONS = 6

/** Gap above the notification bell: it sits under the header row (loop pill and mood label), right of the shape. */
private val NOTIFICATION_BELL_TOP = 56.dp

/** The vertical axis of the chart always shows at least this range, in mg/dL. */
private const val CHART_FLOOR_MGDL = 40.0
private const val CHART_CEILING_MGDL = 250.0

/**
 * The Essential home: one living glucose shape with its pull gauge, three quiet values under it, and a
 * scrub chart in a card. Details (target, ages, activity, recent decisions) open on a tap of the shape.
 *
 * Every value comes from the same view models and builders as Glass, so both skins show the same
 * numbers at the same moment. COB is never read.
 *
 * @param commandsAllowed false on a phone that may not send commands (a client): no Boost from here
 * @param onNavigate opens another screen; the loop pill uses it for the running mode screen
 * @param notifications in-app notifications. `MainScreen` does not show them when a skin replaces the
 *   overview, so this home shows them: a bell that appears only while there is one, and the same sheet as
 *   the other homes, where an alarm is acknowledged.
 * @param autoShowNotificationSheet true when the app comes back with an important notification waiting:
 *   the sheet then opens by itself, as on the classic overview, and [onAutoShowConsumed] is called
 */
@Composable
fun EssentialDashboardHome(
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    statusViewModel: StatusViewModel,
    aimiDashboardData: Flow<AimiGlassDashboardData>,
    commandsAllowed: Boolean,
    onNavigate: (NavigationRequest) -> Unit,
    notifications: List<AapsNotification>,
    onDismissNotification: (AapsNotification) -> Unit,
    onNotificationActionClick: (AapsNotification) -> Unit,
    autoShowNotificationSheet: Boolean,
    onAutoShowConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    cgmWarmupStatus: StateFlow<CgmWarmupStatus?>? = null,
    homeViewModel: GlassHomeViewModel = metroViewModel(),
    essentialViewModel: EssentialViewModel = metroViewModel(),
) {
    val profileUtil = LocalProfileUtil.current
    val dateUtil = LocalDateUtil.current
    val decimalFormatter = LocalDecimalFormatter.current

    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
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
    val boost by essentialViewModel.boost.collectAsStateWithLifecycle()
    val boostPercent by essentialViewModel.boostPercent.collectAsStateWithLifecycle()
    val deviceStatusTick by essentialViewModel.deviceStatusTick.collectAsStateWithLifecycle()
    // The target the loop really uses, which the profile target does not show (AIMI's own rise target, the
    // Boost cap at 80). Read on every composition, and again when a client receives a device status.
    val loopTargetText = essentialViewModel.loopTargetMgdl(nowMs).also { deviceStatusTick }
        ?.let { profileUtil.fromMgdlToStringWithUnits(it) }

    var rangeHours by remember { mutableIntStateOf(ESSENTIAL_RANGES.first()) }
    var pullProgress by remember { mutableFloatStateOf(0f) }
    var detailOpen by remember { mutableStateOf(false) }
    // The AIMI screens of the detail sheet, the same destinations as the Glass tools. AIMI must be the active
    // algorithm (the Boost state already knows), Context must exist on this platform, and the Meal Advisor
    // can start a bolus, so a phone that may not send commands does not get it.
    val aimiActive = boost.unavailableReason != AimiBoostUnavailableReason.NOT_AIMI
    val aimiTools = buildList {
        if (aimiActive && aimiData.contextScreenAvailable) add(DashboardV2ToolAction.AIMI_CONTEXT)
        if (aimiActive) add(DashboardV2ToolAction.ADVISOR)
        if (aimiActive && commandsAllowed) add(DashboardV2ToolAction.MEAL_ADVISOR)
    }
    var notificationSheetOpen by remember { mutableStateOf(false) }
    LaunchedEffect(autoShowNotificationSheet) {
        if (autoShowNotificationSheet) {
            notificationSheetOpen = true
            onAutoShowConsumed()
        }
    }
    var message by remember { mutableStateOf<EssentialMessage?>(null) }
    LaunchedEffect(essentialViewModel) { essentialViewModel.messages.collect { message = it } }
    LaunchedEffect(message) { if (message != null) { delay(MESSAGE_MS); message = null } }

    // The same Glass state, so values match Glass exactly
    val bgInfo = bgInfoState.bgInfo
    val deltaValue = bgInfo?.shortAvgDeltaText?.takeIf { bgInfo.shortAvgDelta?.isFinite() == true }
        ?: bgInfo?.deltaText
        ?: stringResource(CoreUiStrings.value_unavailable_short)
    val iobTotal = iobState.iobTotal.takeIf { iobState.text.isNotEmpty() }
    val iobText = iobTotal?.let {
        if (it >= 0) stringResource(InterfacesStrings.format_insulin_units, it)
        else stringResource(CoreUiStrings.format_insulin_units_signed, it)
    }
    val state = buildGlassUiState(
        bgInfo = bgInfo,
        timeAgo = bgInfo?.let { dateUtil.minAgoShort(it.timestamp) } ?: "",
        deltaText = deltaValue,
        unitText = profileUtil.units.displayLabel,
        iobText = iobText,
        lights = statusLights,
        homeStatus = homeStatus,
        treatmentData = treatmentData,
        warmup = cgmWarmup,
        nowMs = nowMs,
    )

    // The mood, in mg/dL, with the user's own marks. The memory gives the hysteresis its previous mood.
    val toMgdl = { value: Double -> profileUtil.convertToMgdl(value, profileUtil.units) }
    // The display forecast, not a loop curve: see DisplayForecast for why.
    val forecast = remember(bgReadings, predictions, nowMs) {
        DisplayForecast.build(bgReadings, predictions, nowMs) { profileUtil.fromMgdlToUnits(it) }
    }
    val forecast30 = forecast?.let { it.at(it.centre, DisplayForecast.HEADLINE_MINUTES) }
    val forecast60 = forecast?.let { it.at(it.centre, DisplayForecast.HORIZON_MINUTES) }
    val moodMemory = remember { arrayOfNulls<EssentialMood>(1) }
    val mood = EssentialShapeState.classify(
        EssentialInputs(
            bgMgdl = bgInfo?.bgValue?.let(toMgdl),
            stale = bgInfo?.isOutdated ?: true,
            trend = bgInfo?.trendArrow,
            prediction60Mgdl = forecast60?.value?.let(toMgdl),
            lowMgdl = toMgdl(chartConfig.lowMark),
            highMgdl = toMgdl(chartConfig.highMark),
        ),
        moodMemory[0],
    ).also { moodMemory[0] = it }

    val gesture = when {
        !commandsAllowed || mood == EssentialMood.NO_DATA -> ShapeGesture.NONE
        boost.active                                      -> ShapeGesture.CANCEL
        boost.available && mood.offersBoost()             -> ShapeGesture.START
        else                                              -> ShapeGesture.NONE
    }
    val unavailable = stringResource(CoreUiStrings.value_unavailable_short)

    // The page should fit the screen without scrolling: the shape takes what the other rows leave, between
    // its smallest and largest size. On a very small screen it stops at its smallest size and the page scrolls.
    val density = LocalDensity.current
    var restHeight by remember { mutableStateOf<Dp?>(null) }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val shapeBox = restHeight?.let { (maxHeight - it).coerceIn(EssentialShapeDimens.minBox, EssentialShapeDimens.box) } ?: EssentialShapeDimens.minBox
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .onSizeChanged { restHeight = with(density) { it.height.toDp() } - shapeBox }
                .padding(horizontal = AapsTheme.spacing.extraLarge, vertical = AapsTheme.spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val moodColors = mood.colors()
            // Loop state on the left, the situation in words on the right: the shape is never the only
            // place a low is told (colour alone is not enough)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    state.loopStatusText?.let { loopText ->
                        LoopPill(
                            text = loopText,
                            running = state.loopIsRunning,
                            enabled = commandsAllowed && LocalClientRole.current.allows(ElementType.RUNNING_MODE.role),
                            onClick = { onNavigate(NavigationRequest.Element(ElementType.RUNNING_MODE)) },
                        )
                    }
                }
                Text(mood.label(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = moodColors.core, maxLines = 1)
            }
            if (boost.active) {
                Spacer(Modifier.height(AapsTheme.spacing.small))
                Text(
                    loopTargetText?.let { stringResource(UiStrings.essential_boost_active_target, boost.remainingMinutes, it) }
                        ?: stringResource(UiStrings.essential_boost_active, boost.remainingMinutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // One maintenance satellite, only when something is due by the user's own status light thresholds
            val maintenance = EssentialMaintenanceRule.mostUrgent(
                listOf(
                    EssentialMaintenance.of(MaintenanceKind.SENSOR, state.sensorAgeStatus, state.sensorAge),
                    EssentialMaintenance.of(MaintenanceKind.CANNULA, state.cannulaAgeStatus, state.cannulaAge),
                    // Insulin left counts too: a reservoir can be nearly empty long before it is old
                    EssentialMaintenance.of(MaintenanceKind.INSULIN, state.insulinAgeStatus, state.insulinAge, state.reservoirLevelStatus, state.reservoirLevel),
                    EssentialMaintenance.of(MaintenanceKind.BATTERY, state.batteryAgeStatus, state.batteryAge, state.batteryLevelStatus, state.batteryLevel),
                ),
            )
            // The same AIMI screens as the detail sheet, on a long press of the shape
            val orbitTools = aimiTools.map { tool ->
                EssentialOrbitItem(icon = tool.icon(), label = stringResource(tool.label)) {
                    dispatchGlassToolAction(tool, onNavigate, onOpenActions = {}, bgSourcePluginClassName = null)
                }
            }
            // The declared context, filled when the loop really uses it, only outlined when it does not
            val activeContext = aimiData.activeContext
            val contextLabel = activeContext?.let { ctx ->
                val name = if (ctx.count > 1) stringResource(UiStrings.essential_context_more, ctx.kind.label(), ctx.count - 1) else ctx.kind.label()
                if (ctx.usedByLoop) stringResource(UiStrings.essential_context_used, name, dateUtil.timeString(ctx.endsAtMs))
                else stringResource(UiStrings.essential_context_declared, name)
            } ?: ""
            val maintenanceLabel = maintenance?.let {
                val what = stringResource(
                    when (it.kind) {
                        MaintenanceKind.SENSOR  -> UiStrings.essential_age_sensor
                        MaintenanceKind.CANNULA -> UiStrings.essential_age_cannula
                        MaintenanceKind.INSULIN -> if (it.byLevel) UiStrings.essential_reservoir else UiStrings.essential_age_insulin
                        MaintenanceKind.BATTERY -> UiStrings.essential_age_battery
                    }
                )
                stringResource(UiStrings.essential_maintenance_label, what, it.value)
            } ?: ""

            val bgText = if (mood == EssentialMood.NO_DATA) unavailable else state.currentBg
            val bgWithUnit = bgInfo?.bgValue?.let { profileUtil.fromMgdlToStringWithUnits(toMgdl(it)) } ?: unavailable
            LivingShape(
                mood = mood,
                bgText = bgText,
                unitText = state.unit,
                trendSymbol = state.trendArrow?.symbol ?: "",
                deltaText = if (state.deltaText == unavailable) unavailable else stringResource(UiStrings.essential_delta_per_5min, state.deltaText),
                description = stringResource(UiStrings.essential_shape_description, bgWithUnit, mood.label()),
                gesture = gesture,
                onPull = { if (gesture == ShapeGesture.START) essentialViewModel.startBoost() else if (gesture == ShapeGesture.CANCEL) essentialViewModel.cancelBoost() },
                onPullProgress = { pullProgress = it },
                onTap = { detailOpen = true },
                boxSize = shapeBox,
                maintenance = maintenance,
                maintenanceLabel = maintenanceLabel,
                tools = orbitTools,
                context = activeContext?.kind,
                contextUsedByLoop = activeContext?.usedByLoop == true,
                contextLabel = contextLabel,
                // One tap to change or stop it. Without the context screen, the sheet holds what there is.
                onContextTap = {
                    if (aimiTools.contains(DashboardV2ToolAction.AIMI_CONTEXT)) onNavigate(aimiContextNavigationRequest())
                    else detailOpen = true
                },
            )

            // The gauge under the shape. Hidden on a phone that may not send commands and when AIMI is not the algorithm.
            if (commandsAllowed && boost.unavailableReason != AimiBoostUnavailableReason.NOT_AIMI) {
                PullGauge(
                    gesture = gesture,
                    progress = pullProgress,
                    color = moodColors.accent,
                    hint = when {
                        gesture != ShapeGesture.NONE -> gesture.hint(pullProgress >= 1f)
                        mood.isGoingDown()           -> stringResource(UiStrings.essential_hint_going_down)
                        else                         -> boost.unavailableReason?.text() ?: stringResource(UiStrings.essential_hint_boost_later)
                    },
                    showBoostLabel = !mood.isGoingDown(),
                )
            }
            message?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = AapsTheme.spacing.small)) }

            Spacer(Modifier.height(AapsTheme.spacing.medium))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MiniValue(state.iobText, stringResource(UiStrings.essential_iob))
                MiniValue(homeStatus.effectiveBasalText ?: unavailable, stringResource(UiStrings.essential_basal_temp))
                val forecastLow = forecast?.let { it.at(it.low, DisplayForecast.HEADLINE_MINUTES) }
                val forecastHigh = forecast?.let { it.at(it.high, DisplayForecast.HEADLINE_MINUTES) }
                MiniValue(
                    forecast30?.let { stringResource(UiStrings.essential_forecast_value, profileUtil.fromMgdlToStringWithUnits(toMgdl(it.value))) } ?: unavailable,
                    if (forecastLow != null && forecastHigh != null) {
                        stringResource(
                            UiStrings.essential_forecast_label,
                            profileUtil.fromMgdlToStringInUnits(toMgdl(forecastLow.value)),
                            profileUtil.fromMgdlToStringInUnits(toMgdl(forecastHigh.value)),
                        )
                    } else {
                        stringResource(UiStrings.essential_forecast_label_no_range)
                    },
                )
            }
            if (forecast?.lowRisk == true) {
                Text(
                    stringResource(UiStrings.forecast_low_possible),
                    style = MaterialTheme.typography.bodySmall,
                    color = AapsTheme.generalColors.bgLow,
                    modifier = Modifier.padding(top = AapsTheme.spacing.extraSmall),
                )
            }

            Spacer(Modifier.height(AapsTheme.spacing.medium))
            EssentialChart(
                data = EssentialChartData(
                    bg = bgReadings,
                    forecast = forecast,
                    basal = basalData.actualBasal,
                    boluses = treatmentData.boluses,
                    lowMark = chartConfig.lowMark,
                    highMark = chartConfig.highMark,
                    scaleFloor = profileUtil.fromMgdlToUnits(CHART_FLOOR_MGDL),
                    scaleCeiling = profileUtil.fromMgdlToUnits(CHART_CEILING_MGDL),
                ),
                nowMs = nowMs,
                rangeHours = rangeHours,
                onRangeChange = { rangeHours = it },
                tint = moodColors.core,
                readoutAt = { time ->
                    EssentialReadout(
                        time = dateUtil.timeString(time),
                        bg = bgReadings.minByOrNull { abs(it.timestamp - time) }
                            ?.let { profileUtil.fromMgdlToStringWithUnits(toMgdl(it.value)) } ?: unavailable,
                        basal = EssentialSeries.basalAt(basalData.actualBasal, time)?.let { stringResource(CoreUiStrings.pump_base_basal_rate, it) } ?: unavailable,
                        smb = EssentialSeries.smbAt(treatmentData.boluses, time)?.let { stringResource(InterfacesStrings.format_insulin_units, it) },
                    )
                },
                timeLabel = { dateUtil.timeString(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // The bell floats in the empty space right of the shape, under the mood label, so it covers no
        // button (the pull gauge and the chart arrows are lower down). It is drawn only while there is a
        // notification, so the skin stays quiet when all is well.
        NotificationFab(
            notificationCount = notifications.size,
            highestLevel = notifications.mostUrgentLevel(),
            onClick = { notificationSheetOpen = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = NOTIFICATION_BELL_TOP, end = AapsTheme.spacing.large),
        )
        }
    }

    if (notificationSheetOpen && notifications.isNotEmpty()) {
        NotificationBottomSheet(
            notifications = notifications,
            onDismissSheet = { notificationSheetOpen = false },
            onDismissNotification = onDismissNotification,
            onNotificationActionClick = onNotificationActionClick,
        )
    }

    if (detailOpen) {
        val decisions = treatmentData.boluses
            .filter { it.bolusType == BolusType.SMB && it.isValid && it.timestamp <= nowMs }
            .sortedByDescending { it.timestamp }
            .take(RECENT_DECISIONS)
            .map { EssentialDecision(dateUtil.timeString(it.timestamp), it.label) }
        EssentialDetailSheet(
            state = state,
            boost = boost,
            boostPercent = boostPercent,
            loopTargetText = loopTargetText,
            onBoostPercentStep = essentialViewModel::changeBoostPercent,
            commandsAllowed = commandsAllowed,
            decisions = decisions,
            iob = iobData.iob,
            onStartBoost = { essentialViewModel.startBoost() },
            onCancelBoost = { essentialViewModel.cancelBoost() },
            onDismiss = { detailOpen = false },
            aimiTools = aimiTools,
            onOpenTool = { tool ->
                detailOpen = false
                dispatchGlassToolAction(tool, onNavigate, onOpenActions = {}, bgSourcePluginClassName = null)
            },
        )
    }
}

@Composable
private fun MiniValue(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The gauge under the shape: what a pull would do, how far the pull is, and one line of advice. In a
 * situation where glucose is low or going down it offers nothing to pull and says so.
 *
 * @param showBoostLabel false when glucose is low or going down: no Boost label at all, not even grey
 */
@Composable
private fun PullGauge(gesture: ShapeGesture, progress: Float, color: Color, hint: String, showBoostLabel: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = AapsTheme.spacing.small)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(UiStrings.essential_gauge_title), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = muted, modifier = Modifier.weight(1f))
            when {
                gesture == ShapeGesture.START  -> Surface(shape = CircleShape, color = color) {
                    Text(
                        stringResource(UiStrings.essential_gauge_boost),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = AapsTheme.generalColors.onBadge,
                        modifier = Modifier.padding(horizontal = AapsTheme.spacing.large, vertical = AapsTheme.spacing.small),
                    )
                }

                gesture == ShapeGesture.CANCEL -> Text(stringResource(UiStrings.essential_gauge_cancel), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
                showBoostLabel                 -> Text(stringResource(UiStrings.essential_gauge_boost), style = MaterialTheme.typography.labelLarge, color = muted.copy(alpha = 0.6f))
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().padding(top = AapsTheme.spacing.small),
        )
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = AapsTheme.spacing.small),
        )
    }
}

/**
 * The loop state as a quiet pill: a dot (in-range colour while the loop runs, warning colour when it
 * does not), the mode in words, and a small chevron that says it opens something. A tap opens the
 * running mode screen, as the loop chip of the classic overview does.
 */
@Composable
private fun LoopPill(text: String, running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val general = AapsTheme.generalColors
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AapsTheme.spacing.small),
            modifier = Modifier.padding(start = AapsTheme.spacing.medium, end = AapsTheme.spacing.small, top = AapsTheme.spacing.small, bottom = AapsTheme.spacing.small),
        ) {
            val dot = (if (running) general.bgInRange else general.statusWarning).soften()
            Canvas(Modifier.size(LOOP_DOT)) { drawCircle(dot) }
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (enabled) Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = muted, modifier = Modifier.size(LOOP_CHEVRON))
        }
    }
}

/** Glucose is low or heading down: the skin never invites a pull for more insulin then. */
private fun EssentialMood.isGoingDown(): Boolean =
    this == EssentialMood.HYPO || this == EssentialMood.FALLING || this == EssentialMood.TRAJECTORY_HYPO

@Composable
private fun EssentialMood.label(): String = stringResource(
    when (this) {
        EssentialMood.STABLE           -> UiStrings.essential_mood_stable
        EssentialMood.RISING           -> UiStrings.essential_mood_rising
        EssentialMood.FALLING          -> UiStrings.essential_mood_falling
        EssentialMood.HYPER            -> UiStrings.essential_mood_hyper
        EssentialMood.HYPO             -> UiStrings.essential_mood_hypo
        EssentialMood.TRAJECTORY_HYPER -> UiStrings.essential_mood_trajectory_hyper
        EssentialMood.TRAJECTORY_HYPO  -> UiStrings.essential_mood_trajectory_hypo
        EssentialMood.NO_DATA          -> UiStrings.essential_mood_no_data
    }
)

@Composable
private fun ShapeGesture.hint(thresholdReached: Boolean): String = stringResource(
    when (this) {
        ShapeGesture.START  -> if (thresholdReached) UiStrings.essential_boost_release_start else UiStrings.essential_boost_pull_up
        ShapeGesture.CANCEL -> if (thresholdReached) UiStrings.essential_boost_release_cancel else UiStrings.essential_boost_pull_down
        ShapeGesture.NONE   -> UiStrings.essential_boost_pull_up
    }
)

@Composable
private fun EssentialMessage.text(): String = when (this) {
    is EssentialMessage.Started   -> stringResource(UiStrings.essential_boost_started, minutes)
    is EssentialMessage.Refused   -> reason.text()
    EssentialMessage.Cancelled    -> stringResource(UiStrings.essential_boost_cancelled)
}
