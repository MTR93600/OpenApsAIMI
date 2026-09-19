package app.aaps.plugins.aps.openAPSAIMI.context.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent
import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
import app.aaps.plugins.aps.openAPSAIMI.context.ContextPreset
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentation
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilder
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Compose port of the parked `ContextActivity` (+ its `ContextViewModel`, `ContextIntentAdapter`,
 * `PatientSignalGaugeBinder`).
 *
 * `ContextViewModel` was never actually wired to `ContextActivity` - the Activity's own doc comment
 * called itself a "simplified version without ViewModel", and called [ContextManager] directly. This
 * screen follows the Activity, not the unused ViewModel: same calls, same fields.
 */
@Composable
fun AimiContextScreen(
    contextManager: ContextManager,
    preferences: Preferences,
    healthContextRepository: HealthContextRepository,
    aapsLogger: AAPSLogger,
    dateUtil: DateUtil,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    var intents by remember { mutableStateOf<List<Pair<String, ContextIntent>>>(emptyList()) }
    var chatText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var contextEnabled by remember { mutableStateOf(preferences.get(BooleanKey.OApsAIMIContextEnabled)) }
    var llmEnabled by remember { mutableStateOf(preferences.get(BooleanKey.OApsAIMIContextLLMEnabled)) }
    var presentation by remember { mutableStateOf<PatientStatePresentation?>(null) }
    var extendDialogIntentId by remember { mutableStateOf<String?>(null) }
    var showClearAllDialog by remember { mutableStateOf(false) }
    var parseErrorMessage by remember { mutableStateOf<String?>(null) }

    val llmFailureMessage = stringResource(R.string.aimi_context_llm_failure_message)
    val offlineFailureMessage = stringResource(R.string.aimi_context_offline_failure_message)
    val errorPrefix = stringResource(R.string.aimi_context_error_prefix)

    fun refreshIntents() {
        intents = contextManager.getAllIntents().toList()
    }

    fun refreshPatientState() {
        val snapshot = PatientStateRuntimeRepository.getLatest()
        presentation = snapshot?.let { PatientStatePresentationBuilder.build(it, dateUtil.now()) }
    }

    LaunchedEffect(Unit) {
        refreshIntents()
        launch { PatientStateRuntimeRepository.updates.collectLatest { refreshPatientState() } }
        while (isActive) {
            refreshPatientState()
            delay(60_000L)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshIntents()
                refreshPatientState()
                scope.launch(Dispatchers.IO) {
                    runCatching { healthContextRepository.fetchSnapshot() }
                        .onFailure { aapsLogger.error(LTag.APS, "AimiContextScreen physio snapshot refresh failed", it) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun onSend() {
        val text = chatText.trim()
        if (text.isBlank() || busy) return
        busy = true
        scope.launch {
            try {
                val ids = contextManager.addIntent(text)
                if (ids.isNotEmpty()) {
                    chatText = ""
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.aimi_context_intent_added, ids.size)
                    )
                } else {
                    parseErrorMessage = if (llmEnabled) llmFailureMessage else offlineFailureMessage
                }
                refreshIntents()
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "AimiContextScreen parse error", e)
                snackbarHostState.showSnackbar("$errorPrefix: ${e.message ?: ""}")
            } finally {
                busy = false
            }
        }
    }

    fun onPreset(preset: ContextPreset) {
        scope.launch {
            try {
                contextManager.addPreset(preset)
                refreshIntents()
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("$errorPrefix: ${e.message ?: ""}")
            }
        }
    }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(R.string.context_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = AapsSpacing.large)
                    .padding(bottom = AapsSpacing.xxLarge)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                Text(
                    text = stringResource(R.string.aimi_context_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )

                PatientStatePanel(presentation)

                OutlinedTextField(
                    value = chatText,
                    onValueChange = { chatText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.aimi_context_chat_hint)) },
                    minLines = 2,
                    maxLines = 4,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                    Button(onClick = { onSend() }, enabled = !busy) {
                        Text(stringResource(R.string.aimi_context_send_button))
                    }
                    OutlinedButton(onClick = { chatText = "" }, enabled = !busy) {
                        Text(stringResource(R.string.aimi_context_clear_button))
                    }
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.padding(AapsSpacing.small))
                    }
                }

                Text(
                    text = stringResource(R.string.aimi_context_presets_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                PresetChips(onPreset = ::onPreset)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.aimi_context_active_intents_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    TextButton(onClick = { showClearAllDialog = true }, enabled = intents.isNotEmpty()) {
                        Text(stringResource(R.string.aimi_context_clear_all_button))
                    }
                }
                if (intents.isEmpty()) {
                    Text(
                        text = stringResource(R.string.aimi_context_empty_state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    intents.forEach { (id, intent) ->
                        IntentRow(
                            intent = intent,
                            nowMs = dateUtil.now(),
                            onRemove = {
                                contextManager.removeIntent(id)
                                refreshIntents()
                            },
                            onExtend = { extendDialogIntentId = id },
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = AapsSpacing.medium))
                Text(
                    text = stringResource(R.string.aimi_context_settings_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.aimi_context_module_enabled_label))
                    Switch(
                        checked = contextEnabled,
                        onCheckedChange = {
                            contextEnabled = it
                            preferences.put(BooleanKey.OApsAIMIContextEnabled, it)
                        },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.aimi_context_llm_enabled_label))
                    Switch(
                        checked = llmEnabled,
                        onCheckedChange = {
                            llmEnabled = it
                            preferences.put(BooleanKey.OApsAIMIContextLLMEnabled, it)
                        },
                    )
                }
            }
        }
    }

    val currentExtendId = extendDialogIntentId
    if (currentExtendId != null) {
        ExtendDurationDialog(
            onDismiss = { extendDialogIntentId = null },
            onPick = { minutes ->
                contextManager.extendDuration(currentExtendId, minutes.minutes)
                extendDialogIntentId = null
                refreshIntents()
            },
        )
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(stringResource(R.string.aimi_context_clear_all_confirm_title)) },
            text = { Text(stringResource(R.string.aimi_context_clear_all_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        contextManager.clearAll()
                        refreshIntents()
                        showClearAllDialog = false
                    },
                ) { Text(stringResource(R.string.aimi_context_clear_all_confirm_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text(stringResource(app.aaps.core.ui.R.string.cancel))
                }
            },
        )
    }

    val currentParseError = parseErrorMessage
    if (currentParseError != null) {
        AlertDialog(
            onDismissRequest = { parseErrorMessage = null },
            title = { Text(stringResource(R.string.aimi_context_parse_failed_title)) },
            text = { Text(currentParseError) },
            confirmButton = {
                TextButton(onClick = { parseErrorMessage = null }) {
                    Text(stringResource(app.aaps.core.ui.R.string.ok))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetChips(onPreset: (ContextPreset) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
    ) {
        ContextPreset.ALL_PRESETS.forEach { preset ->
            AssistChip(
                onClick = { onPreset(preset) },
                label = { Text("${preset.icon} ${preset.displayName}") },
            )
        }
    }
}

@Composable
private fun IntentRow(
    intent: ContextIntent,
    nowMs: Long,
    onRemove: () -> Unit,
    onExtend: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(AapsSpacing.medium)) {
            Text(text = intentSummary(intent), fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = timeRemainingLabel(intent, nowMs),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    confidenceLabel(intent.confidence)?.let {
                        Text(text = it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row {
                    TextButton(onClick = onExtend) { Text(stringResource(R.string.aimi_context_extend_action)) }
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.aimi_context_remove_action)) }
                }
            }
        }
    }
}

