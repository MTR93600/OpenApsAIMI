package app.aaps.plugins.source.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.CgmStagingEvidence
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.dialogs.DatePickerModal
import app.aaps.core.ui.compose.dialogs.TimePickerModal
import app.aaps.plugins.dexcomoneplus.OnePlusCalibrationOutcome
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.parse.OnePlusCalibrationState
import app.aaps.plugins.source.DexcomOnePlusPlugin
import app.aaps.plugins.source.DexcomOnePlusSensorStartCorrection
import app.aaps.plugins.source.DexcomOnePlusStaging
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.CgmCard
import app.aaps.plugins.source.compose.CgmCardHeader
import app.aaps.plugins.source.compose.CgmCardTone
import app.aaps.plugins.source.compose.CgmKeyValueRow
import app.aaps.plugins.source.compose.CgmLazyColumn
import app.aaps.plugins.source.compose.CgmScaffold
import app.aaps.plugins.source.compose.CgmStateChip
import app.aaps.plugins.source.compose.CgmStepTimeline
import app.aaps.plugins.source.compose.CgmUiState
import app.aaps.plugins.source.compose.DexcomOnePlusUiLabels
import app.aaps.plugins.source.compose.toUiState
import app.aaps.plugins.source.keys.DexcomOnePlusBooleanKey
import app.aaps.plugins.source.logs.DriverLogFilter
import dagger.hilt.android.AndroidEntryPoint
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import app.aaps.core.ui.R as CoreUiR

/**
 * Daily status of the native Dexcom ONE+ / G7 source.
 *
 * The screen answers two questions that used to share one flat list of text lines: what is the
 * sensor feeding the loop doing, and what is the pre-soak sensor doing. Each gets its own card, so
 * a reading belongs to a visible subject. Everything sits in one scrolling list — the old
 * non-scrolling column pushed the promote button, the one action that switches the loop's glucose
 * source, past the bottom edge as soon as a staging sensor was present.
 */
@AndroidEntryPoint
class DexcomOnePlusStatusActivity : AppCompatActivity() {

    @Inject lateinit var dexcomOnePlusPlugin: DexcomOnePlusPlugin
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var persistenceLayer: PersistenceLayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dexcomOnePlusPlugin.syncDriverFromPrefs()
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    DexcomOnePlusStatusScreen(
                        onBack = { finish() },
                        onOpenStart = {
                            startActivity(Intent(this, DexcomOnePlusStartActivity::class.java))
                        },
                        onOpenWarmup = {
                            startActivity(Intent(this, DexcomOnePlusWarmupActivity::class.java))
                        },
                        onOpenLog = {
                            startActivity(
                                Intent(this, CgmDriverLogActivity::class.java)
                                    .putExtra(CgmDriverLogActivity.EXTRA_FILTER, DriverLogFilter.DEXCOM_ONE_PLUS.name)
                            )
                        },
                        stagingStateFlow = dexcomOnePlusPlugin.stagingState,
                        stagingEvidenceFlow = dexcomOnePlusPlugin.stagingEvidence,
                        lifecycleFlow = dexcomOnePlusPlugin.lifecycle,
                        formatGlucose = { mgdl -> profileUtil.fromMgdlToStringWithUnits(mgdl) },
                        formatTime = { epochMs -> dateUtil.timeString(epochMs) },
                        formatAge = { millis -> dateUtil.age(millis, false, rh) },
                        lastGlucose = { persistenceLayer.getLastGlucoseValue() },
                        onCancelStaging = { dexcomOnePlusPlugin.cancelStaging() },
                        onPromote = { allowEarly -> dexcomOnePlusPlugin.promoteStagingToProduction(allowEarly) },
                        onCorrectSensorStart = { startMs -> dexcomOnePlusPlugin.correctProductionSensorStart(startMs) },
                    )
                }
            }
        }
    }
}

/** How often the screen goes back to the database for the newest reading. */
private const val GLUCOSE_REFRESH_MILLIS = 30_000L

