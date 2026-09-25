package app.aaps.plugins.source.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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
import app.aaps.plugins.source.compose.toUiState
import app.aaps.plugins.source.keys.Libre3BooleanKey
import app.aaps.plugins.source.logs.DriverLogFilter
import dagger.hilt.android.AndroidEntryPoint
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
 * One card for the sensor, one for the actions. The screen scrolls, which the previous plain
 * `Column` did not: with a stored sensor and a blocked driver there were enough stacked buttons to
 * push "Forget this sensor" — the only escape from a sensor that can never connect — off the bottom
 * of a short screen.
 *
 * It is also the detail view of a pre-soak: the pre-soak warm-up notification opens this screen.
 */
@AndroidEntryPoint
class Libre3StatusActivity : AppCompatActivity() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var plugin: Libre3NativePlugin
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var rh: ResourceHelper

    /**
     * Whether the pre-soak preference is on, read again on every [onResume].
     *
     * Read once it could be stale: the user can switch the preference while this screen waits in
     * the back stack, and the pre-soak section would then be shown or hidden against the setting.
     */
    private var presoakEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a fresh visit, never on a rebuild: the message has to survive a rotation, but an
        // answer from an hour ago must not greet the user when the screen is opened again.
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
                        // A pre-soak failure must never take production with it (invariant I9), so
                        // every staging call from this screen is wrapped here.
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
 * Promoting swaps the sensor that feeds the loop. A scope taken with `rememberCoroutineScope` is
 * cancelled the moment the composition goes away, so turning the phone while a promotion ran would
 * cut the swap in half and leave the user with no answer at all — on the one action that changes
 * where their insulin decisions come from. The work therefore runs here, outside the screen, and
 * the message is kept here too so a rebuilt screen shows it again.
 *
 * Process wide on purpose: anything owned by the activity dies with the activity, which is exactly
 * the problem. `Libre3StatusActivity.onCreate` empties it when the screen is opened fresh.
 */
internal object Libre3PresoakAction {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow<String?>(null)

    /** What the last finished action left to say, or null when there is nothing. */
    val message: StateFlow<String?> = state.asStateFlow()

    /** Forgets the last message. */
    fun clear() {
        state.value = null
    }

    /**
     * Runs one pre-soak action and publishes the message it returns.
     *
     * @param work does the work and returns the sentence to show. A throw leaves the message
     *   untouched instead of reaching the default handler: this scope outlives every screen, so an
     *   escaping error here would take the whole app down, and a pre-soak must never do that
     *   (invariant I9).
     */
    fun run(work: suspend () -> String) {
        scope.launch {
            runCatching { work() }.getOrNull()?.let { state.value = it }
        }
    }
}

