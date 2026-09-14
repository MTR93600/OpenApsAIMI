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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.DateTimeSection
import app.aaps.core.ui.compose.InsulinSelector
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.navigation.labelResId
import app.aaps.core.ui.compose.preference.PreferenceSheetContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.ui.compose.EventDatePicker
import app.aaps.ui.compose.EventTimePicker
import app.aaps.ui.compose.components.DialogStatusBar
import app.aaps.ui.compose.insulinDialog.InsulinDialogViewModel
import app.aaps.ui.compose.insulinDialog.confirmEnabled
import app.aaps.ui.compose.insulinDialog.eventTimeChanged
import app.aaps.ui.compose.insulinDialog.recordOnlyEnabled
import app.aaps.ui.compose.insulinDialog.timeLayoutVisible
import app.aaps.ui.compose.overview.chips.CobUiState
import app.aaps.ui.compose.overview.chips.IobUiState
import app.aaps.ui.compose.overview.graphs.BgInfoUiState
import kotlinx.coroutines.flow.StateFlow
import app.aaps.core.ui.R as CoreUiR
import app.aaps.core.keys.R as KeysR
import app.aaps.ui.R as UiR

/**
 * Glass-branded re-skin of [app.aaps.ui.compose.insulinDialog.InsulinDialogScreen]. Binds to the SAME
 * [InsulinDialogViewModel] instance the shared screen uses — every user action here calls the exact same
 * ViewModel method the shared screen calls, so the confirmation flow, the batch-executor round trip, and the
 * protection checks are all inherited for free. This file only supplies the GlycoCalm-styled shell.
 */
