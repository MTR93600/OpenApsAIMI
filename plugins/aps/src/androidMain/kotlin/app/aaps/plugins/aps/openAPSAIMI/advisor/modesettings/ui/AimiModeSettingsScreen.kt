package app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.plugins.aps.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val MODE_ACTIVITY_PREFS_FILE = "aimi_mode_activity"

/**
 * The 4 modes this screen edits. [noteText] is read downstream by
 * `app.aaps.plugins.aps.openAPSAIMI.therapy.findActiveLunchEvents` (and its 3 siblings) as a
 * case-insensitive substring match on the therapy-event NOTE this screen creates, and from there
 * feeds `DetermineBasalAIMI2`'s mode-specific dosing. It must stay exactly "Lunch" / "Dinner" /
 * "Breakfast" / "High Carb" - not translated, not reworded - or the match breaks silently.
 */
enum class AimiModeType(val durationPrefKey: String, val noteText: String, val tabLabelRes: Int) {
    LUNCH("aimi_mode_lunch_duration", "Lunch", R.string.aimi_mode_settings_tab_lunch),
    DINNER("aimi_mode_dinner_duration", "Dinner", R.string.aimi_mode_settings_tab_dinner),
    BFAST("aimi_mode_bfast_duration", "Breakfast", R.string.aimi_mode_settings_tab_bfast),
    HIGHCARB("aimi_mode_hc_duration", "High Carb", R.string.aimi_mode_settings_tab_highcarb),
}

private data class ModePrefKeys(val prebolus1: DoubleKey, val prebolus2: DoubleKey, val reactivity: DoubleKey, val interval: IntKey)

private fun modePrefKeysFor(mode: AimiModeType): ModePrefKeys = when (mode) {
    AimiModeType.LUNCH    -> ModePrefKeys(DoubleKey.OApsAIMILunchPrebolus, DoubleKey.OApsAIMILunchPrebolus2, DoubleKey.OApsAIMILunchFactor, IntKey.OApsAIMILunchinterval)
    AimiModeType.DINNER   -> ModePrefKeys(DoubleKey.OApsAIMIDinnerPrebolus, DoubleKey.OApsAIMIDinnerPrebolus2, DoubleKey.OApsAIMIDinnerFactor, IntKey.OApsAIMIDinnerinterval)
    AimiModeType.BFAST    -> ModePrefKeys(DoubleKey.OApsAIMIBFPrebolus, DoubleKey.OApsAIMIBFPrebolus2, DoubleKey.OApsAIMIBFFactor, IntKey.OApsAIMIBFinterval)
    AimiModeType.HIGHCARB -> ModePrefKeys(DoubleKey.OApsAIMIHighCarbPrebolus, DoubleKey.OApsAIMIHighCarbPrebolus2, DoubleKey.OApsAIMIHCFactor, IntKey.OApsAIMIHCinterval)
}

/**
 * Compose port of the parked `AimiModeSettingsActivity`: a live control surface for the dosing
 * engine, not a decorative settings page. "Activate" writes a therapy-event NOTE that
 * [app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2] reads through `therapy.kt`'s mode
 * detectors to switch into that meal's prebolus/reactivity profile.
 *
 * Duration stays in its own `SharedPreferences` file (not an `IntKey`/preference-tree entry) -
 * an existing decision, kept as-is here, not reopened by this port.
 */
