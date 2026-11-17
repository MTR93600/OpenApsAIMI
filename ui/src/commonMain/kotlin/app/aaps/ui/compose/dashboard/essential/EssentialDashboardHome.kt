package app.aaps.ui.compose.dashboard.essential

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.source.CgmWarmupStatus
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.glass.GlassHomeViewModel
import app.aaps.ui.compose.dashboard.glass.buildGlassUiState
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DEFAULT_RANGE_HOURS = 6
private const val MESSAGE_MS = 4_000L
private const val RECENT_DECISIONS = 6

/**
 * The Essential home: one living glucose shape, three quiet values under it, a scrub chart, and one
 * quiet status line. Details open on a tap of the shape.
 *
 * Every value comes from the same view models and builders as Glass, so both skins show the same
 * numbers at the same moment. COB is never read.
 *
 * @param commandsAllowed false on a phone that may not send commands (a client): no Boost from here
 */
@Composable
fun EssentialDashboardHome(
    graphViewModel: GraphViewModel,
    chipsViewModel: ChipsViewModel,
    statusViewModel: StatusViewModel,
    aimiDashboardData: Flow<AimiGlassDashboardData>,
    commandsAllowed: Boolean,
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

    var rangeHours by remember { mutableIntStateOf(DEFAULT_RANGE_HOURS) }
    var pullProgress by remember { mutableFloatStateOf(0f) }
    var detailOpen by remember { mutableStateOf(false) }
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
    val prediction60 = EssentialSeries.prediction60(predictions, nowMs)
    val moodMemory = remember { arrayOfNulls<EssentialMood>(1) }
    val mood = EssentialShapeState.classify(
        EssentialInputs(
            bgMgdl = bgInfo?.bgValue?.let(toMgdl),
            stale = bgInfo?.isOutdated ?: true,
            trend = bgInfo?.trendArrow,
            prediction60Mgdl = prediction60?.value?.let(toMgdl),
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

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AapsTheme.spacing.extraLarge, vertical = AapsTheme.spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(mood.label(), style = MaterialTheme.typography.labelLarge, color = mood.colors().core)
            if (boost.active) {
                Spacer(Modifier.height(AapsTheme.spacing.small))
                Text(
                    stringResource(UiStrings.essential_boost_active, boost.remainingMinutes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            val bgText = if (mood == EssentialMood.NO_DATA) unavailable else state.currentBg
            val bgWithUnit = bgInfo?.bgValue?.let { profileUtil.fromMgdlToStringWithUnits(toMgdl(it)) } ?: unavailable
            LivingShape(
                mood = mood,
                bgText = bgText,
                unitText = state.unit,
                trendSymbol = state.trendArrow?.symbol ?: "",
                deltaText = state.deltaText,
                description = stringResource(UiStrings.essential_shape_description, bgWithUnit, mood.label()),
                gesture = gesture,
                onPull = { if (gesture == ShapeGesture.START) essentialViewModel.startBoost() else if (gesture == ShapeGesture.CANCEL) essentialViewModel.cancelBoost() },
                onPullProgress = { pullProgress = it },
                onTap = { detailOpen = true },
            )

            if (gesture != ShapeGesture.NONE) {
                LinearProgressIndicator(progress = { pullProgress }, modifier = Modifier.fillMaxWidth().padding(horizontal = AapsTheme.spacing.xxLarge))
                Text(
                    gesture.hint(pullProgress >= 1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AapsTheme.spacing.small),
                )
            }
            message?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = AapsTheme.spacing.small)) }

            Spacer(Modifier.height(AapsTheme.spacing.large))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MiniValue(
                    prediction60?.let { profileUtil.fromMgdlToStringWithUnits(toMgdl(it.value)) } ?: unavailable,
                    stringResource(UiStrings.essential_prediction_60),
                )
                MiniValue(state.iobText, stringResource(UiStrings.essential_iob))
                MiniValue(homeStatus.effectiveBasalText ?: unavailable, stringResource(UiStrings.essential_basal_temp))
                MiniValue(state.targetText, stringResource(UiStrings.essential_target))
            }

            Spacer(Modifier.height(AapsTheme.spacing.large))
            EssentialChart(
                data = EssentialChartData(
                    bg = bgReadings,
                    prediction = predictions.filter { prediction60 != null && it.type == prediction60.type },
                    basal = basalData.actualBasal,
                    boluses = treatmentData.boluses,
                    lowMark = chartConfig.lowMark,
                    highMark = chartConfig.highMark,
                ),
                nowMs = nowMs,
                rangeHours = rangeHours,
                onRangeChange = { rangeHours = it },
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

            state.tir?.let { tir ->
                Spacer(Modifier.height(AapsTheme.spacing.medium))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AapsTheme.spacing.medium)) {
                    Text(stringResource(UiStrings.essential_tir, tir.inRangePct.roundToInt().toString()), style = MaterialTheme.typography.labelMedium)
                    tir.deltaVsYesterday?.let { delta ->
                        val signed = if (delta > 0) "+$delta" else delta.toString()
                        Text(stringResource(UiStrings.essential_tir_delta, signed), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(AapsTheme.spacing.medium))
            StatusLine(
                listOfNotNull(
                    state.insulinAge to state.insulinAgeStatus,
                    state.cannulaAge to state.cannulaAgeStatus,
                    state.sensorAge to state.sensorAgeStatus,
                    state.loopStatusText?.let { it to (if (state.loopIsRunning) StatusLevel.NORMAL else StatusLevel.WARNING) },
                    stringResource(UiStrings.essential_activity, state.stepsText, state.hrText) to StatusLevel.NORMAL,
                ),
            )
        }
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
            commandsAllowed = commandsAllowed,
            decisions = decisions,
            iob = iobData.iob,
            onStartBoost = { essentialViewModel.startBoost() },
            onCancelBoost = { essentialViewModel.cancelBoost() },
            onDismiss = { detailOpen = false },
        )
    }
}

@Composable
private fun MiniValue(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The quiet line at the bottom: each item its own text, never glued together in code. An age that is
 * due or past due stands out in the theme's warning or critical colour; the shape does not change for it.
 */
@Composable
private fun StatusLine(items: List<Pair<String, StatusLevel>>) {
    val general = AapsTheme.generalColors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AapsTheme.spacing.medium, Alignment.CenterHorizontally)) {
        items.forEach { (item, level) ->
            Text(
                item,
                style = MaterialTheme.typography.labelSmall,
                color = when (level) {
                    StatusLevel.CRITICAL -> general.statusCritical
                    StatusLevel.WARNING  -> general.statusWarning
                    else                 -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

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
