package app.aaps.ui.compose.dashboard.glass

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.DateTimeSection
import app.aaps.core.ui.compose.LabeledSwitch
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.TimeOffsetRow
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.insulin.SelectInsulin
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.siteRotation.SiteLocationSummary
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.EVENT_TIME_OFFSET_RANGE_MINUTES
import app.aaps.ui.compose.EventDatePicker
import app.aaps.ui.compose.EventTimePicker
import app.aaps.ui.compose.components.SmbBeingDeliveredBanner
import app.aaps.ui.compose.fillDialog.FillButtonSettingsSheet
import app.aaps.ui.compose.fillDialog.FillDialogUiState
import app.aaps.ui.compose.fillDialog.FillDialogViewModel
import app.aaps.ui.compose.fillDialog.PresetButtonsRow

/**
 * The Glass cannula screen: prime / fill, site change and cartridge change. Port of
 * `GlassCannulaDetailScreen` on `dev_OAPSAIMI`.
 *
 * Only the look is new. It uses the same [FillDialogViewModel] as
 * [app.aaps.ui.compose.fillDialog.FillDialogScreen]: the confirmation lines come from
 * [FillDialogViewModel.buildConfirmationSummary], nothing is done before "confirm" there, and
 * [FillDialogViewModel.confirmAndSave] applies the constraints. The prime is shown only where the
 * pump can prime from here, is disabled on a client, and waits while an SMB is being delivered.
 * Failures arrive after the screen has closed, so the view model reports them on the app dialog bus.
 */
@Composable
fun GlassCannulaDetailScreen(
    viewModel: FillDialogViewModel = metroViewModel(),
    fillButtonsDef: PreferenceSubScreenDef,
    onNavigateBack: () -> Unit,
    onPickSiteLocation: () -> Unit = {},
    siteLocationResult: Pair<String?, String?>? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The site picked on the site location screen comes back here, as on the normal fill dialog.
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
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var showButtonSettings by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is FillDialogViewModel.SideEffect.ShowNoActionDialog -> showNoAction = true
            }
        }
    }

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
                    onNavigateBack()
                },
                onDismiss = { showConfirmation = false }
            )
        }
    }

    if (showNoAction) {
        ElementConfirmationDialog(
            elementType = ElementType.FILL,
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
        FillButtonSettingsSheet(
            settingsDef = fillButtonsDef,
            onDismiss = {
                showButtonSettings = false
                viewModel.refreshPresetButtons()
            }
        )
    }

    val dateString = remember(uiState.eventTime) { viewModel.dateUtil.dateString(uiState.eventTime) }
    val timeString = remember(uiState.eventTime) { viewModel.dateUtil.timeString(uiState.eventTime) }
    val bolusFormat = remember(uiState.bolusStep) { viewModel.decimalFormat() }

    GlassCannulaDetailContent(
        uiState = uiState,
        isDark = isGlassDarkMode(),
        dateString = dateString,
        timeString = timeString,
        bolusFormat = bolusFormat,
        onSiteChangeClick = { viewModel.updateSiteChange(!uiState.siteChange) },
        onCartridgeChangeClick = { viewModel.updateCartridgeChange(!uiState.insulinCartridgeChange) },
        onInsulinChange = viewModel::updateInsulin,
        onInsulinSelect = viewModel::selectInsulin,
        onNotesChange = viewModel::updateNotes,
        onTimeOffsetChange = viewModel::updateTimeOffset,
        onDateClick = { showDatePicker = true },
        onTimeClick = { showTimePicker = true },
        // The settings edit the prime presets, so they go with the prime.
        onSettingsClick = if (uiState.simpleMode || !uiState.primeAvailable) null else {
            { showButtonSettings = true }
        },
        onNavigateBack = onNavigateBack,
        onConfirmClick = { showConfirmation = true },
        onPickSiteLocation = onPickSiteLocation
    )
}