@Composable
private fun DexcomOnePlusStatusScreen(
    onBack: () -> Unit,
    onOpenStart: () -> Unit,
    onOpenWarmup: () -> Unit,
    onOpenLog: () -> Unit,
    stagingStateFlow: StateFlow<StagingState>,
    stagingEvidenceFlow: StateFlow<CgmStagingEvidence?>,
    lifecycleFlow: StateFlow<CgmSensorLifecycle?>,
    formatGlucose: (Double) -> String,
    formatTime: (Long) -> String,
    formatAge: (Long) -> String,
    lastGlucose: suspend () -> GV?,
    onCancelStaging: () -> Unit,
    onPromote: suspend (Boolean) -> PromotionResult,
    onCorrectSensorStart: suspend (Long) -> DexcomOnePlusSensorStartCorrection.Verdict,
) {
    // Not `remember`-ed: a promotion swaps which driver instance `default()` hands out (see
    // OnePlusCgmDrivers.promoteStagingInstance), and a `remember`-ed reference kept polling the
    // retired instance for the rest of this screen's life, showing a status stuck at whatever
    // phase it had before the promotion.
    var state by remember { mutableStateOf(OnePlusCgmDrivers.default().warmupState()) }
    var sessionUp by remember { mutableStateOf(OnePlusCgmDrivers.default().isSessionUp()) }
    var newestGlucose by remember { mutableStateOf<GV?>(null) }
    var calibrationOutcome by remember { mutableStateOf<OnePlusCalibrationOutcome?>(null) }
    var calibrationWaiting by remember { mutableStateOf(false) }
    // The card exists only for the people who send values to the sensor itself. With the setting
    // off the value never leaves the phone, so there would be nothing to report.
    val preferences = LocalPreferences.current
    val sendCalibrationToSensor = remember(preferences) {
        preferences.get(DexcomOnePlusBooleanKey.SendCalibrationToSensor)
    }
    val stagingState by stagingStateFlow.collectAsState()
    val stagingEvidence by stagingEvidenceFlow.collectAsState()
    val lifecycle by lifecycleFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var showPromoteConfirm by remember { mutableStateOf(false) }
    var promoteEarly by remember { mutableStateOf(false) }
    var promoteResultText by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    // Offer the early way out only while the sensor is proving itself right now — see canPromoteEarly.
    val earlyPromoteOffered = stagingState != StagingState.ABSENT && DexcomOnePlusStaging.canPromoteEarly(
        validEgvCount = stagingEvidence?.validCount ?: 0,
        lastValueAtEpochMs = stagingEvidence?.lastValueAtEpochMs,
        nowMs = now,
    )

    // Promotion result → user message (resolved here so the coroutine has no Composable context).
    val promoteOk = stringResource(R.string.dexcom_oneplus_staging_promote_ok)
    val promoteRejectedAbsent = stringResource(R.string.dexcom_oneplus_staging_promote_rejected_absent)
    val promoteRejectedNotSettled = stringResource(R.string.dexcom_oneplus_staging_promote_rejected_not_settled)
    val promoteRejectedNoGlucose = stringResource(R.string.dexcom_oneplus_staging_promote_rejected_no_glucose)
    val promoteRejectedNoRecentGlucose = stringResource(R.string.dexcom_oneplus_staging_promote_rejected_no_recent_glucose)
    val promoteRejectedLoopBusy = stringResource(R.string.dexcom_oneplus_staging_promote_rejected_loop_busy)

    LaunchedEffect(Unit) {
        while (true) {
            val driver = OnePlusCgmDrivers.default()
            state = driver.warmupState()
            sessionUp = driver.isSessionUp()
            // The sensor answers by radio, minutes after the dialog said "sent", so the answer can
            // only be picked up by polling like the rest of this screen.
            calibrationOutcome = driver.lastCalibrationOutcome()
            calibrationWaiting = driver.calibrationPending()
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    // A reading arrives every five minutes, so the database is asked far less often than the driver
    // state is polled.
    LaunchedEffect(Unit) {
        while (true) {
            newestGlucose = lastGlucose()
            delay(GLUCOSE_REFRESH_MILLIS)
        }
    }

    CgmScaffold(
        title = stringResource(R.string.dexcom_oneplus_status_title),
        onNavigate = onBack,
        actions = {
            IconButton(onClick = onOpenLog) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.cgm_driver_log_open),
                )
            }
        },
    ) {
        CgmLazyColumn {
            item(key = "production") {
                ProductionCard(
                    phaseLabel = DexcomOnePlusUiLabels.phaseLabel(state.phase),
                    phaseState = state.phase.toUiState(),
                    sessionUp = sessionUp,
                    message = DexcomOnePlusUiLabels.userMessage(state.message),
                    lifecycle = lifecycle,
                    newestGlucose = newestGlucose,
                    formatGlucose = formatGlucose,
                    formatTime = formatTime,
                    formatAge = formatAge,
                    onCorrectSensorStart = onCorrectSensorStart,
                )
            }
            if (sendCalibrationToSensor) {
                item(key = "sensorCalibration") {
                    SensorCalibrationCard(
                        outcome = calibrationOutcome,
                        waiting = calibrationWaiting,
                        driverMessage = state.message,
                    )
                }
            }
            // Prompt for a pre-soak exactly when it is useful: the sensor in use is near its end and
            // no replacement is warming up yet.
            if (lifecycle?.endOfLife == true && stagingState == StagingState.ABSENT) {
                item(key = "endOfLife") {
                    CgmCard(tone = CgmCardTone.Warning) {
                        CgmCardHeader(stringResource(R.string.dexcom_oneplus_end_of_life_title))
                        Text(
                            text = stringResource(R.string.dexcom_oneplus_end_of_life_text),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = onOpenStart) {
                            Text(stringResource(R.string.dexcom_oneplus_start_presoak))
                        }
                    }
                }
            }
            item(key = "staging") {
                StagingCard(
                    stagingState = stagingState,
                    stagingEvidence = stagingEvidence,
                    earlyPromoteOffered = earlyPromoteOffered,
                    formatGlucose = formatGlucose,
                    formatTime = formatTime,
                    onPromoteClick = { early ->
                        promoteEarly = early
                        showPromoteConfirm = true
                    },
                    onCancelStaging = {
                        onCancelStaging()
                        promoteResultText = null
                    },
                    onOpenStart = onOpenStart,
                )
            }
            promoteResultText?.let { message ->
                item(key = "promoteResult") {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "actions") {
                CgmCard {
                    CgmCardHeader(stringResource(R.string.cgm_actions_heading))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                    ) {
                        OutlinedButton(onClick = onOpenStart, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.dexcom_oneplus_start_action))
                        }
                        OutlinedButton(onClick = onOpenWarmup, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.dexcom_oneplus_step_warmup))
                        }
                    }
                    OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cgm_driver_log_open))
                    }
                }
            }
        }
    }

    if (showPromoteConfirm) {
        AlertDialog(
            onDismissRequest = { showPromoteConfirm = false },
            title = {
                Text(
                    if (promoteEarly) stringResource(R.string.dexcom_oneplus_staging_promote_early_confirm_title)
                    else stringResource(R.string.dexcom_oneplus_staging_promote_confirm_title),
                )
            },
            text = {
                Text(
                    if (promoteEarly) stringResource(R.string.dexcom_oneplus_staging_promote_early_confirm_message)
                    else stringResource(R.string.dexcom_oneplus_staging_promote_confirm_message),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPromoteConfirm = false
                        val allowEarly = promoteEarly
                        scope.launch {
                            promoteResultText = when (val result = onPromote(allowEarly)) {
                                is PromotionResult.Ok       -> promoteOk
                                is PromotionResult.Rejected -> when (result.reason) {
                                    PromotionRejectReason.STAGING_ABSENT            -> promoteRejectedAbsent
                                    PromotionRejectReason.STAGING_NOT_SETTLED       -> promoteRejectedNotSettled
                                    PromotionRejectReason.STAGING_NO_VALID_GLUCOSE  -> promoteRejectedNoGlucose
                                    PromotionRejectReason.STAGING_NO_RECENT_GLUCOSE -> promoteRejectedNoRecentGlucose
                                    PromotionRejectReason.LOOP_BUSY                 -> promoteRejectedLoopBusy
                                }
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.dexcom_oneplus_staging_promote_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPromoteConfirm = false }) {
                    Text(stringResource(R.string.dexcom_oneplus_staging_promote_dismiss))
                }
            },
        )
    }
}