/**
 * @param presoakEnabled the pre-soak preference. With it off the pre-soak section is not built at
 *   all, so the screen is exactly what it was before the feature existed (invariant I8).
 * @param formatGlucose mg/dL into the user's own unit. A lambda, because `ResourceHelper` and the
 *   unit helpers belong outside a `@Composable`.
 * @param onPromote makes the pre-soak sensor the sensor that feeds the loop.
 * @param onSensorForgotten called after a sensor was forgotten, so the plugin can give up the
 *   foreground service when no slot wants a link any more.
 * @param presoakMessageFlow what the last pre-soak action left to say. Read from outside the
 *   screen, so a rotation in the middle of a promotion does not lose the answer.
 * @param runPresoakAction runs a pre-soak action outside the composition and publishes the sentence
 *   it returns into [presoakMessageFlow]. See [Libre3PresoakAction].
 */
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
    // Saveable, not remembered: a dialog the user opened must still be there after a rotation, and
    // silently closing a confirmation is how a user ends up tapping the wrong thing twice.
    var askingToForget by rememberSaveable { mutableStateOf(false) }
    var forgotten by rememberSaveable { mutableStateOf(false) }
    var askingToPromote by rememberSaveable { mutableStateOf(false) }
    var askingToCancelPresoak by rememberSaveable { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var stagingWarmup by remember { mutableStateOf<Libre3WarmupState?>(null) }

    // The pre-soak slot reads itself from the plugin, so nothing here polls it.
    val stagingState by stagingStateFlow.collectAsStateWithLifecycle()
    val stagingEvidence by stagingEvidenceFlow.collectAsStateWithLifecycle()
    val stagingLifecycle by stagingLifecycleFlow.collectAsStateWithLifecycle()
    val stagingCurve by stagingCurveFlow.collectAsStateWithLifecycle()
    val presoakResultText by presoakMessageFlow.collectAsStateWithLifecycle()

    // Every message the pre-soak actions can end with, read here: a coroutine has no Composable
    // context, so `stringResource` cannot be called from inside one.
    val promoteOk = stringResource(R.string.libre3_presoak_promote_ok)
    val promoteRejectedAbsent = stringResource(R.string.libre3_presoak_promote_rejected_absent)
    val promoteRejectedOther = stringResource(R.string.libre3_presoak_promote_rejected_other)
    val presoakCancelled = stringResource(R.string.libre3_presoak_cancel_done)

    // Both loops below poll only while the screen is really in front of the user. A composition is
    // thrown away at onDestroy, not at onStop, so a plain `LaunchedEffect` would keep asking the
    // driver for the whole soak after the user pressed Home — hours of wake-ups on the one link
    // the soak depends on.
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

    // The pre-soak countdown and the right edge of the curve both need a clock, and a countdown
    // read in whole seconds needs to be asked every second. `stagingOrNull` and not `staging()`:
    // asking for the instance would build one, and with the pre-soak off there must be none.
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
                        // An empty state that offers the way in rather than only reporting absence.
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
                        // The link toggle sits with the session line it flips, instead of floating
                        // below as one more button among several.
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
                        // The way out of a sensor that is stored but can never be reached. Without it
                        // the only escape would be clearing the whole app, because a stored pairing
                        // key sends every later attempt down the short reconnect path, and a fresh
                        // scan of the same sensor keeps that key. Destructive, so it stays a quiet
                        // text button rather than competing with the connect action above.
                        TextButton(
                            onClick = { askingToForget = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.libre3_forget_sensor))
                        }
                    }
                }
            }

            // Why the real driver is not in use, said plainly rather than left to be guessed.
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

            // The pre-soak, when there is one. With the preference off, or with no pre-soak sensor,
            // this section does not exist at all — no card, no empty state, nothing to read.
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

            // Outside the section on purpose: cancelling a pre-soak takes the section away, and the
            // answer to what just happened has to stay on screen.
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

    // Promotion swaps the sensor that feeds the loop, so it is never one tap away.
    if (askingToPromote) {
        AlertDialog(
            onDismissRequest = { askingToPromote = false },
            title = { Text(stringResource(R.string.libre3_presoak_promote_title)) },
            text = { Text(stringResource(R.string.libre3_presoak_promote_explain)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingToPromote = false
                        // Run outside the composition, so turning the phone during the swap
                        // neither cuts it in half nor swallows the answer.
                        runPresoakAction {
                            // A throw must not reach the loop or leave the screen silent (I9), so
                            // a failure is reported the same way a refusal is.
                            when (val result = runCatching { onPromote() }.getOrNull()) {
                                is PromotionResult.Ok       -> promoteOk
                                is PromotionResult.Rejected -> when (result.reason) {
                                    PromotionRejectReason.STAGING_ABSENT -> promoteRejectedAbsent
                                    else                                 -> promoteRejectedOther
                                }

                                null                        -> promoteRejectedOther
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

    // Cancelling throws away the soak the user has already paid wear time for, so it is confirmed
    // too — and the dialog says what stays untouched.
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

/**
 * The pre-soak sensor: what it is doing, what it has collected, and the two ways out of it.
 *
 * The dashboard card offers a promote button only once the slot is ready, which is right there —
 * offering a promote for a sensor that has produced nothing would be a trap. This card is the one
 * place that offers it in every state, for the case it exists for: the sensor that feeds the loop
 * has failed while the new one is still warming up. What that costs is said in the dialog, not
 * hidden behind a disabled button.
 *
 * @param stagingWarmup the pre-soak driver's own phase, for the countdown. Null when the pre-soak
 *   driver has never been built.
 * @param nowMs ticks once a second, so the countdown moves and the curve keeps its right edge.
 */
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
            // A warming Libre 3 sends no glucose at all, so there is no curve to draw. The
            // countdown says the wait is going somewhere; an empty chart would only look broken.
            val remainingMs = stagingWarmup?.let { Libre3WarmupCountdown.remainingMs(it, nowMs) }
            CgmWarmupRing(
                // Libre 3 reports what is left but never the whole length, so no honest fraction
                // can be drawn. Same choice as the production warm-up screen.
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
        // Evidence, never a gate. The user already pays real wear time for the soak, so the app
        // does not veto on top of that: these rows are here to be read, not to unlock anything.
        // Counted from the phone clock rather than taken from the stored age, which only moves when
        // the slot has something to report — and during warm-up it has nothing. The stored age is
        // the fallback for a sensor whose start time is not known.
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

/**
 * Throws away everything this phone knows about the sensor it is holding.
 *
 * The three steps have to go together. Dropping the link first, so no session keeps running on
 * material that is about to disappear. Then the store, which is what makes the next attempt start
 * a fresh pairing instead of reusing a key that does not work. Then the ingest mark, because the
 * next sensor counts its own minutes from zero and a leftover mark would refuse every reading of
 * it as already seen.
 *
 * The sensor itself is left alone. No command is sent to it, and it keeps running.
 */
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
