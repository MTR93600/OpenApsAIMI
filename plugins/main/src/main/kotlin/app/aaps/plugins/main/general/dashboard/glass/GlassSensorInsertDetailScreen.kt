package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.DateTimeSection
import app.aaps.core.ui.compose.EventTimeRow
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.navigation.labelResId
import app.aaps.core.ui.compose.siteRotation.SiteLocationSummary
import app.aaps.ui.compose.EventDatePicker
import app.aaps.ui.compose.EventTimePicker
import app.aaps.ui.compose.careDialog.CareDialogViewModel
import app.aaps.ui.compose.careDialog.showNotesSection
import app.aaps.ui.compose.careDialog.showSiteRotationSection
import app.aaps.core.ui.R as CoreUiR

/**
 * Glass-branded re-skin of [app.aaps.ui.compose.careDialog.CareDialogScreen], hardcoded to
 * [app.aaps.ui.compose.careDialog.CareportalEventType.SENSOR_INSERT]. Binds to the SAME [CareDialogViewModel]
 * instance the shared screen uses (pre-selected to SENSOR_INSERT via the same `{eventTypeOrdinal}` nav-arg
 * mechanism the shared `care_dialog/{ordinal}` route already relies on) — every user action here calls the
 * exact same ViewModel method the shared screen calls, so the batch-executor round trip is inherited for free.
 *
 * Since this pill is hardcoded to a single event type, only the sections the shared `CareDialogUiState` marks
 * relevant for SENSOR_INSERT are rendered: site rotation and notes. The BG-check and duration sections only
 * apply to other event types (BGCHECK/QUESTION/ANNOUNCEMENT and NOTE/EXERCISE respectively) and are omitted —
 * the shared screen only conditionally renders them too, so this is a narrowing, not a scope cut.
 */
@Composable
fun GlassSensorInsertDetailScreen(
    viewModel: CareDialogViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onPickSiteLocation: () -> Unit = {},
    siteLocationResult: Pair<String?, String?>? = null,
    isDark: Boolean,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val bg = if (isDark) Color(0xFF213145) else Color(0xFFF1F5F9)
    val card = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)
    val primary = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0D1B2A)

    // Process the site-location result coming back from the picker screen — same mechanism the shared
    // CareDialogScreen uses (its own AppNavGraph.kt destination round-trips this through a savedStateHandle).
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

    // Confirmation dialog — the summary is built locally (no round trip); accepting it calls
    // confirmAndSave(), which is what actually records the therapy event.
    if (showConfirmation) {
        ElementConfirmationDialog(
            elementType = ElementType.SENSOR_INSERT,
            lines = viewModel.buildConfirmationSummary(),
            onConfirm = {
                viewModel.confirmAndSave()
                showConfirmation = false
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
                    text = stringResource(ElementType.SENSOR_INSERT.labelResId()),
                    color = primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            // Site rotation + time card
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.showSiteRotationSection) {
                    SiteLocationSummary(
                        siteType = TE.Type.SENSOR_CHANGE,
                        lastLocationString = uiState.lastSiteLocationString,
                        selectedLocationString = uiState.selectedSiteLocationString,
                        onPickSiteClick = onPickSiteLocation
                    )
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

            // Notes card
            if (uiState.showNotesSection) {
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
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(CoreUiR.string.ok))
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
