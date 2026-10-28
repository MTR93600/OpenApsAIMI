package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.DateTimeSection
import app.aaps.core.ui.compose.InsulinSelector
import app.aaps.core.ui.compose.LabeledSwitch
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.TimeOffsetRow
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.EventDatePicker
import app.aaps.ui.compose.EventTimePicker
import app.aaps.ui.compose.components.DialogStatusBar
import app.aaps.ui.compose.components.RecordOnlyBanner
import app.aaps.ui.compose.components.SmbBeingDeliveredBanner
import app.aaps.ui.compose.insulinDialog.InsulinButtonSettingsSheet
import app.aaps.ui.compose.insulinDialog.InsulinDialogUiState
import app.aaps.ui.compose.insulinDialog.InsulinDialogViewModel
import app.aaps.ui.compose.insulinDialog.InsulinQuickAddButtons
import app.aaps.ui.compose.insulinDialog.confirmEnabled
import app.aaps.ui.compose.insulinDialog.displayStep
import app.aaps.ui.compose.insulinDialog.eventTimeChanged
import app.aaps.ui.compose.insulinDialog.recordOnlyEnabled
import app.aaps.ui.compose.insulinDialog.timeLayoutVisible
import app.aaps.ui.compose.insulinDialog.waitsForSmb
import app.aaps.ui.compose.overview.chips.CobUiState
import app.aaps.ui.compose.overview.chips.IobUiState
import app.aaps.ui.compose.overview.graphs.BgInfoUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * The Glass insulin screen. Port of `GlassInsulinDetailScreen` on `dev_OAPSAIMI`.
 *
 * Only the look is new. It uses the same [InsulinDialogViewModel] as
 * [app.aaps.ui.compose.insulinDialog.InsulinDialogScreen] and handles its side effects in the same way:
 * OK asks the master to prepare the bolus, the master's lines are shown in the confirmation dialog,
 * and only "confirm" there calls [InsulinDialogViewModel.commit]. A dismissed or replaced confirmation
 * is discarded on the master, and the dialog closes at the master's deadline. The record-only and
 * "SMB is being delivered" banners and the constraints of the view model apply here as there.
 */
