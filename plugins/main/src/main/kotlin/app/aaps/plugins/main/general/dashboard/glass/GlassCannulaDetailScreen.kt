package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.DateTimeSection
import app.aaps.core.ui.compose.EventTimeRow
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.insulin.SelectInsulin
import app.aaps.core.ui.compose.navigation.labelResId
import app.aaps.core.ui.compose.preference.PreferenceSheetContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.siteRotation.SiteLocationSummary
import app.aaps.ui.compose.EventDatePicker
import app.aaps.ui.compose.EventTimePicker
import app.aaps.ui.compose.fillDialog.FillDialogViewModel
import app.aaps.core.ui.R as CoreUiR
import app.aaps.ui.R as UiR

/**
 * Glass-branded re-skin of [app.aaps.ui.compose.fillDialog.FillDialogScreen]. Binds to the SAME
 * [FillDialogViewModel] instance the shared screen uses — every user action here calls the exact same
 * ViewModel method the shared screen calls, so the site/cartridge-change logging, the prime delivery, and
 * the site-location picker round trip are all inherited for free. This file only supplies the GlycoCalm
 * -styled shell.
 */
@Composable
fun GlassCannulaDetailScreen(
    viewModel: FillDialogViewModel = hiltViewModel(),
    fillButtonsDef: PreferenceSubScreenDef,
    onNavigateBack: () -> Unit,
    onPickSiteLocation: () -> Unit = {},
    siteLocationResult: Pair<String?, String?>? = null,
    isDark: Boolean,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val bg = if (isDark) Color(0xFF213145) else Color(0xFFF1F5F9)
    val card = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)
    val primary = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0D1B2A)
    val muted = if (isDark) Color(0xFF778598) else Color(0xFF64748B)

    // Process the site-location result coming back from the picker screen — same mechanism the shared
    // FillDialogScreen uses (its own AppNavGraph.kt destination round-trips this through a savedStateHandle).
    LaunchedEffect(siteLocationResult) {
        siteLocationResult?.let { (locationName, arrowName) ->
            if (locationName != null) {
                val location = try {
                    TE.Location.valueOf(locationName)
                } catch (_: Exception) {
                    TE.Location.NONE
                }
                viewModel.updateSiteLocation(location)
            }
            if (arrowName != null) {
                val arrow = try {
                    TE.Arrow.valueOf(arrowName)
                } catch (_: Exception) {
                    TE.Arrow.NONE
                }
                viewModel.updateSiteArrow(arrow)
            }
        }
    }

    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    var showNoAction by rememberSaveable { mutableStateOf(false) }
    var showButtonSettings by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is FillDialogViewModel.SideEffect.ShowNoActionDialog -> showNoAction = true
            }
        }
    }

    // Confirmation dialog — the summary is built locally (no round trip); accepting it calls
    // confirmAndSave(), which is what actually delivers the prime / records the site & cartridge changes.
    if (showConfirmation) {
        if (!uiState.hasAction) {
            showConfirmation = false
            showNoAction = true
        } else {
            ElementConfirmationDialog(
                elementType = ElementType.FILL,
                lines = viewModel.buildConfirmationSummary(),
                onConfirm = {
                    viewModel.confirmAndSave()
                    showConfirmation = false
                    onNavigateBack()
                },
                onDismiss = { showConfirmation = false }
            )
        }
    }

    if (showNoAction) {
        ElementConfirmationDialog(
            elementType = ElementType.FILL,
            message = stringResource(CoreUiR.string.no_action_selected),
            onConfirm = { showNoAction = false },
            onDismiss = { showNoAction = false }
        )
    }

    if (showButtonSettings) {
        FillButtonSettingsSheet(
            settingsDef = fillButtonsDef,
            onDismiss = {
                showButtonSettings = false
                viewModel.refreshPresetButtons()
            }
        )
    }

    if (showDatePicker) {
        EventDatePicker(
            eventTimeMillis = uiState.eventTime,
            onEventTimeChanged = { viewModel.updateEventTime(it) },
            onDismiss = { showDatePicker = false }
        )
    }

    if (showTimePicker) {
        EventTimePicker(
            eventTimeMillis = uiState.eventTime,
            onEventTimeChanged = { viewModel.updateEventTime(it) },
            onDismiss = { showTimePicker = false }
        )
    }

    val focusManager = LocalFocusManager.current

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .clearFocusOnTap(focusManager)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = primary)
                }
                Text(
                    text = stringResource(ElementType.FILL.labelResId()),
                    color = primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (!uiState.simpleMode) {
                    IconButton(onClick = { showButtonSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(CoreUiR.string.settings), tint = muted)
                    }
                }
            }

            // Site / cartridge change card
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.updateSiteChange(!uiState.siteChange) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(UiR.string.record_pump_site_change), color = primary, modifier = Modifier.weight(1f))
                    Switch(checked = uiState.siteChange, onCheckedChange = viewModel::updateSiteChange)
                }

                if (uiState.siteChange && uiState.siteRotationEnabled) {
                    SiteLocationSummary(
                        siteType = TE.Type.CANNULA_CHANGE,
                        lastLocationString = uiState.lastSiteLocationString,
                        selectedLocationString = uiState.selectedSiteLocationString,
                        onPickSiteClick = onPickSiteLocation
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.updateCartridgeChange(!uiState.insulinCartridgeChange) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(UiR.string.record_insulin_cartridge_change), color = primary, modifier = Modifier.weight(1f))
                    Switch(checked = uiState.insulinCartridgeChange, onCheckedChange = viewModel::updateCartridgeChange)
                }

                if (uiState.showInsulinChange) {
                    SelectInsulin(
                        availableInsulins = uiState.availableInsulins,
                        selectedInsulin = uiState.selectedInsulin,
                        activeInsulinLabel = uiState.activeInsulinLabel,
                        onInsulinSelect = viewModel::selectInsulin,
                        concentrationDropDownEnabled = uiState.concentrationEnabled
                    )
                }
            }

            // Amount card
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NumberInputRow(
                    labelResId = UiR.string.fill_prime_amount,
                    value = uiState.insulin,
                    onValueChange = viewModel::updateInsulin,
                    valueRange = 0.0..uiState.maxInsulin,
                    step = uiState.bolusStep,
                    valueFormat = viewModel.decimalFormat(),
                    unitLabel = stringResource(CoreUiR.string.insulin_unit_shortname),
                    enabled = uiState.showBolus
                )

                uiState.pumpUnitsWarning?.let { warning ->
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                if (uiState.constraintApplied) {
                    Text(
                        text = stringResource(CoreUiR.string.bolus_constraint_applied_warn, uiState.insulin, uiState.insulinAfterConstraints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (uiState.showBolus) {
                    val presets = listOf(uiState.presetButton1, uiState.presetButton2, uiState.presetButton3).filter { it > 0 }
                    if (presets.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        ) {
                            presets.forEach { amount ->
                                val label = if (uiState.bolusStep <= 0.051) "%.2f".format(amount) else "%.1f".format(amount)
                                Button(onClick = { viewModel.updateInsulin(amount) }) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }

                val dateString = viewModel.dateUtil.dateString(uiState.eventTime)
                val timeString = viewModel.dateUtil.timeString(uiState.eventTime)
                EventTimeRow(
                    timeChanged = uiState.eventTimeChanged,
                    displayText = "$dateString $timeString",
                    dateTimeContent = {
                        DateTimeSection(
                            dateString = dateString,
                            timeString = timeString,
                            eventTimeChanged = uiState.eventTimeChanged,
                            onDateClick = { showDatePicker = true },
                            onTimeClick = { showTimePicker = true }
                        )
                    }
                )
            }

            // Notes card — only when preferences turn notes on for dialogs.
            if (uiState.showNotesFromPreferences) {
                Column(modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(4.dp)) {
                    TextField(
                        value = uiState.notes,
                        onValueChange = viewModel::updateNotes,
                        label = { Text(stringResource(CoreUiR.string.notes_label)) },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        singleLine = false,
                        maxLines = 3
                    )
                }
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    showConfirmation = true
                },
                enabled = uiState.hasAction,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(CoreUiR.string.ok))
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FillButtonSettingsSheet(
    settingsDef: PreferenceSubScreenDef,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        PreferenceSheetContent(
            settingsDef = settingsDef,
            modifier = Modifier.padding(bottom = 24.dp)
        )
    }
}
