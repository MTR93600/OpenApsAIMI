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
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.source.DexcomOnePlusPlugin
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
import app.aaps.plugins.source.logs.DriverLogFilter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
) {
    val driver = remember { OnePlusCgmDrivers.default() }
    var state by remember { mutableStateOf(driver.warmupState()) }
    var sessionUp by remember { mutableStateOf(driver.isSessionUp()) }
    var newestGlucose by remember { mutableStateOf<GV?>(null) }
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
            state = driver.warmupState()
            sessionUp = driver.isSessionUp()
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
                )
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
