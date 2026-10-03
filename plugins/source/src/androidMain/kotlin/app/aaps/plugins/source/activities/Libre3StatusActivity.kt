package app.aaps.plugins.source.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.interfaces.source.CgmStagingEvidence
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.MetroAppCompatActivity
import app.aaps.plugins.libre3.Libre3CgmDriver
import app.aaps.plugins.libre3.Libre3CgmDrivers
import app.aaps.plugins.libre3.Libre3WarmupState
import app.aaps.plugins.libre3.identity.Libre3SensorStore
import app.aaps.plugins.source.Libre3Ingest
import app.aaps.plugins.source.Libre3NativePlugin
import app.aaps.plugins.source.Libre3PresoakPoint
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.CgmCard
import app.aaps.plugins.source.compose.CgmCardHeader
import app.aaps.plugins.source.compose.CgmCardTone
import app.aaps.plugins.source.compose.CgmKeyValueRow
import app.aaps.plugins.source.compose.CgmLazyColumn
import app.aaps.plugins.source.compose.CgmScaffold
import app.aaps.plugins.source.compose.CgmStateChip
import app.aaps.plugins.source.compose.CgmWarmupRing
import app.aaps.plugins.source.compose.Libre3PresoakCurve
import app.aaps.plugins.source.compose.Libre3UiLabels
import app.aaps.plugins.source.compose.Libre3WarmupCountdown
import app.aaps.plugins.source.compose.toCgmWarmupInfo
import app.aaps.plugins.source.compose.toUiState
import app.aaps.plugins.source.keys.Libre3BooleanKey
import app.aaps.plugins.source.logs.DriverLogFilter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Shows which sensor is stored and what the session is doing.
 *
 * It is also the detail view of a pre-soak: the pre-soak warm-up notification opens this screen.
 */
class Libre3StatusActivity : MetroAppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    @Inject lateinit var plugin: Libre3NativePlugin

    @Inject lateinit var profileUtil: ProfileUtil

    @Inject lateinit var dateUtil: DateUtil

    @Inject lateinit var rh: ResourceHelper

    private var presoakEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) Libre3PresoakAction.clear()
        presoakEnabled = preferences.get(Libre3BooleanKey.PresoakEnabled)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    Libre3StatusScreen(
                        onBack = { finish() },
                        onOpenLog = {
                            startActivity(
                                Intent(this, CgmDriverLogActivity::class.java)
                                    .putExtra(CgmDriverLogActivity.EXTRA_FILTER, DriverLogFilter.LIBRE3.name)
                            )
                        },
                        onOpenStart = {
                            startActivity(Intent(this, Libre3StartActivity::class.java))
                        },
                        presoakEnabled = presoakEnabled,
                        stagingStateFlow = plugin.stagingState,
                        stagingEvidenceFlow = plugin.stagingEvidence,
                        stagingLifecycleFlow = plugin.stagingLifecycle,
                        stagingCurveFlow = plugin.stagingCurve,
                        formatGlucose = { mgdl -> profileUtil.fromMgdlToStringWithUnits(mgdl) },
                        formatTime = { epochMs -> dateUtil.timeString(epochMs) },
                        formatAge = { millis -> dateUtil.age(millis, false, rh) },
                        // The button calls the function that still returns STAGING_ABSENT. This lot
                        // does not promote, so the confirm path shows the refusal and changes nothing.
                        onPromote = { plugin.promoteStagingToProduction(allowEarly = true) },
                        onCancelStaging = { runCatching { plugin.cancelStaging() } },
                        onSensorForgotten = { plugin.refreshSessionService() },
                        presoakMessageFlow = Libre3PresoakAction.message,
                        runPresoakAction = { work -> Libre3PresoakAction.run(work) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        presoakEnabled = preferences.get(Libre3BooleanKey.PresoakEnabled)
    }
}

/**
 * Runs the pre-soak actions of the status screen and keeps the message the last one ended with.
 *
 * A throw leaves the message untouched. The `runCatching` is the reference `Libre3PresoakAction.run`
 * (`dev_OAPSAIMI` @ `3dd0ca64772`).
 */
internal object Libre3PresoakAction {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = state.asStateFlow()

    fun clear() {
        state.value = null
    }

    fun run(work: suspend () -> String) {
        scope.launch {
            runCatching { work() }.getOrNull()?.let { state.value = it }
        }
    }
}