@Composable
fun GlassInsulinDetailScreen(
    viewModel: InsulinDialogViewModel = metroViewModel(),
    insulinButtonsDef: PreferenceSubScreenDef,
    bgInfoState: StateFlow<BgInfoUiState>,
    iobUiState: StateFlow<IobUiState>,
    cobUiState: StateFlow<CobUiState>,
    onNavigateBack: () -> Unit,
    onShowDeliveryError: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val bgInfo by bgInfoState.collectAsStateWithLifecycle()
    val iob by iobUiState.collectAsStateWithLifecycle()
    val cob by cobUiState.collectAsStateWithLifecycle()

    // The master's prepared confirmation (bolusId + its merged lines), set by the ShowConfirmation side effect.
    var confirmation by remember { mutableStateOf<InsulinDialogViewModel.SideEffect.ShowConfirmation?>(null) }
    var showNoAction by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is InsulinDialogViewModel.SideEffect.ShowDeliveryError  -> onShowDeliveryError(effect.comment)
                is InsulinDialogViewModel.SideEffect.ShowNoActionDialog -> showNoAction = true

                is InsulinDialogViewModel.SideEffect.ShowConfirmation   -> {
                    // A newer prepare replaces one still shown (a second tap): drop the old one on the master.
                    confirmation?.let { viewModel.discard(it.bolusId) }
                    confirmation = effect
                }
            }
        }
    }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showButtonSettings by rememberSaveable { mutableStateOf(false) }

    confirmation?.let { shown ->
        ElementConfirmationDialog(
            elementType = ElementType.INSULIN,
            lines = shown.lines,
            onConfirm = {
                viewModel.commit(shown.bolusId)
                confirmation = null
                onNavigateBack()
            },
            onDismiss = {
                viewModel.discard(shown.bolusId)
                confirmation = null
            },
            closeAt = shown.closeAt
        )
    }

    if (showNoAction) {
        ElementConfirmationDialog(
            elementType = ElementType.INSULIN,
            message = stringResource(CoreUiStrings.no_action_selected),
            onConfirm = { showNoAction = false },
            onDismiss = { showNoAction = false }
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

    if (showButtonSettings) {
        InsulinButtonSettingsSheet(
            settingsDef = insulinButtonsDef,
            onDismiss = {
                showButtonSettings = false
                viewModel.refreshInsulinButtons()
            }
        )
    }

    GlassInsulinDetailContent(
        uiState = uiState,
        isDark = isGlassDarkMode(),
        bgInfo = bgInfo,
        iob = iob,
        cob = cob,
        dateString = viewModel.dateUtil.dateString(uiState.eventTime),
        timeString = viewModel.dateUtil.timeString(uiState.eventTime),
        bolusFormat = viewModel.decimalFormatter.pumpSupportedBolusFormat(uiState.displayStep),
        formatAmount = { viewModel.decimalFormatter.toPumpSupportedBolus(it, uiState.bolusStep) },
        onEatingSoonChange = viewModel::updateEatingSoonTt,
        onRecordOnlyChange = viewModel::updateRecordOnly,
        onInsulinChange = { viewModel.updateInsulin(it) },
        onAddInsulin = viewModel::addInsulin,
        onTimeOffsetChange = { viewModel.updateTimeOffset(it) },
        onNotesChange = viewModel::updateNotes,
        onDateClick = { showDatePicker = true },
        onTimeClick = { showTimePicker = true },
        onInsulinTypeSelect = viewModel::selectInsulinType,
        onSettingsClick = if (uiState.simpleMode) null else {
            { showButtonSettings = true }
        },
        onNavigateBack = onNavigateBack,
        onConfirmClick = { viewModel.prepareAndConfirm() }
    )
}

/** The stateless body of [GlassInsulinDetailScreen]: the same controls as `InsulinDialogContent`, in Glass cards. */
@Composable
internal fun GlassInsulinDetailContent(
    uiState: InsulinDialogUiState,
    isDark: Boolean,
    bgInfo: BgInfoUiState,
    iob: IobUiState,
    cob: CobUiState,
    dateString: String,
    timeString: String,
    bolusFormat: NumberFormat,
    formatAmount: (Double) -> String,
    onEatingSoonChange: (Boolean) -> Unit,
    onRecordOnlyChange: (Boolean) -> Unit,
    onInsulinChange: (Double) -> Unit,
    onAddInsulin: (Double) -> Unit,
    onTimeOffsetChange: (Int) -> Unit,
    onNotesChange: (String) -> Unit,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
    onInsulinTypeSelect: (ICfg) -> Unit,
    onSettingsClick: (() -> Unit)?,
    onNavigateBack: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    GlassTreatmentDetailFrame(
        title = stringResourceOrNull(ElementType.INSULIN.label()) ?: "",
        isDark = isDark,
        onNavigateBack = onNavigateBack,
        actions = {
            if (onSettingsClick != null) {
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(CoreUiStrings.settings),
                        tint = GlassColors.textMuted(isDark)
                    )
                }
            }
        },
        // Outside the scrolling content, as on the normal dialog: a screen reader user must hear that
        // this insulin is recorded only, and the OK button waits while an SMB is being delivered.
        banners = {
            if (uiState.forcedRecordOnly) RecordOnlyBanner()
            if (uiState.waitsForSmb) SmbBeingDeliveredBanner()
        },
        confirmButton = {
            GlassConfirmButton(
                label = if (uiState.insulin > 0.0) stringResource(InterfacesStrings.format_insulin_units, uiState.insulin)
                else stringResource(CoreUiStrings.ok),
                enabled = uiState.confirmEnabled,
                onClick = onConfirmClick
            )
        }
    ) {
        DialogStatusBar(bgInfo = bgInfo, iob = iob, cob = cob)

        GlassDetailCard(isDark = isDark) {
            NumberInputRow(
                labelRef = CoreUiStrings.overview_insulin_label,
                value = uiState.insulin,
                onValueChange = onInsulinChange,
                valueRange = 0.0..uiState.maxInsulin,
                step = uiState.bolusStep,
                valueFormat = bolusFormat,
                unitLabel = CoreUiStrings.insulin_unit_shortname
            )
            InsulinQuickAddButtons(
                increment1 = uiState.insulinButtonIncrement1,
                increment2 = uiState.insulinButtonIncrement2,
                increment3 = uiState.insulinButtonIncrement3,
                formatAmount = formatAmount,
                onAddInsulin = onAddInsulin
            )
        }

        GlassDetailCard(isDark = isDark) {
            // Not offered on a client whose role may give insulin but not set a temp target.
            if (uiState.canSetTempTarget) LabeledSwitch(
                label = stringResource(UiStrings.start_eating_soon_tt),
                checked = uiState.eatingSoonTtChecked,
                onCheckedChange = onEatingSoonChange
            )
            LabeledSwitch(
                label = stringResource(InterfacesStrings.bolus_recorded_only),
                checked = uiState.recordOnlyChecked,
                onCheckedChange = onRecordOnlyChange,
                enabled = uiState.recordOnlyEnabled,
                labelColor = if (uiState.forcedRecordOnly) MaterialTheme.colorScheme.error else GlassColors.textBright(isDark)
            )
        }

        // Insulin type and time matter only for a record-only entry, as on the normal dialog.
        if (uiState.timeLayoutVisible) {
            GlassDetailCard(isDark = isDark) {
                Text(
                    text = stringResource(CoreUiStrings.record_insulin_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                InsulinSelector(
                    insulins = uiState.insulins,
                    selected = uiState.selectedIcfg,
                    onSelect = onInsulinTypeSelect
                )
            }
            GlassDetailCard(isDark = isDark) {
                TimeOffsetRow(
                    labelRef = CoreUiStrings.time,
                    offsetMinutes = uiState.timeOffsetMinutes,
                    onOffsetChange = onTimeOffsetChange,
                    resolvedTimeText = if (uiState.timeOffsetMinutes != 0) timeString else null,
                    offsetRange = -12 * 60..12 * 60,
                    dateTimeContent = {
                        DateTimeSection(
                            dateString = dateString,
                            timeString = timeString,
                            eventTimeChanged = uiState.eventTimeChanged,
                            onDateClick = onDateClick,
                            onTimeClick = onTimeClick
                        )
                    }
                )
            }
        }

        if (uiState.showNotesFromPreferences) {
            GlassDetailCard(isDark = isDark) {
                TextField(
                    value = uiState.notes,
                    onValueChange = onNotesChange,
                    label = { Text(stringResource(CoreUiStrings.notes_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    maxLines = 3
                )
            }
        }
    }
}
