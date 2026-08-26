package app.aaps.plugins.source.activities

import android.app.Activity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.MetroAppCompatActivity
import app.aaps.plugins.libre3.identity.Libre3SensorStore
import app.aaps.plugins.libre3.nfc.Libre3NfcFailure
import app.aaps.plugins.libre3.nfc.Libre3NfcReader
import app.aaps.plugins.libre3.nfc.Libre3NfcScanResult
import app.aaps.plugins.libre3.nfc.Libre3NfcSession
import app.aaps.plugins.source.Libre3BlePermissionHelper
import app.aaps.plugins.source.Libre3NativePlugin
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.CgmCard
import app.aaps.plugins.source.compose.CgmCardHeader
import app.aaps.plugins.source.compose.CgmCardTone
import app.aaps.plugins.source.compose.CgmHelpCard
import app.aaps.plugins.source.compose.CgmLazyColumn
import app.aaps.plugins.source.compose.CgmNavIcon
import app.aaps.plugins.source.compose.CgmScaffold
import app.aaps.plugins.source.compose.CgmStateChip
import app.aaps.plugins.source.compose.CgmStepper
import app.aaps.plugins.source.compose.CgmUiState
import app.aaps.plugins.source.compose.CgmWidth
import app.aaps.plugins.source.compose.Libre3UiLabels
import app.aaps.plugins.source.compose.rememberCgmWindow
import javax.inject.Inject

/**
 * Starting a sensor.
 *
 * There is nothing to type. The user holds the phone on the sensor, the NFC step reads it, stores
 * it, and only then may Bluetooth start. The reader runs only while this screen is in front of the
 * user, so a phone in a pocket never scans.
 *
 * Unlike the ONE+ start screen this one never splits into two panes: pairing is a single NFC tap,
 * so there is no list of candidates and nothing to put in a second column. One column at every
 * size is the honest layout here — the value of the redesign is that the screen scrolls, that the
 * help folds away, and that the current step is always named.
 */
class Libre3StartActivity : MetroAppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    @Inject lateinit var plugin: Libre3NativePlugin

    private var reader: Libre3NfcReader? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Libre3SensorStore(this)
        val nfcReader = Libre3NfcReader(Libre3NfcSession(store))
        reader = nfcReader
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    Libre3StartScreen(
                        activity = this,
                        reader = nfcReader,
                        onBack = { finish() },
                        onSensorScanned = { result ->
                            // A different sensor counts its own minutes from zero, so the repeat
                            // guard has to start again. Without this every reading of the new
                            // sensor would be refused as "already seen".
                            plugin.onSensorChanged()
                            plugin.syncDriverFromPrefs()
                            if (result.readyForBle) {
                                plugin.connectStoredSensor(result.identity.bleAddress)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reader?.shutdown()
        reader = null
    }
}

@Composable
internal fun Libre3StartScreen(
    activity: Activity,
    reader: Libre3NfcReader,
    onBack: () -> Unit,
    onSensorScanned: (Libre3NfcScanResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val window = rememberCgmWindow()
    var scanned by remember { mutableStateOf<Libre3NfcScanResult?>(null) }
    var failure by remember { mutableStateOf<Libre3NfcFailure?>(null) }
    var nfcAvailable by remember { mutableStateOf(true) }
    var permissionsGranted by remember { mutableStateOf(Libre3BlePermissionHelper.hasAll(activity)) }

    // The reader follows the screen, not the first drawing of it. Turning it on once would leave
    // the screen looking ready while the reader was off after the user came back from another app.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    nfcAvailable = reader.enable(
                        activity = activity,
                        onResult = { result ->
                            failure = null
                            scanned = result
                            onSensorScanned(result)
                        },
                        onError = { reason -> failure = reason },
                    )
                }

                Lifecycle.Event.ON_PAUSE  -> reader.disable(activity)
                else                      -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            reader.disable(activity)
        }
    }

    // Allow → NFC on → hold the phone on the sensor → warming up. The same four-step shape as the
    // ONE+ flow, with the content this one actually has.
    val currentStep = when {
        !permissionsGranted -> 0
        !nfcAvailable       -> 1
        scanned == null     -> 2
        else                -> 3
    }

    CgmScaffold(
        title = stringResource(R.string.libre3_start_title),
        onNavigate = onBack,
        modifier = modifier,
        // A task the user either completes by scanning or abandons — see the AapsTopAppBar convention.
        navIcon = CgmNavIcon.Close,
    ) {
        CgmLazyColumn {
            item(key = "stepper") {
                CgmStepper(
                    currentStep = currentStep,
                    labels = listOf(
                        stringResource(R.string.libre3_step_permissions),
                        stringResource(R.string.libre3_step_nfc),
                        stringResource(R.string.libre3_step_scan),
                        stringResource(R.string.libre3_step_warmup),
                    ),
                    compact = window.width == CgmWidth.Compact,
                )
            }

            item(key = "state") {
                val result = scanned
                val problem = failure
                when {
                    result != null       -> CgmCard(accent = true) {
                        CgmCardHeader(stringResource(R.string.libre3_scan_ready)) {
                            CgmStateChip(
                                state = CgmUiState.Ready,
                                label = stringResource(R.string.libre3_phase_ready),
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.libre3_start_scanned,
                                result.identity.serialNumber,
                                Libre3UiLabels.generationLabel(result.identity.generation),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.libre3_start_done_note),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    problem != null      -> CgmCard(tone = CgmCardTone.Warning) {
                        CgmCardHeader(stringResource(R.string.libre3_scan_problem))
                        Text(
                            text = Libre3UiLabels.nfcFailureLabel(problem),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        // Nothing was written, so saying so removes the fear of having half-paired
                        // a sensor and makes retrying the obvious next move.
                        Text(
                            text = stringResource(R.string.libre3_scan_retry_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    !permissionsGranted  -> CgmCard {
                        CgmCardHeader(stringResource(R.string.libre3_scan_heading))
                        Text(
                            text = stringResource(R.string.libre3_permissions_needed),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                Libre3BlePermissionHelper.requestMissing(activity)
                                permissionsGranted = Libre3BlePermissionHelper.hasAll(activity)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.libre3_request_permissions))
                        }
                    }

                    !nfcAvailable        -> CgmCard(tone = CgmCardTone.Warning) {
                        CgmCardHeader(stringResource(R.string.libre3_scan_heading))
                        Text(
                            text = stringResource(R.string.libre3_start_nfc_off),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    else                 -> CgmCard {
                        CgmCardHeader(stringResource(R.string.libre3_scan_heading)) {
                            CgmStateChip(
                                state = CgmUiState.Working,
                                label = stringResource(R.string.libre3_scan_waiting_chip),
                            )
                        }
                        Text(
                            text = stringResource(R.string.libre3_start_scan_hint),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.libre3_start_scan_waiting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item(key = "help") {
                Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)) {
                    CgmHelpCard(title = stringResource(R.string.libre3_help_how_to_start)) {
                        Text(
                            text = stringResource(R.string.libre3_start_steps),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    CgmHelpCard(
                        title = stringResource(R.string.libre3_help_before_scanning),
                        tone = CgmCardTone.Warning,
                    ) {
                        Text(
                            text = stringResource(R.string.libre3_start_conflict_warning),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun Libre3StartScreenPreview() {
    MaterialTheme {
        Text(stringResource(R.string.libre3_start_scan_hint))
    }
}