@Composable
fun GlassInsulinDetailScreen(
    viewModel: InsulinDialogViewModel = hiltViewModel(),
    bgInfoState: StateFlow<BgInfoUiState>,
    iobUiState: StateFlow<IobUiState>,
    cobUiState: StateFlow<CobUiState>,
    insulinButtonsDef: PreferenceSubScreenDef,
    onNavigateBack: () -> Unit,
    onShowDeliveryError: (String) -> Unit,
    isDark: Boolean,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bgInfo by bgInfoState.collectAsStateWithLifecycle()
    val iob by iobUiState.collectAsStateWithLifecycle()
    val cob by cobUiState.collectAsStateWithLifecycle()

    val bg = if (isDark) Color(0xFF213145) else Color(0xFFF1F5F9)
    val card = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)
    val primary = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0D1B2A)
    val muted = if (isDark) Color(0xFF778598) else Color(0xFF64748B)

    // The master's prepared confirmation (bolusId + its merged lines), set via the ShowConfirmation side effect.
    var confirmation by remember { mutableStateOf<Pair<Long, List<ConfirmationLine>>?>(null) }
    var showNoAction by rememberSaveable { mutableStateOf(false) }
    var showButtonSettings by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is InsulinDialogViewModel.SideEffect.ShowDeliveryError  -> onShowDeliveryError(effect.comment)
                is InsulinDialogViewModel.SideEffect.ShowNoActionDialog -> showNoAction = true
                is InsulinDialogViewModel.SideEffect.ShowConfirmation   -> confirmation = effect.bolusId to effect.lines
            }
        }
    }

    // Confirmation dialog — renders the MASTER's prepared lines (set via the ShowConfirmation side effect
    // after prepareAndConfirm()). Accepting it calls commit(bolusId), which is what actually delivers.
    confirmation?.let { (bolusId, lines) ->
        ElementConfirmationDialog(
            elementType = ElementType.INSULIN,
            lines = lines,
            onConfirm = {
                viewModel.commit(bolusId)
                confirmation = null
                onNavigateBack()
            },
            onDismiss = { confirmation = null }
        )
    }

    if (showNoAction) {
        ElementConfirmationDialog(
            elementType = ElementType.INSULIN,
            message = stringResource(CoreUiR.string.no_action_selected),
            onConfirm = { showNoAction = false },
            onDismiss = { showNoAction = false }
        )
    }

    if (showButtonSettings) {
        InsulinButtonSettingsSheet(
            settingsDef = insulinButtonsDef,
            onDismiss = {
                showButtonSettings = false
                viewModel.refreshInsulinButtons()
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
                    text = stringResource(ElementType.INSULIN.labelResId()),
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

            // Status summary — same BG/IOB/COB data the shared screen's DialogStatusBar shows.
            DialogStatusBar(bgInfo = bgInfo, iob = iob, cob = cob)

            // Amount card
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NumberInputRow(
                    labelResId = CoreUiR.string.overview_insulin_label,
                    value = uiState.insulin,
                    onValueChange = viewModel::updateInsulin,
                    valueRange = 0.0..uiState.maxInsulin,
                    step = uiState.bolusStep,
                    valueFormat = viewModel.decimalFormatter.pumpSupportedBolusFormat(uiState.bolusStep),
                    unitLabel = stringResource(CoreUiR.string.insulin_unit_shortname)
                )
                val increments = listOf(uiState.insulinButtonIncrement1, uiState.insulinButtonIncrement2, uiState.insulinButtonIncrement3).filter { it != 0.0 }
                if (increments.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                    ) {
                        increments.forEach { amount ->
                            val formatted = viewModel.decimalFormatter.toPumpSupportedBolus(amount, uiState.bolusStep)
                            val label = if (amount > 0) "+$formatted" else formatted
                            Button(onClick = {
                                focusManager.clearFocus()
                                viewModel.addInsulin(amount)
                            }) {
                                Text(label)
                            }
                        }
                    }
                }
            }

            // Eating soon / record only card
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.updateEatingSoonTt(!uiState.eatingSoonTtChecked) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(UiR.string.start_eating_soon_tt), color = primary, modifier = Modifier.weight(1f))
                    Switch(checked = uiState.eatingSoonTtChecked, onCheckedChange = viewModel::updateEatingSoonTt)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = uiState.recordOnlyEnabled) { viewModel.updateRecordOnly(!uiState.recordOnlyChecked) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(CoreUiR.string.bolus_recorded_only),
                        color = if (uiState.forcedRecordOnly) MaterialTheme.colorScheme.error else primary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = uiState.recordOnlyChecked,
                        onCheckedChange = viewModel::updateRecordOnly,
                        enabled = uiState.recordOnlyEnabled
                    )
                }
            }

            // Insulin type selector — shown only when recording a record-only entry (iCfg matters for
            // record-only, per the shared InsulinDialogViewModel's own gating).
            if (uiState.timeLayoutVisible) {
                Column(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(CoreUiR.string.record_insulin_hint),
                        color = MaterialTheme.colorScheme.error,
                    )
                    InsulinSelector(
                        insulins = uiState.insulins,
                        selected = uiState.selectedIcfg,
                        onSelect = viewModel::selectInsulinType
                    )
                }
            }

            // Time selection — same gate as above, same shared-screen behavior.
            if (uiState.timeLayoutVisible) {
                Column(
                    modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NumberInputRow(
                        labelResId = CoreUiR.string.time,
                        value = uiState.timeOffsetMinutes.toDouble(),
                        onValueChange = { viewModel.updateTimeOffset(it.toInt()) },
                        valueRange = -12.0 * 60..12.0 * 60,
                        step = 5.0,
                        unitLabelResId = KeysR.string.units_min
                    )
                    DateTimeSection(
                        dateString = viewModel.dateUtil.dateString(uiState.eventTime),
                        timeString = viewModel.dateUtil.timeString(uiState.eventTime),
                        eventTimeChanged = uiState.eventTimeChanged,
                        onDateClick = { showDatePicker = true },
                        onTimeClick = { showTimePicker = true }
                    )
                }
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
                    viewModel.prepareAndConfirm()
                },
                enabled = uiState.confirmEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (uiState.insulin > 0.0) stringResource(CoreUiR.string.format_insulin_units, uiState.insulin)
                    else stringResource(CoreUiR.string.ok)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InsulinButtonSettingsSheet(
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
