package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.TE
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.DateTimeSection
import app.aaps.core.ui.compose.TimeOffsetRow
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.siteRotation.SiteLocationSummary
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.EVENT_TIME_OFFSET_RANGE_MINUTES
import app.aaps.ui.compose.EventDatePicker
import app.aaps.ui.compose.EventTimePicker
import app.aaps.ui.compose.careDialog.CareDialogUiState
import app.aaps.ui.compose.careDialog.CareDialogViewModel
import app.aaps.ui.compose.careDialog.eventTimeChanged
import app.aaps.ui.compose.careDialog.showNotesSection
import app.aaps.ui.compose.careDialog.showSiteRotationSection
import app.aaps.ui.compose.careDialog.titleResId
import app.aaps.ui.compose.careDialog.toElementType

/**
 * The Glass sensor insert screen. Port of `GlassSensorInsertDetailScreen` on `dev_OAPSAIMI`.
 *
 * Only the look is new. It uses the same [CareDialogViewModel] as
 * [app.aaps.ui.compose.careDialog.CareDialogScreen]; the event type comes from the route, as there.
 * The confirmation lines come from [CareDialogViewModel.buildConfirmationSummary] and nothing is
 * recorded before "confirm" there. A sensor insert has no BG and no duration, so only the site, the
 * time and the notes are shown.
 */
@Composable
fun GlassSensorInsertDetailScreen(
    viewModel: CareDialogViewModel = metroViewModel(),
    onNavigateBack: () -> Unit,
    onPickSiteLocation: () -> Unit = {},
    siteLocationResult: Pair<String?, String?>? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The site picked on the site location screen comes back here, as on the normal care dialog.
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
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTimePicker by rememberSaveable { mutableStateOf(false) }

    if (showConfirmation) {
        ElementConfirmationDialog(
            elementType = uiState.eventType.toElementType(),
            lines = viewModel.buildConfirmationSummary(),
            onConfirm = {
                viewModel.confirmAndSave()
                onNavigateBack()
            },
            onDismiss = { showConfirmation = false }
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

    GlassSensorInsertDetailContent(
        uiState = uiState,
        isDark = isGlassDarkMode(),
        dateString = viewModel.dateUtil.dateString(uiState.eventTime),
        timeString = viewModel.dateUtil.timeString(uiState.eventTime),
        onNotesChange = viewModel::updateNotes,
        onTimeOffsetChange = viewModel::updateTimeOffset,
        onNavigateBack = onNavigateBack,
        onConfirmClick = { showConfirmation = true },
        onDateClick = { showDatePicker = true },
        onTimeClick = { showTimePicker = true },
        onPickSiteLocation = onPickSiteLocation
    )
}

/** The stateless body of [GlassSensorInsertDetailScreen]. */
@Composable
internal fun GlassSensorInsertDetailContent(
    uiState: CareDialogUiState,
    isDark: Boolean,
    dateString: String,
    timeString: String,
    onNotesChange: (String) -> Unit,
    onTimeOffsetChange: (Int) -> Unit,
    onNavigateBack: () -> Unit,
    onConfirmClick: () -> Unit,
    onDateClick: () -> Unit,
    onTimeClick: () -> Unit,
    onPickSiteLocation: () -> Unit,
) {
    GlassTreatmentDetailFrame(
        title = stringResource(uiState.eventType.titleResId()),
        isDark = isDark,
        onNavigateBack = onNavigateBack,
        confirmButton = {
            GlassConfirmButton(label = stringResource(CoreUiStrings.ok), enabled = true, onClick = onConfirmClick)
        }
    ) {
        GlassDetailCard(isDark = isDark) {
            if (uiState.showSiteRotationSection) {
                SiteLocationSummary(
                    siteType = TE.Type.SENSOR_CHANGE,
                    lastLocationString = uiState.lastSiteLocationString,
                    selectedLocationString = uiState.selectedSiteLocationString,
                    onPickSiteClick = onPickSiteLocation
                )
            }
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

        if (uiState.showNotesSection) {
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
