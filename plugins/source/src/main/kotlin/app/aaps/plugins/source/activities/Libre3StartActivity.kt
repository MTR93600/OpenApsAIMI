package app.aaps.plugins.source.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.libre3.identity.Libre3SensorStore
import app.aaps.plugins.libre3.nfc.Libre3NfcFailure
import app.aaps.plugins.libre3.nfc.Libre3NfcReader
import app.aaps.plugins.libre3.nfc.Libre3NfcScanResult
import app.aaps.plugins.libre3.nfc.Libre3NfcSession
import app.aaps.plugins.source.Libre3BlePermissionHelper
import app.aaps.plugins.source.Libre3NativePlugin
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.Libre3UiLabels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Starting a sensor.
 *
 * There is nothing to type. The user holds the phone on the sensor, the NFC step reads it, stores
 * it, and only then may Bluetooth start. The reader runs only while this screen is in front of the
 * user, so a phone in a pocket never scans.
 */
@AndroidEntryPoint
class Libre3StartActivity : AppCompatActivity() {

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
                        onSensorScanned = {
                            // A different sensor counts its own minutes from zero, so the repeat
                            // guard has to start again. Without this every reading of the new
                            // sensor would be refused as "already seen".
                            plugin.onSensorChanged()
                            plugin.syncDriverFromPrefs()
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Libre3StartScreen(
    activity: AppCompatActivity,
    reader: Libre3NfcReader,
    onBack: () -> Unit,
    onSensorScanned: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                            onSensorScanned()
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

    Scaffold(
        modifier = modifier,
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.libre3_start_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.libre3_nav_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(
                    modifier = Modifier.padding(AapsSpacing.medium),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
                ) {
                    Text(stringResource(R.string.libre3_start_steps))
                    Text(stringResource(R.string.libre3_start_conflict_warning))
                }
            }

            if (!permissionsGranted) {
                Text(stringResource(R.string.libre3_permissions_needed))
                Button(
                    onClick = {
                        Libre3BlePermissionHelper.requestMissing(activity)
                        permissionsGranted = Libre3BlePermissionHelper.hasAll(activity)
                    }
                ) {
                    Text(stringResource(R.string.libre3_request_permissions))
                }
            }

            if (!nfcAvailable) Text(stringResource(R.string.libre3_start_nfc_off))

            val result = scanned
            val problem = failure
            when {
                result != null  -> {
                    Text(
                        stringResource(
                            R.string.libre3_start_scanned,
                            result.identity.serialNumber,
                            Libre3UiLabels.generationLabel(result.identity.generation),
                        )
                    )
                    Text(stringResource(R.string.libre3_start_done_note))
                }

                problem != null -> Text(Libre3UiLabels.nfcFailureLabel(problem))
                nfcAvailable    -> {
                    Text(stringResource(R.string.libre3_start_scan_hint))
                    Text(stringResource(R.string.libre3_start_scan_waiting))
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