/** The stateless body of [GlassCannulaDetailScreen]: the same controls as `FillDialogContent`, in Glass cards. */
@Composable
internal fun GlassCannulaDetailContent(
    uiState: FillDialogUiState,
    isDark: Boolean,
    dateString: String,
    timeString: String,
    bolusFormat: NumberFormat,
    onSiteChangeClick: () -> Unit,
    onCartridgeChangeClick: () -> Unit,
    onInsulinChange: (Double) -> Unit,
    onInsulinSelect: (ICfg) -> Unit,
    onNotesChange: (String) -> Unit,
    onTimeOffsetChange: (Int) -> Unit,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
    onSettingsClick: (() -> Unit)?,
    onNavigateBack: () -> Unit,
    onConfirmClick: () -> Unit,
    onPickSiteLocation: () -> Unit,
) {
    GlassTreatmentDetailFrame(
        title = stringResourceOrNull(ElementType.FILL.label()) ?: "",
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
        banners = {
            if (uiState.waitsForSmb) SmbBeingDeliveredBanner()
        },
        confirmButton = {
            GlassConfirmButton(
                label = stringResource(CoreUiStrings.ok),
                enabled = uiState.hasAction && !uiState.waitsForSmb,
                onClick = onConfirmClick
            )
        }
    ) {
        GlassDetailCard(isDark = isDark) {
            LabeledSwitch(
                label = stringResource(UiStrings.record_pump_site_change),
                checked = uiState.siteChange,
                onCheckedChange = { onSiteChangeClick() }
            )
            if (uiState.siteChange && uiState.siteRotationEnabled) {
                SiteLocationSummary(
                    siteType = TE.Type.CANNULA_CHANGE,
                    lastLocationString = uiState.lastSiteLocationString,
                    selectedLocationString = uiState.selectedSiteLocationString,
                    onPickSiteClick = onPickSiteLocation
                )
            }
            LabeledSwitch(
                label = stringResource(UiStrings.record_insulin_cartridge_change),
                checked = uiState.insulinCartridgeChange,
                onCheckedChange = { onCartridgeChangeClick() }
            )
            AnimatedVisibility(visible = uiState.showInsulinChange) {
                SelectInsulin(
                    availableInsulins = uiState.availableInsulins,
                    selectedInsulin = uiState.selectedInsulin,
                    activeInsulinLabel = uiState.activeInsulinLabel,
                    onInsulinSelect = onInsulinSelect,
                    concentrationDropDownEnabled = uiState.concentrationEnabled
                )
            }
        }

        // A patch pump primes in its own activation wizard: no prime here at all (insulin outside IOB).
        // On a client the prime cannot be given remotely, so the amount is shown disabled.
        if (uiState.primeAvailable) {
            GlassDetailCard(isDark = isDark) {
                NumberInputRow(
                    labelRef = UiStrings.fill_prime_amount,
                    value = uiState.insulin,
                    onValueChange = onInsulinChange,
                    valueRange = 0.0..uiState.maxInsulin,
                    step = uiState.bolusStep,
                    valueFormat = bolusFormat,
                    unitLabel = CoreUiStrings.insulin_unit_shortname,
                    enabled = uiState.showBolus
                )
                if (uiState.pumpUnitsWarning != null) {
                    Text(
                        text = uiState.pumpUnitsWarning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                // As on DEV: say before OK that a constraint lowers the prime (the confirmation says it too).
                if (uiState.constraintApplied) {
                    Text(
                        text = stringResource(InterfacesStrings.bolus_constraint_applied_warn, uiState.insulin, uiState.insulinAfterConstraints),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (uiState.showBolus) {
                    PresetButtonsRow(
                        presetButton1 = uiState.presetButton1,
                        presetButton2 = uiState.presetButton2,
                        presetButton3 = uiState.presetButton3,
                        bolusStep = uiState.bolusStep,
                        onPresetClick = onInsulinChange
                    )
                }
            }
        }

        GlassDetailCard(isDark = isDark) {
            TimeOffsetRow(
                labelRef = CoreUiStrings.time,
                offsetMinutes = uiState.timeOffsetMinutes,
                onOffsetChange = onTimeOffsetChange,
                resolvedTimeText = if (uiState.timeOffsetMinutes != 0) stringResource(CoreUiStrings.value_with_unit, dateString, timeString) else null,
                offsetRange = EVENT_TIME_OFFSET_RANGE_MINUTES,
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