@Composable
internal fun Libre3StatusScreen(
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenStart: () -> Unit,
    presoakEnabled: Boolean,
    stagingStateFlow: StateFlow<StagingState>,
    stagingEvidenceFlow: StateFlow<CgmStagingEvidence?>,
    stagingLifecycleFlow: StateFlow<CgmSensorLifecycle?>,
    stagingCurveFlow: StateFlow<List<Libre3PresoakPoint>>,
    formatGlucose: (Double) -> String,
    formatTime: (Long) -> String,
    formatAge: (Long) -> String,
    onPromote: suspend () -> PromotionResult,
    onCancelStaging: () -> Unit,
    onSensorForgotten: () -> Unit,
    presoakMessageFlow: StateFlow<String?>,
    runPresoakAction: (suspend () -> String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { Libre3SensorStore(context) }
    val driver = remember { Libre3CgmDrivers.default() }
    var identity by remember { mutableStateOf(store.loadIdentity()) }
    var phase by remember { mutableStateOf(driver.warmupState().phase) }
    var sessionUp by remember { mutableStateOf(driver.isSessionUp()) }
    var blockedReason by remember { mutableStateOf(Libre3CgmDrivers.realDriverBlockedReason()) }
    var askingToForget by rememberSaveable { mutableStateOf(false) }
    var forgotten by rememberSaveable { mutableStateOf(false) }
    var askingToPromote by rememberSaveable { mutableStateOf(false) }
    var askingToCancelPresoak by rememberSaveable { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var stagingWarmup by remember { mutableStateOf<Libre3WarmupState?>(null) }

    val stagingState by stagingStateFlow.collectAsStateWithLifecycle()
    val stagingEvidence by stagingEvidenceFlow.collectAsStateWithLifecycle()
    val stagingLifecycle by stagingLifecycleFlow.collectAsStateWithLifecycle()
    val stagingCurve by stagingCurveFlow.collectAsStateWithLifecycle()
    val presoakResultText by presoakMessageFlow.collectAsStateWithLifecycle()

    val promoteOk = stringResource(R.string.libre3_presoak_promote_ok)
    val promoteBoundFailed = stringResource(R.string.libre3_presoak_promote_bound_failed)
    val promoteCheckState = stringResource(R.string.libre3_presoak_promote_check_state)
    val promoteFollowUpNoIdentity = stringResource(R.string.libre3_presoak_promote_follow_up_no_identity)
    val promoteRejectedAbsent = stringResource(R.string.libre3_presoak_promote_rejected_absent)
    val promoteRejectedOther = stringResource(R.string.libre3_presoak_promote_rejected_other)
    val presoakCancelled = stringResource(R.string.libre3_presoak_cancel_done)

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                identity = store.loadIdentity()
                phase = driver.warmupState().phase
                sessionUp = driver.isSessionUp()
                blockedReason = Libre3CgmDrivers.realDriverBlockedReason()
                delay(2_000L)
            }
        }
    }

    LaunchedEffect(lifecycleOwner, presoakEnabled, stagingState) {
        if (!presoakEnabled || stagingState == StagingState.ABSENT) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMs = System.currentTimeMillis()
                stagingWarmup = runCatching { Libre3CgmDrivers.stagingOrNull()?.warmupState() }.getOrNull()
                delay(1_000L)
            }
        }
    }

    CgmScaffold(
        title = stringResource(R.string.libre3_status_title),
        onNavigate = onBack,
        modifier = modifier,
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
            item(key = "sensor") {
                val stored = identity
                CgmCard(accent = stored != null) {
                    CgmCardHeader(stringResource(R.string.libre3_status_heading)) {
                        CgmStateChip(state = phase.toUiState(), label = Libre3UiLabels.phaseLabel(phase))
                    }
                    if (stored == null) {
                        Text(
                            text = stringResource(R.string.libre3_status_no_sensor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onOpenStart) {
                            Text(stringResource(R.string.libre3_status_open_start))
                        }
                    } else {
                        CgmKeyValueRow(
                            label = stringResource(R.string.libre3_label_serial),
                            value = stored.serialNumber,
                        )
                        CgmKeyValueRow(
                            label = stringResource(R.string.libre3_label_family),
                            value = Libre3UiLabels.generationLabel(stored.generation),
                        )
                        CgmKeyValueRow(
                            label = stringResource(R.string.cgm_sensor_address),
                            value = stored.bleAddress,
                        )
                        CgmKeyValueRow(
                            label = stringResource(R.string.cgm_session_label),
                            value = stringResource(
                                if (sessionUp) R.string.libre3_status_session_up else R.string.libre3_status_session_down,
                            ),
                        )
                        if (blockedReason == null) {
                            Button(
                                onClick = {
                                    if (sessionUp) driver.disconnect() else driver.connect(stored.bleAddress)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(
                                        if (sessionUp) R.string.libre3_status_disconnect else R.string.libre3_status_connect
                                    )
                                )
                            }
                        }
                        TextButton(
                            onClick = { askingToForget = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.libre3_forget_sensor))
                        }
                    }
                }
            }

            blockedReason?.let { reason ->
                item(key = "blocked") {
                    CgmCard(tone = CgmCardTone.Warning) {
                        CgmCardHeader(stringResource(R.string.libre3_status_driver_blocked_heading))
                        Text(
                            text = stringResource(R.string.libre3_status_driver_blocked, reason),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (presoakEnabled && stagingState != StagingState.ABSENT) {
                item(key = "presoak") {
                    PresoakCard(
                        stagingState = stagingState,
                        stagingEvidence = stagingEvidence,
                        stagingLifecycle = stagingLifecycle,
                        stagingWarmup = stagingWarmup,
                        curve = stagingCurve,
                        nowMs = nowMs,
                        formatGlucose = formatGlucose,
                        formatTime = formatTime,
                        formatAge = formatAge,
                        onPromoteClick = { askingToPromote = true },
                        onCancelClick = { askingToCancelPresoak = true },
                    )
                }
            }

            presoakResultText?.let { message ->
                item(key = "presoakResult") {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (forgotten) {
                item(key = "forgotten") {
                    Text(
                        text = stringResource(R.string.libre3_forget_sensor_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "actions") {
                CgmCard {
                    CgmCardHeader(stringResource(R.string.cgm_actions_heading))
                    OutlinedButton(onClick = onOpenStart, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.libre3_status_open_start))
                    }
                    OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cgm_driver_log_open))
                    }
                }
            }
        }
    }

    if (askingToForget) {
        AlertDialog(
            onDismissRequest = { askingToForget = false },
            title = { Text(stringResource(R.string.libre3_forget_sensor_title)) },
            text = { Text(stringResource(R.string.libre3_forget_sensor_explain)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingToForget = false
                        forgetSensor(driver, store)
                        identity = null
                        sessionUp = false
                        forgotten = true
                        onSensorForgotten()
                    }
                ) {
                    Text(stringResource(R.string.libre3_forget_sensor_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { askingToForget = false }) {
                    Text(stringResource(R.string.libre3_forget_sensor_cancel))
                }
            },
        )
    }

    if (askingToPromote) {
        AlertDialog(
            onDismissRequest = { askingToPromote = false },
            title = { Text(stringResource(R.string.libre3_presoak_promote_title)) },
            text = { Text(stringResource(R.string.libre3_presoak_promote_explain)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingToPromote = false
                        runPresoakAction {
                            when (val result = onPromote()) {
                                PromotionResult.Ok                 -> promoteOk
                                PromotionResult.OkBoundFailed      -> promoteBoundFailed
                                is PromotionResult.OkFollowUpFailed ->
                                    if (result.productionIdentityPresent) promoteCheckState
                                    else promoteFollowUpNoIdentity
                                is PromotionResult.Rejected        -> when (result.reason) {
                                    PromotionRejectReason.STAGING_ABSENT -> promoteRejectedAbsent
                                    else                                 -> promoteRejectedOther
                                }
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.libre3_presoak_promote_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { askingToPromote = false }) {
                    Text(stringResource(R.string.libre3_presoak_promote_cancel))
                }
            },
        )
    }

    if (askingToCancelPresoak) {
        AlertDialog(
            onDismissRequest = { askingToCancelPresoak = false },
            title = { Text(stringResource(R.string.libre3_presoak_cancel_title)) },
            text = { Text(stringResource(R.string.libre3_presoak_cancel_explain)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingToCancelPresoak = false
                        runPresoakAction {
                            onCancelStaging()
                            presoakCancelled
                        }
                    }
                ) {
                    Text(stringResource(R.string.libre3_presoak_cancel_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { askingToCancelPresoak = false }) {
                    Text(stringResource(R.string.libre3_presoak_cancel_dismiss))
                }
            },
        )
    }
}

@Composable
private fun PresoakCard(
    stagingState: StagingState,
    stagingEvidence: CgmStagingEvidence?,
    stagingLifecycle: CgmSensorLifecycle?,
    stagingWarmup: Libre3WarmupState?,
    curve: List<Libre3PresoakPoint>,
    nowMs: Long,
    formatGlucose: (Double) -> String,
    formatTime: (Long) -> String,
    formatAge: (Long) -> String,
    onPromoteClick: () -> Unit,
    onCancelClick: () -> Unit,
) {
    val stateLabel = when (stagingState) {
        StagingState.ABSENT   -> stringResource(R.string.libre3_presoak_state_absent)
        StagingState.WARMUP   -> stringResource(R.string.libre3_presoak_state_warmup)
        StagingState.SETTLING -> stringResource(R.string.libre3_presoak_state_settling)
        StagingState.READY    -> stringResource(R.string.libre3_presoak_state_ready)
    }
    CgmCard {
        CgmCardHeader(stringResource(R.string.libre3_presoak_heading)) {
            CgmStateChip(state = stagingState.toUiState(), label = stateLabel)
        }
        if (stagingState == StagingState.WARMUP) {
            val remainingMs = stagingWarmup?.let {
                Libre3WarmupCountdown.remainingMs(it.toCgmWarmupInfo(), nowMs)
            }
            CgmWarmupRing(
                progress = null,
                state = stagingState.toUiState(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = remainingMs?.let { Libre3WarmupCountdown.format(it) }
                        ?: stringResource(R.string.libre3_warmup_countdown_unknown),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Text(
                text = stringResource(R.string.libre3_presoak_warmup_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Libre3PresoakCurve(points = curve, nowMs = nowMs, formatGlucose = formatGlucose)
        }
        val soakMs = stagingLifecycle?.let { lifecycle ->
            lifecycle.startedAtEpochMs?.let { startedAt -> (nowMs - startedAt).coerceAtLeast(0L) }
                ?: lifecycle.ageMs
        }
        soakMs?.let { millis ->
            CgmKeyValueRow(
                label = stringResource(R.string.libre3_presoak_soak_time),
                value = formatAge(millis),
            )
        }
        CgmKeyValueRow(
            label = stringResource(R.string.libre3_presoak_reading_count),
            value = (stagingEvidence?.validCount ?: 0).toString(),
        )
        val lastValueMgdl = stagingEvidence?.lastValueMgdl
        val lastValueAtMs = stagingEvidence?.lastValueAtEpochMs
        if (lastValueMgdl != null && lastValueAtMs != null) {
            CgmKeyValueRow(
                label = stringResource(R.string.libre3_presoak_last_value),
                value = stringResource(
                    R.string.libre3_presoak_last_value_at,
                    formatGlucose(lastValueMgdl),
                    formatTime(lastValueAtMs),
                ),
            )
        }
        Text(
            text = stringResource(R.string.libre3_presoak_info_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onPromoteClick, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.libre3_presoak_promote))
        }
        TextButton(onClick = onCancelClick, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.libre3_presoak_cancel))
        }
    }
}

private fun forgetSensor(driver: Libre3CgmDriver, store: Libre3SensorStore) {
    driver.disconnect()
    store.clear()
    Libre3Ingest.reset()
}

@Preview
@Composable
private fun Libre3StatusScreenPreview() {
    MaterialTheme {
        Libre3StatusScreen(
            onBack = {},
            onOpenLog = {},
            onOpenStart = {},
            presoakEnabled = false,
            stagingStateFlow = MutableStateFlow(StagingState.ABSENT),
            stagingEvidenceFlow = MutableStateFlow(null),
            stagingLifecycleFlow = MutableStateFlow(null),
            stagingCurveFlow = MutableStateFlow(emptyList()),
            formatGlucose = { mgdl -> mgdl.toString() },
            formatTime = { epochMs -> epochMs.toString() },
            formatAge = { millis -> millis.toString() },
            onPromote = { PromotionResult.Rejected(PromotionRejectReason.STAGING_ABSENT) },
            onCancelStaging = {},
            onSensorForgotten = {},
            presoakMessageFlow = MutableStateFlow(null),
            runPresoakAction = {},
        )
    }
}
