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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.libre3.Libre3CgmDrivers
import app.aaps.plugins.libre3.identity.Libre3SensorStore
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.Libre3UiLabels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

/** Shows which sensor is stored and what the session is doing. Read only. */
@AndroidEntryPoint
class Libre3StatusActivity : AppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    Libre3StatusScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Libre3StatusScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { Libre3SensorStore(context) }
    val driver = remember { Libre3CgmDrivers.default() }
    var identity by remember { mutableStateOf(store.loadIdentity()) }
    var phase by remember { mutableStateOf(driver.warmupState().phase) }
    var sessionUp by remember { mutableStateOf(driver.isSessionUp()) }
    var blockedReason by remember { mutableStateOf(Libre3CgmDrivers.realDriverBlockedReason()) }

    LaunchedEffect(Unit) {
        while (true) {
            identity = store.loadIdentity()
            phase = driver.warmupState().phase
            sessionUp = driver.isSessionUp()
            blockedReason = Libre3CgmDrivers.realDriverBlockedReason()
            delay(2_000L)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.libre3_status_title)) },
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
                    Text(
                        text = stringResource(R.string.libre3_status_heading),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val stored = identity
                    if (stored == null) {
                        Text(stringResource(R.string.libre3_status_no_sensor))
                    } else {
                        Text(stringResource(R.string.libre3_status_serial, stored.serialNumber))
                        Text(
                            stringResource(
                                R.string.libre3_status_family,
                                Libre3UiLabels.generationLabel(stored.generation),
                            )
                        )
                        Text(stringResource(R.string.libre3_status_address, stored.bleAddress))
                    }
                }
            }
            Text(stringResource(R.string.libre3_status_phase, Libre3UiLabels.phaseLabel(phase)))
            Text(
                stringResource(
                    R.string.libre3_status_session,
                    stringResource(
                        if (sessionUp) R.string.libre3_status_session_up else R.string.libre3_status_session_down
                    ),
                )
            )

            // Why the real driver is not in use, said plainly rather than left to be guessed.
            blockedReason?.let { Text(stringResource(R.string.libre3_status_driver_blocked, it)) }

            val stored = identity
            if (stored != null && blockedReason == null) {
                Button(onClick = { if (sessionUp) driver.disconnect() else driver.connect(stored.bleAddress) }) {
                    Text(
                        stringResource(
                            if (sessionUp) R.string.libre3_status_disconnect else R.string.libre3_status_connect
                        )
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun Libre3StatusScreenPreview() {
    MaterialTheme {
        Libre3StatusScreen(onBack = {})
    }
}