@Composable
fun AimiModeSettingsScreen(
    preferences: Preferences,
    persistenceLayer: PersistenceLayer,
    aapsLogger: AAPSLogger,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val modeActivityPrefs = remember { context.getSharedPreferences(MODE_ACTIVITY_PREFS_FILE, Context.MODE_PRIVATE) }

    var selectedMode by remember { mutableStateOf(AimiModeType.LUNCH) }
    var prebolus1Text by remember { mutableStateOf("") }
    var prebolus2Text by remember { mutableStateOf("") }
    var reactivityText by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("") }
    var intervalText by remember { mutableStateOf("") }
    var showActivateConfirm by remember { mutableStateOf(false) }
    var activating by remember { mutableStateOf(false) }

    val activatedTemplate = stringResource(R.string.aimi_mode_settings_activated_message)
    val activateErrorTemplate = stringResource(R.string.aimi_mode_settings_activate_error)
    val modeDisplayName = stringResource(selectedMode.tabLabelRes)

    fun loadValues(mode: AimiModeType) {
        val keys = modePrefKeysFor(mode)
        prebolus1Text = preferences.get(keys.prebolus1).toString()
        prebolus2Text = preferences.get(keys.prebolus2).toString()
        reactivityText = preferences.get(keys.reactivity).toString()
        durationText = modeActivityPrefs.getInt(mode.durationPrefKey, 60).toString()
        intervalText = preferences.get(keys.interval).toString()
    }

    LaunchedEffect(Unit) { loadValues(selectedMode) }

    fun saveValues() {
        val keys = modePrefKeysFor(selectedMode)
        val prebolus1 = prebolus1Text.toDoubleOrNull() ?: 0.0
        val prebolus2 = prebolus2Text.toDoubleOrNull() ?: 0.0
        val reactivity = reactivityText.toDoubleOrNull() ?: 100.0
        val duration = durationText.toIntOrNull() ?: 60
        val interval = intervalText.toIntOrNull() ?: 5

        preferences.put(keys.prebolus1, prebolus1)
        preferences.put(keys.prebolus2, prebolus2)
        preferences.put(keys.reactivity, reactivity)
        modeActivityPrefs.edit().putInt(selectedMode.durationPrefKey, duration).apply()
        preferences.put(keys.interval, interval)
    }

    fun onSelectMode(mode: AimiModeType) {
        if (selectedMode == mode) return
        selectedMode = mode
        loadValues(mode)
    }

    fun confirmActivate() {
        if (activating) return
        activating = true
        val modeNote = selectedMode.noteText
        val durationMin = durationText.toIntOrNull() ?: 60
        val durationMs = durationMin * 60 * 1000L
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val te = TE(
                        timestamp = System.currentTimeMillis(),
                        type = TE.Type.NOTE,
                        note = modeNote,
                        duration = durationMs,
                        enteredBy = "AIMI Advisor",
                        glucoseUnit = GlucoseUnit.MGDL,
                    )
                    persistenceLayer.insertOrUpdateTherapyEvent(te)
                }
                showActivateConfirm = false
                snackbarHostState.showSnackbar(String.format(Locale.getDefault(), activatedTemplate, modeNote, durationMin))
                onBack()
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "AimiModeSettingsScreen activate failed", e)
                showActivateConfirm = false
                snackbarHostState.showSnackbar(String.format(Locale.getDefault(), activateErrorTemplate, e.message ?: e.toString()))
            } finally {
                activating = false
            }
        }
    }

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(R.string.aimi_mode_settings_title)) },
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
                PrimaryTabRow(selectedTabIndex = selectedMode.ordinal) {
                    AimiModeType.entries.forEach { mode ->
                        Tab(
                            selected = selectedMode == mode,
                            onClick = { onSelectMode(mode) },
                            text = { Text(stringResource(mode.tabLabelRes)) },
                        )
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(AapsSpacing.medium),
                        verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                    ) {
                        OutlinedTextField(
                            value = prebolus1Text,
                            onValueChange = { prebolus1Text = it },
                            label = { Text(stringResource(R.string.aimi_mode_settings_prebolus1_label)) },
                            placeholder = { Text(stringResource(R.string.aimi_mode_settings_prebolus1_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = prebolus2Text,
                            onValueChange = { prebolus2Text = it },
                            label = { Text(stringResource(R.string.aimi_mode_settings_prebolus2_label)) },
                            placeholder = { Text(stringResource(R.string.aimi_mode_settings_prebolus2_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = reactivityText,
                            onValueChange = { reactivityText = it },
                            label = { Text(stringResource(R.string.aimi_mode_settings_reactivity_label)) },
                            placeholder = { Text(stringResource(R.string.aimi_mode_settings_reactivity_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = { durationText = it },
                            label = { Text(stringResource(R.string.aimi_mode_settings_duration_label)) },
                            placeholder = { Text(stringResource(R.string.aimi_mode_settings_duration_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = intervalText,
                            onValueChange = { intervalText = it },
                            label = { Text(stringResource(R.string.aimi_mode_settings_interval_label)) },
                            placeholder = { Text(stringResource(R.string.aimi_mode_settings_interval_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Button(onClick = { saveValues(); onBack() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.aimi_mode_settings_save_button))
                }

                OutlinedButton(
                    onClick = {
                        saveValues()
                        showActivateConfirm = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.aimi_mode_settings_activate_button, modeDisplayName))
                }
            }
        }
    }

    if (showActivateConfirm) {
        val durationMin = durationText.toIntOrNull() ?: 60
        AlertDialog(
            onDismissRequest = { if (!activating) showActivateConfirm = false },
            title = { Text(stringResource(R.string.aimi_mode_settings_activate_confirm_title, modeDisplayName)) },
            text = { Text(stringResource(R.string.aimi_mode_settings_activate_confirm_message, modeDisplayName, durationMin)) },
            confirmButton = {
                TextButton(onClick = { confirmActivate() }, enabled = !activating) {
                    Text(stringResource(app.aaps.core.ui.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showActivateConfirm = false }, enabled = !activating) {
                    Text(stringResource(app.aaps.core.ui.R.string.cancel))
                }
            },
        )
    }
}
