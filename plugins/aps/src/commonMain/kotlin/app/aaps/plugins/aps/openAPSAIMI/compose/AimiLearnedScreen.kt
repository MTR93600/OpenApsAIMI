package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdProfileInsulinMemory
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ProfileInsulin
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoActiveSessionUi
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionDocument
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionStatus
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoUiSupport
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The read only "What AIMI learned" page (step S2).
 *
 * It shows the learned insulin action (DIA and peak, the learner range, the values that dosed on
 * the last loop run), the ISF in use against the profile ISF, the machine learning models, an
 * active TPO session with what it rewrote, and the T3C and Harmonia runtime cards that used to be
 * on the Control Center. Opening it writes nothing. The two reset buttons (PK/PD back to the
 * profile insulin, TPO revert) each ask first.
 *
 * @param loadLive what the loop knows now; read when the page opens
 * @param currentTpoSession the TPO session now, or null
 * @param revertTpo ends the TPO session now and puts the values back; true when it did
 */
@Composable
internal fun AimiLearnedScreen(
    preferences: Preferences,
    loadLive: suspend () -> AimiLearnedLive,
    currentTpoSession: () -> TpoSessionDocument?,
    revertTpo: () -> Boolean,
    storage: AimiStorage,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var live by remember { mutableStateOf(AimiLearnedLive()) }
    var revision by remember { mutableIntStateOf(0) }
    var showTpoRevertConfirm by remember { mutableStateOf(false) }
    var showPkpdResetConfirm by remember { mutableStateOf(false) }
    val tpoRevertDoneMessage = stringResource(ApsStrings.aimi_tpo_revert_done)
    val pkpdResetDoneMessage = stringResource(ApsStrings.aimi_pkpd_sync_done)

    LaunchedEffect(revision) {
        live = withContext(aapsIoDispatcher) { loadLive() }
    }
    val tpoSessionUi = remember(revision) { TpoUiSupport.buildActiveSessionUi(session = currentTpoSession(), nowMs = aimiWallClockMs()) }
    val t3cRuntime = remember(revision) { loadLatestT3cRuntimeSnapshot(storage) }
    val harmoniaRuntime = remember(revision) { loadLatestHarmoniaRuntimeSnapshot(storage) }

    if (showTpoRevertConfirm) {
        ConfirmDialog(
            title = ApsStrings.aimi_tpo_revert_confirm_title,
            body = ApsStrings.aimi_tpo_revert_confirm_body,
            confirm = ApsStrings.aimi_tpo_revert_now,
            onConfirm = {
                showTpoRevertConfirm = false
                if (revertTpo()) {
                    revision++
                    scope.launch { snackbarHostState.showSnackbar(tpoRevertDoneMessage) }
                }
            },
            onDismiss = { showTpoRevertConfirm = false },
        )
    }
    if (showPkpdResetConfirm) {
        ConfirmDialog(
            title = ApsStrings.aimi_pkpd_reset_confirm_title,
            body = ApsStrings.aimi_pkpd_reset_confirm_body,
            confirm = ApsStrings.aimi_pkpd_reset_confirm_ok,
            onConfirm = {
                showPkpdResetConfirm = false
                PkpdProfileInsulinMemory.requestReset(preferences)
                revision++
                scope.launch { snackbarHostState.showSnackbar(pkpdResetDoneMessage) }
            },
            onDismiss = { showPkpdResetConfirm = false },
        )
    }

    ProvidePreferenceTheme {
        CompositionLocalProvider(LocalPreferences provides preferences) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    AapsTopAppBar(
                        title = { Text(stringResource(ApsStrings.aimi_learned_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(CoreUiStrings.back),
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
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                ) {
                    Text(
                        text = stringResource(ApsStrings.aimi_learned_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PkpdSection(preferences = preferences, live = live)
                    IsfSection(live = live)
                    ModelsSection(live = live)
                    TpoSection(sessionUi = tpoSessionUi, onRevertNow = { showTpoRevertConfirm = true })
                    SectionTitle(ApsStrings.aimi_learned_runtime_title)
                    T3cRuntimeCard(runtime = t3cRuntime)
                    HarmoniaRuntimeCard(runtime = harmoniaRuntime)
                    SectionTitle(ApsStrings.aimi_learned_reset_title)
                    OutlinedButton(onClick = { showPkpdResetConfirm = true }) {
                        Text(stringResource(ApsStrings.aimi_pkpd_reset_to_profile_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun PkpdSection(preferences: Preferences, live: AimiLearnedLive) {
    SectionTitle(ApsStrings.aimi_learned_pkpd_title)
    PkpdSimpleStatusCard(
        preferences = preferences,
        profileDiaHours = live.profileDiaHours,
        profilePeakMin = live.profilePeakMin,
    )
    val profile = live.profileDiaHours?.let { dia -> live.profilePeakMin?.let { peak -> ProfileInsulin.of(dia, peak) } }
    val range = profile?.let(AimiLearned::learnerRange)
    BodyText(
        if (range == null) {
            stringResource(ApsStrings.aimi_learned_pkpd_range_unknown)
        } else {
            stringResource(
                ApsStrings.aimi_learned_pkpd_range,
                aimiFmt1(range.diaMinH),
                aimiFmt1(range.diaMaxH),
                range.peakMinMin.roundToInt().toString(),
                range.peakMinMax.roundToInt().toString(),
            )
        }
    )
}

@Composable
private fun IsfSection(live: AimiLearnedLive) {
    SectionTitle(ApsStrings.aimi_learned_isf_title)
    val percent = AimiLearned.isfPercentOfProfile(live.isfInUseMgdl, live.profileIsfMgdl)
    if (percent == null) {
        BodyText(stringResource(ApsStrings.aimi_learned_isf_unknown))
    } else {
        BodyText(stringResource(ApsStrings.aimi_learned_isf_percent, percent))
        BodyText(stringResource(ApsStrings.aimi_learned_isf_hint))
    }
}

@Composable
private fun ModelsSection(live: AimiLearnedLive) {
    SectionTitle(ApsStrings.aimi_learned_models_title)
    BodyText(stringResource(ApsStrings.aimi_learned_model_smb, stringResource(AimiLearned.smbModelState(live.smbModel).label())))
    BodyText(stringResource(ApsStrings.aimi_learned_model_basal, stringResource(AimiLearned.basalModelState(live.basalModel).label())))
}

private fun AimiModelState.label(): TextRef = when (this) {
    AimiModelState.UNKNOWN         -> ApsStrings.aimi_learned_model_unknown
    AimiModelState.NO_MODEL        -> ApsStrings.aimi_learned_model_none
    AimiModelState.FILE_NOT_LOADED -> ApsStrings.aimi_learned_model_not_loaded
    AimiModelState.FILE_PRESENT    -> ApsStrings.aimi_learned_model_present
    AimiModelState.IN_USE          -> ApsStrings.aimi_learned_model_in_use
    AimiModelState.PAUSED          -> ApsStrings.aimi_learned_model_paused
}

/** The TPO session and what it rewrote. Moved here from the Control Center. */
@Composable
private fun TpoSection(sessionUi: TpoActiveSessionUi?, onRevertNow: () -> Unit) {
    SectionTitle(ApsStrings.aimi_learned_tpo_title)
    if (sessionUi == null) {
        BodyText(stringResource(ApsStrings.aimi_learned_tpo_none))
        return
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(text = stringResource(ApsStrings.aimi_tpo_active_session_title), style = MaterialTheme.typography.titleMedium)
            if (sessionUi.status == TpoSessionStatus.PENDING_LLM) {
                Text(text = stringResource(ApsStrings.aimi_tpo_active_session_pending), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(
                    ApsStrings.aimi_tpo_active_session_summary,
                    stringResource(sessionUi.packTitle),
                    sessionUi.tierLabel,
                    sessionUi.remainingMinutes,
                    sessionUi.changedKeyCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            sessionUi.deltaPreviewLines.forEach { line -> Text(text = line, style = MaterialTheme.typography.bodySmall) }
            if (sessionUi.extraChangeCount > 0) {
                Text(text = stringResource(ApsStrings.aimi_tpo_extra_changes, sessionUi.extraChangeCount), style = MaterialTheme.typography.bodySmall)
            }
            if (sessionUi.status == TpoSessionStatus.ACTIVE) {
                Button(onClick = onRevertNow, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(ApsStrings.aimi_tpo_revert_now))
                }
            }
        }
    }
}

/** Moved here from the Control Center. */
@Composable
private fun HarmoniaRuntimeCard(runtime: AimiHarmoniaRuntimeSnapshot) {
    RuntimeCard(
        title = ApsStrings.aimi_control_center_harmonia_title,
        summary = ApsStrings.aimi_control_center_harmonia_summary,
        pills = buildList {
            add(runtime.status.labelResId)
            if (runtime.selectedForProduction) add(ApsStrings.aimi_control_center_harmonia_chip_selected)
            if (!runtime.addsSmbAuthority) add(ApsStrings.aimi_control_center_harmonia_chip_no_smb_authority)
        },
        unavailableBody = ApsStrings.aimi_control_center_harmonia_unavailable_body.takeIf {
            runtime.status == AimiHarmoniaRuntimeStatus.Unavailable && runtime.details.isEmpty()
        },
        details = runtime.details,
    )
}

/** Moved here from the Control Center. */
@Composable
private fun T3cRuntimeCard(runtime: AimiT3cRuntimeSnapshot) {
    RuntimeCard(
        title = ApsStrings.aimi_control_center_t3c_title,
        summary = ApsStrings.aimi_control_center_t3c_summary,
        pills = buildList {
            add(runtime.status.labelResId)
            add(runtime.owner.labelResId)
            if (runtime.authorityApplied) add(ApsStrings.aimi_control_center_t3c_chip_authority_applied)
            if (runtime.shadowOnly) add(ApsStrings.aimi_control_center_t3c_chip_shadow_only)
        },
        unavailableBody = ApsStrings.aimi_control_center_t3c_unavailable_body.takeIf {
            runtime.status == AimiT3cRuntimeStatus.Unavailable && runtime.details.isEmpty()
        },
        details = runtime.details,
    )
}

@Composable
private fun RuntimeCard(
    title: TextRef,
    summary: TextRef,
    pills: List<TextRef>,
    unavailableBody: TextRef?,
    details: List<AimiRuntimeDetail>,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Text(text = stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
            ) {
                pills.forEach { Pill(text = stringResource(it)) }
            }
            if (unavailableBody != null) {
                Text(
                    text = stringResource(unavailableBody),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                details.forEach { DetailRow(detail = it) }
            }
        }
    }
}

@Composable
private fun DetailRow(detail: AimiRuntimeDetail) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = stringResource(detail.titleResId), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = when {
                detail.valueResId != null         -> stringResource(detail.valueResId)
                !detail.valueText.isNullOrBlank() -> detail.valueText
                else                              -> stringResource(ApsStrings.aimi_control_center_not_configured)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Pill(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.small),
        )
    }
}

@Composable
private fun SectionTitle(text: TextRef) {
    Text(text = stringResource(text), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun BodyText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ConfirmDialog(
    title: TextRef,
    body: TextRef,
    confirm: TextRef,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CoreUiStrings.cancel)) } },
    )
}