@Composable
private fun ExtendDurationDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val options = listOf(
        15 to stringResource(R.string.aimi_context_duration_minutes, 15),
        30 to stringResource(R.string.aimi_context_duration_minutes, 30),
        60 to stringResource(R.string.aimi_context_duration_hours, 1),
        120 to stringResource(R.string.aimi_context_duration_hours, 2),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aimi_context_extend_dialog_title)) },
        text = {
            Column {
                options.forEach { (minutes, label) ->
                    TextButton(onClick = { onPick(minutes) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.wrapContentWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(app.aaps.core.ui.R.string.cancel)) }
        },
    )
}

@Composable
private fun PatientStatePanel(presentation: PatientStatePresentation?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(AapsSpacing.medium)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.aimi_context_patient_state_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (presentation == null) {
                Text(
                    text = stringResource(R.string.context_patient_state_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
                return@Column
            }
            Text(text = presentation.updatedSummary, style = MaterialTheme.typography.bodySmall)
            Text(text = presentation.modeHeadline, fontWeight = FontWeight.Medium)
            Text(text = presentation.narrative, style = MaterialTheme.typography.bodyMedium)

            LabeledValue(stringResource(R.string.aimi_context_patient_state_live_body_label), presentation.physioLiveSummary)
            LabeledValue(stringResource(R.string.aimi_context_patient_state_thermal_label), presentation.thermalSummary)
            LabeledValue(stringResource(R.string.aimi_context_patient_state_phase_label), presentation.physiologySummary)
            LabeledValue(stringResource(R.string.aimi_context_patient_state_intent_label), presentation.intentSummary)

            Text(
                text = stringResource(R.string.aimi_context_patient_state_signals_label),
                style = MaterialTheme.typography.labelMedium,
            )
            presentation.signalGauges.forEach { gauge ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${gauge.label} ${gauge.percent}%", style = MaterialTheme.typography.bodySmall)
                }
                LinearProgressIndicator(
                    progress = { gauge.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(text = presentation.signalSummary, style = MaterialTheme.typography.bodySmall)

            LabeledValue(stringResource(R.string.aimi_context_patient_state_bias_label), presentation.deliverySummary)
            LabeledValue(stringResource(R.string.aimi_context_patient_state_reasons_label), presentation.reasonSummary)
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(modifier = Modifier.padding(top = AapsSpacing.small)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun String.titleCase(): String = lowercase().replaceFirstChar { it.uppercase() }

@Composable
private fun intentSummary(intent: ContextIntent): String = when (intent) {
    is ContextIntent.Activity            -> stringResource(
        R.string.aimi_context_intent_activity, intent.activityType.name.titleCase(), intent.intensity.name.titleCase()
    )

    is ContextIntent.Illness             -> stringResource(
        R.string.aimi_context_intent_illness, intent.symptomType.name.titleCase(), intent.intensity.name.titleCase()
    )

    is ContextIntent.Stress              -> stringResource(
        R.string.aimi_context_intent_stress, intent.stressType.name.titleCase(), intent.intensity.name.titleCase()
    )

    is ContextIntent.UnannouncedMealRisk -> stringResource(
        R.string.aimi_context_intent_meal_risk, intent.intensity.name.titleCase()
    )

    is ContextIntent.Alcohol             -> stringResource(
        R.string.aimi_context_intent_alcohol, intent.units, intent.intensity.name.titleCase()
    )

    is ContextIntent.Travel              -> stringResource(
        R.string.aimi_context_intent_travel, intent.timezoneShiftHours, intent.intensity.name.titleCase()
    )

    is ContextIntent.MenstrualCycle      -> stringResource(
        R.string.aimi_context_intent_cycle, intent.phase.name.titleCase()
    )

    is ContextIntent.SlowCarbMeal        -> stringResource(
        R.string.aimi_context_intent_slow_carb, intent.intensity.name.titleCase()
    )

    is ContextIntent.HypoRecovery        -> stringResource(
        R.string.aimi_context_intent_hypo_recovery, intent.intensity.name.titleCase()
    )

    is ContextIntent.Custom              -> intent.description
}

@Composable
private fun timeRemainingLabel(intent: ContextIntent, nowMs: Long): String {
    val remainingMinutes = (intent.endTimeMs - nowMs) / 60_000L
    return when {
        remainingMinutes <= 0L  -> stringResource(R.string.aimi_context_time_expired)
        remainingMinutes < 60L  -> stringResource(R.string.aimi_context_time_minutes, remainingMinutes)
        else                    -> stringResource(
            R.string.aimi_context_time_hours_minutes, remainingMinutes / 60L, remainingMinutes % 60L
        )
    }
}

@Composable
private fun confidenceLabel(confidence: Float): String? = when {
    confidence >= 0.90f -> stringResource(R.string.aimi_context_confidence_high)
    confidence >= 0.70f -> stringResource(R.string.aimi_context_confidence_medium)
    confidence >= 0.50f -> stringResource(R.string.aimi_context_confidence_low)
    else                -> null
}
