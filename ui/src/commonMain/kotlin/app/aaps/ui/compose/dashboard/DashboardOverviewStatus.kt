package app.aaps.ui.compose.dashboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.ui.compose.overview.OverviewStatusSection
import app.aaps.ui.compose.overview.statusLights.StatusViewModel

/**
 * Shared dashboard presentation of the existing Overview device-status state and actions.
 * The ViewModel remains the source of status; role gating and navigation are the same as Overview.
 */
@Composable
internal fun DashboardOverviewStatus(
    statusViewModel: StatusViewModel,
    statusLightsDef: PreferenceSubScreenDef,
    onNavigate: (NavigationRequest) -> Unit,
    commandsAllowed: Boolean,
) {
    val status by statusViewModel.uiState.collectAsStateWithLifecycle()
    var expanded by rememberSaveable { mutableStateOf(false) }
    OverviewStatusSection(
        sensorStatus = status.sensorStatus,
        insulinStatus = status.insulinStatus,
        cannulaStatus = status.cannulaStatus,
        batteryStatus = status.batteryStatus,
        showFill = status.showFill,
        showPumpBatteryChange = status.showPumpBatteryChange,
        commandsAllowed = commandsAllowed,
        onNavigate = onNavigate,
        statusLightsDef = statusLightsDef,
        expanded = expanded,
        onExpandedChange = { expanded = it },
    )
}