/** The sensor that feeds the loop. Accented so it is never confused with the pre-soak card. */
@Composable
private fun ProductionCard(
    phaseLabel: String,
    phaseState: CgmUiState,
    sessionUp: Boolean,
    message: String,
    lifecycle: CgmSensorLifecycle?,
    newestGlucose: GV?,
    formatGlucose: (Double) -> String,
    formatTime: (Long) -> String,
    formatAge: (Long) -> String,
    onCorrectSensorStart: suspend (Long) -> DexcomOnePlusSensorStartCorrection.Verdict,
) {
    CgmCard(accent = true) {
        CgmCardHeader(stringResource(R.string.dexcom_oneplus_production_heading)) {
            CgmStateChip(state = phaseState, label = phaseLabel)
        }
        // Only this driver's own readings are shown here: the newest value in the database can come
        // from another source, and labelling someone else's reading as this sensor's would be a lie.
        val ownReading = newestGlucose?.takeIf { it.sourceSensor == SourceSensor.DEXCOM_ONEPLUS_NATIVE }
        if (ownReading != null) {
            Text(
                text = stringResource(
                    R.string.dexcom_oneplus_staging_last_reading,
                    formatGlucose(ownReading.value),
                    formatTime(ownReading.timestamp),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Text(
                text = stringResource(R.string.dexcom_oneplus_production_no_reading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CgmKeyValueRow(
            label = stringResource(R.string.cgm_session_label),
            value = stringResource(
                if (sessionUp) R.string.dexcom_oneplus_session_up else R.string.dexcom_oneplus_session_down,
            ),
        )
        lifecycle?.ageMs?.let { age ->
            Text(
                text = stringResource(R.string.dexcom_oneplus_sensor_age, formatAge(age)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Offered only for a sensor that has a stored session: with no session there is no age to
        // correct, and the correction would have nothing to attach itself to.
        lifecycle?.startedAtEpochMs?.let { startedAt ->
            CorrectInsertionDateAction(
                currentStartMs = startedAt,
                formatTime = formatTime,
                onCorrectSensorStart = onCorrectSensorStart,
            )
        }
    }
}

/**
 * What became of a finger prick value handed to the sensor itself.
 *
 * The calibration dialog can only say "sent": the write leaves the phone one duty cycle later and
 * the sensor answers by radio after that, so the real result has no place to appear unless a screen
 * polls for it. On screen only while the value is sent to the sensor at all — see
 * [DexcomOnePlusBooleanKey.SendCalibrationToSensor].
 *
 * [OnePlusCalibrationOutcome.Unknown] is deliberately not softened: a Dexcom ONE+ answers with four
 * bytes nobody has decoded (docs/DEXCOM_ONEPLUS_CALIBRATION_TO_SENSOR.md §2c), so it is neither a
 * failure of the app nor a success, and a sensor keeps a calibration it took for good.
 */
@Composable
private fun SensorCalibrationCard(
    outcome: OnePlusCalibrationOutcome?,
    waiting: Boolean,
    driverMessage: String?,
) {
    // A refusal is the one case where something is plainly not in the sensor, so it is the one case
    // that carries the warning surface.
    val tone = if (outcome is OnePlusCalibrationOutcome.Refused) CgmCardTone.Warning else CgmCardTone.Neutral
    CgmCard(tone = tone) {
        CgmCardHeader(stringResource(R.string.dexcom_oneplus_calibration_heading))
        sensorCalibrationRequest(driverMessage)?.let { request ->
            Text(
                text = request,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = when {
                waiting || outcome is OnePlusCalibrationOutcome.Pending ->
                    stringResource(R.string.dexcom_oneplus_calibration_waiting)

                outcome is OnePlusCalibrationOutcome.Accepted           ->
                    stringResource(R.string.dexcom_oneplus_calibration_accepted)

                outcome is OnePlusCalibrationOutcome.Refused            ->
                    stringResource(R.string.dexcom_oneplus_calibration_refused)

                outcome is OnePlusCalibrationOutcome.Unknown            ->
                    stringResource(R.string.dexcom_oneplus_calibration_unknown)

                else                                                   ->
                    stringResource(R.string.dexcom_oneplus_calibration_none)
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        // The raw bytes are worth keeping on screen: they are what a bug report needs, and they are
        // the only evidence the user has of what the sensor really said.
        val detail = when (outcome) {
            is OnePlusCalibrationOutcome.Accepted -> outcome.detail
            is OnePlusCalibrationOutcome.Refused  -> outcome.detail
            is OnePlusCalibrationOutcome.Unknown  -> outcome.detail
            else                                  -> null
        }
        detail?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                text = stringResource(R.string.dexcom_oneplus_calibration_detail, text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the sensor itself is asking for, read back out of the driver status line.
 *
 * Every reading carries a state byte that `OnePlusCalibrationState` already decodes, and the driver
 * passes its name on as [app.aaps.plugins.dexcomoneplus.OnePlusWarmupState.message]. Nothing showed
 * the four calibration states until now. Null when the line is about something else, which it is
 * most of the time — that keeps this reading of a shared field honest instead of guessing.
 */
@Composable
private fun sensorCalibrationRequest(driverMessage: String?): String? {
    val state = OnePlusCalibrationState.entries.firstOrNull { it.name == driverMessage } ?: return null
    return when (state) {
        OnePlusCalibrationState.NeedsCalibration       -> stringResource(R.string.dexcom_oneplus_calibration_state_needs)
        OnePlusCalibrationState.NeedsFirstCalibration  -> stringResource(R.string.dexcom_oneplus_calibration_state_needs_first)
        OnePlusCalibrationState.NeedsSecondCalibration -> stringResource(R.string.dexcom_oneplus_calibration_state_needs_second)
        OnePlusCalibrationState.CalibrationSent        -> stringResource(R.string.dexcom_oneplus_calibration_state_sent)
        else                                           -> null
    }
}

/**
 * "The sensor went in earlier than the app thinks."
 *
 * This source stamps the sensor age when the sensor is paired, not when it is inserted, and that
 * clock lives in the driver's own store — a `SENSOR_CHANGE` added by hand in Care never reached it,
 * and an earlier one was not even the newest event any more, so nothing the user could do moved the
 * age. See `DexcomOnePlusSensorStartCorrection`.
 */
@Composable
private fun CorrectInsertionDateAction(
    currentStartMs: Long,
    formatTime: (Long) -> String,
    onCorrectSensorStart: suspend (Long) -> DexcomOnePlusSensorStartCorrection.Verdict,
) {
    val scope = rememberCoroutineScope()
    var showDatePicker by remember { mutableStateOf(false) }
    var pickedDateMs by remember { mutableStateOf<Long?>(null) }
    var refusal by remember { mutableStateOf<DexcomOnePlusSensorStartCorrection.Verdict?>(null) }

    OutlinedButton(onClick = { showDatePicker = true }) {
        Text(stringResource(R.string.dexcom_oneplus_correct_insertion_date))
    }

    if (showDatePicker) {
        DatePickerModal(
            initialDateMillis = currentStartMs,
            onDateSelected = { pickedDateMs = it },
            onDismiss = { showDatePicker = false },
        )
    }
    // The date picker hands back midnight UTC of the chosen day; the time picker then puts the hour
    // and the minute on it, in the phone's own time zone, which is where the user read the clock.
    pickedDateMs?.let { dayMs ->
        val current = remember(currentStartMs) { Calendar.getInstance().apply { timeInMillis = currentStartMs } }
        TimePickerModal(
            initialHour = current.get(Calendar.HOUR_OF_DAY),
            initialMinute = current.get(Calendar.MINUTE),
            onTimeSelected = { hour, minute ->
                val day = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dayMs }
                val chosen = Calendar.getInstance().apply {
                    set(day.get(Calendar.YEAR), day.get(Calendar.MONTH), day.get(Calendar.DAY_OF_MONTH), hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                scope.launch {
                    val verdict = onCorrectSensorStart(chosen.timeInMillis)
                    if (verdict != DexcomOnePlusSensorStartCorrection.Verdict.Accepted) refusal = verdict
                }
            },
            onDismiss = { pickedDateMs = null },
        )
    }
    refusal?.let { verdict ->
        AlertDialog(
            onDismissRequest = { refusal = null },
            title = { Text(stringResource(R.string.dexcom_oneplus_correct_insertion_date)) },
            text = {
                Text(
                    stringResource(
                        when (verdict) {
                            DexcomOnePlusSensorStartCorrection.Verdict.InFuture  -> R.string.dexcom_oneplus_insertion_date_future
                            DexcomOnePlusSensorStartCorrection.Verdict.TooOld    -> R.string.dexcom_oneplus_insertion_date_too_old
                            else                                                -> R.string.dexcom_oneplus_insertion_date_no_session
                        },
                        formatTime(currentStartMs),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { refusal = null }) { Text(stringResource(CoreUiR.string.ok)) }
            },
        )
    }
}

/**
 * The collect-only second sensor.
 *
 * Promotion stays the only full width button in the card it belongs to, so it does not compete with
 * the navigation buttons that used to sit above it at the same visual weight.
 */
@Composable
private fun StagingCard(
    stagingState: StagingState,
    stagingEvidence: CgmStagingEvidence?,
    earlyPromoteOffered: Boolean,
    formatGlucose: (Double) -> String,
    formatTime: (Long) -> String,
    onPromoteClick: (Boolean) -> Unit,
    onCancelStaging: () -> Unit,
    onOpenStart: () -> Unit,
) {
    val stagingStateLabel = when (stagingState) {
        StagingState.ABSENT   -> stringResource(R.string.dexcom_oneplus_staging_state_absent)
        StagingState.WARMUP   -> stringResource(R.string.dexcom_oneplus_staging_state_warmup)
        StagingState.SETTLING -> stringResource(R.string.dexcom_oneplus_staging_state_settling)
        StagingState.READY    -> stringResource(R.string.dexcom_oneplus_staging_state_ready)
    }
    CgmCard {
        CgmCardHeader(stringResource(R.string.dexcom_oneplus_staging_heading_short)) {
            CgmStateChip(state = stagingState.toUiState(), label = stagingStateLabel)
        }
        if (stagingState == StagingState.ABSENT) {
            // An empty state that explains what the slot is for and offers the way in, instead of
            // just reporting "None".
            Text(
                text = stringResource(R.string.dexcom_oneplus_staging_none_short),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenStart) {
                Text(stringResource(R.string.dexcom_oneplus_start_presoak))
            }
        } else {
            CgmStepTimeline(
                labels = listOf(
                    stringResource(R.string.dexcom_oneplus_staging_state_warmup),
                    stringResource(R.string.dexcom_oneplus_staging_state_settling),
                    stringResource(R.string.dexcom_oneplus_staging_state_ready),
                ),
                reached = when (stagingState) {
                    StagingState.ABSENT   -> -1
                    StagingState.WARMUP   -> 0
                    StagingState.SETTLING -> 1
                    StagingState.READY    -> 2
                },
            )
            // Evidence the staging sensor is really alive: without it the user only sees a state
            // label and cannot tell "settling with data" from "settling with a dead radio".
            CgmKeyValueRow(
                label = stringResource(R.string.dexcom_oneplus_staging_readings_label),
                value = (stagingEvidence?.validCount ?: 0).toString(),
            )
            val lastValue = stagingEvidence?.lastValueMgdl
            val lastAt = stagingEvidence?.lastValueAtEpochMs
            if (lastValue != null && lastAt != null) {
                CgmKeyValueRow(
                    label = stringResource(R.string.dexcom_oneplus_staging_last_label),
                    value = stringResource(
                        R.string.dexcom_oneplus_staging_last_reading,
                        formatGlucose(lastValue),
                        formatTime(lastAt),
                    ),
                )
            } else {
                Text(
                    text = stringResource(R.string.dexcom_oneplus_staging_no_reading_yet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stagingState == StagingState.READY) {
                Button(
                    onClick = { onPromoteClick(false) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.dexcom_oneplus_staging_promote))
                }
            } else if (earlyPromoteOffered) {
                // Way out when the production sensor stops before the soak ends. Deliberately an
                // outlined button with its own warning dialog: it gives up sensor quality, so it
                // must never look like the normal path.
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                    OutlinedButton(
                        onClick = { onPromoteClick(true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.dexcom_oneplus_staging_promote_early))
                    }
                    Text(
                        text = stringResource(R.string.dexcom_oneplus_staging_promote_early_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onCancelStaging, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.dexcom_oneplus_staging_cancel))
            }
        }
    }
}
