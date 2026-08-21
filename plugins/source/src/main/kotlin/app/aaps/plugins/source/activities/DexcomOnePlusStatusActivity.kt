package app.aaps.plugins.source.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.source.CgmStagingEvidence
import app.aaps.core.interfaces.source.PromotionRejectReason
import app.aaps.core.interfaces.source.PromotionResult
import app.aaps.core.interfaces.source.StagingState
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.source.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.source.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.source.DexcomOnePlusPlugin
import app.aaps.plugins.source.DexcomOnePlusStaging
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.DexcomOnePlusUiLabels
import app.aaps.plugins.source.logs.DriverLogFilter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Daily status skeleton for Dexcom ONE+ native BG source.
 * Opens from the plugin preferences.
 */
@AndroidEntryPoint
class DexcomOnePlusStatusActivity : AppCompatActivity() {

    @Inject lateinit var dexcomOnePlusPlugin: DexcomOnePlusPlugin
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var dateUtil: DateUtil

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
                        formatGlucose = { mgdl -> profileUtil.fromMgdlToStringWithUnits(mgdl) },
                        formatTime = { epochMs -> dateUtil.timeString(epochMs) },
                        onCancelStaging = { dexcomOnePlusPlugin.cancelStaging() },
                        onPromote = { allowEarly -> dexcomOnePlusPlugin.promoteStagingToProduction(allowEarly) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DexcomOnePlusStatusScreen(
    onBack: () -> Unit,
    onOpenStart: () -> Unit,
    onOpenWarmup: () -> Unit,
    onOpenLog: () -> Unit,
    stagingStateFlow: StateFlow<StagingState>,
    stagingEvidenceFlow: StateFlow<CgmStagingEvidence?>,
    formatGlucose: (Double) -> String,
    formatTime: (Long) -> String,
    onCancelStaging: () -> Unit,
    onPromote: suspend (Boolean) -> PromotionResult,
) {
    val driver = remember { OnePlusCgmDrivers.default() }
    var state by remember { mutableStateOf(driver.warmupState()) }
    var sessionUp by remember { mutableStateOf(driver.isSessionUp()) }
    val stagingState by stagingStateFlow.collectAsState()
    val stagingEvidence by stagingEvidenceFlow.collectAsState()
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

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.dexcom_oneplus_status_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.dexcom_oneplus_nav_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
        ) {
            Text(
                text = stringResource(R.string.dexcom_oneplus_status_phase, DexcomOnePlusUiLabels.phaseLabel(state.phase)),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.dexcom_oneplus_status_session,
                    if (sessionUp) {
                        stringResource(R.string.dexcom_oneplus_session_up)
                    } else {
                        stringResource(R.string.dexcom_oneplus_session_down)
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    R.string.dexcom_oneplus_status_message,
                    DexcomOnePlusUiLabels.userMessage(state.message),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onOpenStart,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dexcom_oneplus_start_action))
            }
            Button(
                onClick = onOpenWarmup,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.dexcom_oneplus_warmup_open))
            }
            // The way to see what the driver did, without exporting the whole log folder first.
            OutlinedButton(
                onClick = onOpenLog,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.cgm_driver_log_open))
            }

            HorizontalDivider()

            // Dual-sensor (staging / pre-soak) management. The staging sensor is collect-only until
            // the user promotes it — promotion is the ONLY action that switches the loop source.
            Text(
                text = stringResource(R.string.dexcom_oneplus_staging_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            val stagingStateLabel = when (stagingState) {
                StagingState.ABSENT   -> stringResource(R.string.dexcom_oneplus_staging_state_absent)
                StagingState.WARMUP   -> stringResource(R.string.dexcom_oneplus_staging_state_warmup)
                StagingState.SETTLING -> stringResource(R.string.dexcom_oneplus_staging_state_settling)
                StagingState.READY    -> stringResource(R.string.dexcom_oneplus_staging_state_ready)
            }
            Text(
                text = stringResource(R.string.dexcom_oneplus_staging_state, stagingStateLabel),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (stagingState == StagingState.ABSENT) {
                Text(
                    text = stringResource(R.string.dexcom_oneplus_staging_none_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Evidence the staging sensor is really alive: without it the user only sees a state
                // label and cannot tell "settling with data" from "settling with a dead radio".
                Text(
                    text = stringResource(R.string.dexcom_oneplus_staging_readings, stagingEvidence?.validCount ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val lastValue = stagingEvidence?.lastValueMgdl
                val lastAt = stagingEvidence?.lastValueAtEpochMs
                if (lastValue != null && lastAt != null) {
                    Text(
                        text = stringResource(
                            R.string.dexcom_oneplus_staging_last_reading,
                            formatGlucose(lastValue),
                            formatTime(lastAt),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
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
                        onClick = {
                            promoteEarly = false
                            showPromoteConfirm = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.dexcom_oneplus_staging_promote))
                    }
                } else if (earlyPromoteOffered) {
                    // Way out when the production sensor stops before the soak ends. Deliberately an
                    // outlined button with its own warning dialog: it gives up sensor quality, so it
                    // must never look like the normal path.
                    OutlinedButton(
                        onClick = {
                            promoteEarly = true
                            showPromoteConfirm = true
                        },
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
                OutlinedButton(
                    onClick = {
                        onCancelStaging()
                        promoteResultText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.dexcom_oneplus_staging_cancel))
                }
            }
            promoteResultText?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

