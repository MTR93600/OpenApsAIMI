package app.aaps.plugins.source.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.libre3.Libre3CgmDriver
import app.aaps.plugins.libre3.Libre3CgmDrivers
import app.aaps.plugins.libre3.identity.Libre3SensorStore
import app.aaps.plugins.source.Libre3Ingest
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.CgmCard
import app.aaps.plugins.source.compose.CgmCardHeader
import app.aaps.plugins.source.compose.CgmCardTone
import app.aaps.plugins.source.compose.CgmKeyValueRow
import app.aaps.plugins.source.compose.CgmLazyColumn
import app.aaps.plugins.source.compose.CgmScaffold
import app.aaps.plugins.source.compose.CgmStateChip
import app.aaps.plugins.source.compose.Libre3UiLabels
import app.aaps.plugins.source.compose.toUiState
import app.aaps.plugins.source.logs.DriverLogFilter
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * Shows which sensor is stored and what the session is doing.
 *
 * One card for the sensor, one for the actions. The screen scrolls, which the previous plain
 * `Column` did not: with a stored sensor and a blocked driver there were enough stacked buttons to
 * push "Forget this sensor" — the only escape from a sensor that can never connect — off the bottom
 * of a short screen.
 */
@AndroidEntryPoint
class Libre3StatusActivity : AppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    Libre3StatusScreen(
                        onBack = { finish() },
                        onOpenLog = {
                            startActivity(
                                Intent(this, CgmDriverLogActivity::class.java)
                                    .putExtra(CgmDriverLogActivity.EXTRA_FILTER, DriverLogFilter.LIBRE3.name)
                            )
                        },
                        onOpenStart = {
                            startActivity(Intent(this, Libre3StartActivity::class.java))
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun Libre3StatusScreen(
    onBack: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val store = remember { Libre3SensorStore(context) }
    val driver = remember { Libre3CgmDrivers.default() }
    var identity by remember { mutableStateOf(store.loadIdentity()) }
    var phase by remember { mutableStateOf(driver.warmupState().phase) }
    var sessionUp by remember { mutableStateOf(driver.isSessionUp()) }
    var blockedReason by remember { mutableStateOf(Libre3CgmDrivers.realDriverBlockedReason()) }
    var askingToForget by remember { mutableStateOf(false) }
    var forgotten by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            identity = store.loadIdentity()
            phase = driver.warmupState().phase
            sessionUp = driver.isSessionUp()
            blockedReason = Libre3CgmDrivers.realDriverBlockedReason()
            delay(2_000L)
        }
    }

    CgmScaffold(
        title = stringResource(R.string.libre3_status_title),
        onNavigate = onBack,
        modifier = modifier,
        actions = {
            IconButton(onClick = onOpenLog) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.cgm_driver_log_open),
                )
            }
        },
    ) {
        CgmLazyColumn {
            item(key = "sensor") {
                val stored = identity
                CgmCard(accent = stored != null) {
                    CgmCardHeader(stringResource(R.string.libre3_status_heading)) {
                        CgmStateChip(state = phase.toUiState(), label = Libre3UiLabels.phaseLabel(phase))
                    }
                    if (stored == null) {
                        // An empty state that offers the way in rather than only reporting absence.
                        Text(
                            text = stringResource(R.string.libre3_status_no_sensor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onOpenStart) {
                            Text(stringResource(R.string.libre3_status_open_start))
                        }
                    } else {
                        CgmKeyValueRow(
                            label = stringResource(R.string.libre3_label_serial),
                            value = stored.serialNumber,
                        )
                        CgmKeyValueRow(
                            label = stringResource(R.string.libre3_label_family),
                            value = Libre3UiLabels.generationLabel(stored.generation),
                        )
                        CgmKeyValueRow(
                            label = stringResource(R.string.cgm_sensor_address),
                            value = stored.bleAddress,
                        )
                        CgmKeyValueRow(
                            label = stringResource(R.string.cgm_session_label),
                            value = stringResource(
                                if (sessionUp) R.string.libre3_status_session_up else R.string.libre3_status_session_down,
                            ),
                        )
                        // The link toggle sits with the session line it flips, instead of floating
                        // below as one more button among several.
                        if (blockedReason == null) {
                            Button(
                                onClick = {
                                    if (sessionUp) driver.disconnect() else driver.connect(stored.bleAddress)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    stringResource(
                                        if (sessionUp) R.string.libre3_status_disconnect else R.string.libre3_status_connect
                                    )
                                )
                            }
                        }
                        // The way out of a sensor that is stored but can never be reached. Without it
                        // the only escape would be clearing the whole app, because a stored pairing
                        // key sends every later attempt down the short reconnect path, and a fresh
                        // scan of the same sensor keeps that key. Destructive, so it stays a quiet
                        // text button rather than competing with the connect action above.
                        TextButton(
                            onClick = { askingToForget = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.libre3_forget_sensor))
                        }
                    }
                }
            }

            // Why the real driver is not in use, said plainly rather than left to be guessed.
            blockedReason?.let { reason ->
                item(key = "blocked") {
                    CgmCard(tone = CgmCardTone.Warning) {
                        CgmCardHeader(stringResource(R.string.libre3_status_driver_blocked_heading))
                        Text(
                            text = stringResource(R.string.libre3_status_driver_blocked, reason),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (forgotten) {
                item(key = "forgotten") {
                    Text(
                        text = stringResource(R.string.libre3_forget_sensor_done),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item(key = "actions") {
                CgmCard {
                    CgmCardHeader(stringResource(R.string.cgm_actions_heading))
                    OutlinedButton(onClick = onOpenStart, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.libre3_status_open_start))
                    }
                    OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cgm_driver_log_open))
                    }
                }
            }
        }
    }

    if (askingToForget) {
        AlertDialog(
            onDismissRequest = { askingToForget = false },
            title = { Text(stringResource(R.string.libre3_forget_sensor_title)) },
            text = { Text(stringResource(R.string.libre3_forget_sensor_explain)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        askingToForget = false
                        forgetSensor(driver, store)
                        identity = null
                        sessionUp = false
                        forgotten = true
                    }
                ) {
                    Text(stringResource(R.string.libre3_forget_sensor_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { askingToForget = false }) {
                    Text(stringResource(R.string.libre3_forget_sensor_cancel))
                }
            },
        )
    }
}

/**
 * Throws away everything this phone knows about the sensor it is holding.
 *
 * The three steps have to go together. Dropping the link first, so no session keeps running on
 * material that is about to disappear. Then the store, which is what makes the next attempt start
 * a fresh pairing instead of reusing a key that does not work. Then the ingest mark, because the
 * next sensor counts its own minutes from zero and a leftover mark would refuse every reading of
 * it as already seen.
 *
 * The sensor itself is left alone. No command is sent to it, and it keeps running.
 */
private fun forgetSensor(driver: Libre3CgmDriver, store: Libre3SensorStore) {
    driver.disconnect()
    store.clear()
    Libre3Ingest.reset()
}

@Preview
@Composable
private fun Libre3StatusScreenPreview() {
    MaterialTheme {
        Libre3StatusScreen(onBack = {}, onOpenLog = {}, onOpenStart = {})
    }
}
