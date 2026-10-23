package app.aaps.plugins.source.compose

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.interfaces.source.CgmSensorLifecycle
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.source.SourceStrings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** How often the status screen goes back to the database for the newest reading. */
private const val GLUCOSE_REFRESH_MILLIS = 30_000L

/**
 * The card for the sensor that feeds the loop, shared by Android and iPhone.
 *
 * Accented so it is never confused with the pre-soak card on Android. [extra] is for what only
 * one phone offers below the facts - on Android the "correct insertion date" action.
 */
@Composable
fun DexcomOnePlusProductionCard(
    phaseLabel: String,
    phaseState: CgmUiState,
    sessionUp: Boolean,
    message: String,
    lifecycle: CgmSensorLifecycle?,
    newestGlucose: GV?,
    formatGlucose: (Double) -> String,
    formatTime: (Long) -> String,
    formatAge: (Long) -> String,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    CgmCard(accent = true) {
        CgmCardHeader(stringResource(SourceStrings.dexcom_oneplus_production_heading)) {
            CgmStateChip(state = phaseState, label = phaseLabel)
        }
        // Only this driver's own readings are shown here: the newest value in the database can come
        // from another source, and labelling someone else's reading as this sensor's would be a lie.
        val ownReading = newestGlucose?.takeIf { it.sourceSensor == SourceSensor.DEXCOM_ONEPLUS_NATIVE }
        if (ownReading != null) {
            Text(
                text = stringResource(
                    SourceStrings.dexcom_oneplus_staging_last_reading,
                    formatGlucose(ownReading.value),
                    formatTime(ownReading.timestamp),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Text(
                text = stringResource(SourceStrings.dexcom_oneplus_production_no_reading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CgmKeyValueRow(
            label = stringResource(SourceStrings.cgm_session_label),
            value = stringResource(
                if (sessionUp) SourceStrings.dexcom_oneplus_session_up else SourceStrings.dexcom_oneplus_session_down,
            ),
        )
        lifecycle?.ageMs?.let { age ->
            Text(
                text = stringResource(SourceStrings.dexcom_oneplus_sensor_age, formatAge(age)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        extra()
    }
}

/**
 * The sensor status screen on a phone with one sensor slot.
 *
 * It is the production card of the Android status screen and nothing else: the phase, the link,
 * the newest reading of this sensor, the sensor age and the last driver message. The pre-soak
 * card, the promotion and the end-of-life prompt are left out because they are about a second
 * slot, which a one-slot phone does not have. The calibration card is left out too, for now.
 *
 * The driver state is read every second and the database every 30 seconds, as on Android.
 */
@Composable
fun DexcomOnePlusStatusSummaryScreen(
    onBack: () -> Unit,
    lifecycleFlow: StateFlow<CgmSensorLifecycle?>,
    lastGlucose: suspend () -> GV?,
    formatGlucose: (Double) -> String,
    formatTime: (Long) -> String,
    formatAge: (Long) -> String,
) {
    var state by remember { mutableStateOf(OnePlusCgmDrivers.default().warmupState()) }
    var sessionUp by remember { mutableStateOf(OnePlusCgmDrivers.default().isSessionUp()) }
    var newestGlucose by remember { mutableStateOf<GV?>(null) }
    val lifecycle by lifecycleFlow.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            // Asked again every time: a promotion or a switch to the real driver changes which
            // instance `default()` hands out.
            val driver = OnePlusCgmDrivers.default()
            state = driver.warmupState()
            sessionUp = driver.isSessionUp()
            delay(1_000L)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            newestGlucose = lastGlucose()
            delay(GLUCOSE_REFRESH_MILLIS)
        }
    }

    CgmScaffold(
        title = stringResource(SourceStrings.dexcom_oneplus_status_title),
        onNavigate = onBack,
    ) {
        CgmLazyColumn {
            item(key = "production") {
                DexcomOnePlusProductionCard(
                    phaseLabel = DexcomOnePlusUiLabels.phaseLabel(state.phase),
                    phaseState = state.phase.toUiState(),
                    sessionUp = sessionUp,
                    message = DexcomOnePlusUiLabels.userMessage(state.message),
                    lifecycle = lifecycle,
                    newestGlucose = newestGlucose,
                    formatGlucose = formatGlucose,
                    formatTime = formatTime,
                    formatAge = formatAge,
                )
            }
        }
    }
}
