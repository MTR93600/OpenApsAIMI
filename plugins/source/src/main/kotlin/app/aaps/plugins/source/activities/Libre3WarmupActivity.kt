package app.aaps.plugins.source.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.plugins.libre3.Libre3CgmDrivers
import app.aaps.plugins.source.R
import app.aaps.plugins.source.compose.Libre3UiLabels
import app.aaps.plugins.source.compose.Libre3WarmupCountdown
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * The warm-up countdown.
 *
 * The number only ever comes from the sensor. When the sensor has not said how long is left, a
 * dash is shown rather than a guess, because a made up countdown that runs out would tell the user
 * that glucose is coming when it is not.
 */
@AndroidEntryPoint
class Libre3WarmupActivity : AppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    Libre3WarmupScreen(onBack = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Libre3WarmupScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val driver = remember { Libre3CgmDrivers.default() }
    var state by remember { mutableStateOf(driver.warmupState()) }
    var remainingMs by remember { mutableStateOf<Long?>(null) }
    var finished by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            state = driver.warmupState()
            remainingMs = Libre3WarmupCountdown.remainingMs(state, now)
            finished = Libre3WarmupCountdown.isFinished(state, now)
            delay(1_000L)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(R.string.libre3_warmup_title)) },
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
            Text(
                text = stringResource(R.string.libre3_warmup_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.libre3_status_phase, Libre3UiLabels.phaseLabel(state.phase)))
            Text(
                stringResource(
                    R.string.libre3_warmup_countdown,
                    remainingMs?.let { Libre3WarmupCountdown.format(it) }
                        ?: stringResource(R.string.libre3_warmup_countdown_unknown),
                )
            )
            if (finished) {
                Text(stringResource(R.string.libre3_warmup_done))
            } else {
                Text(stringResource(R.string.libre3_warmup_note))
            }
        }
    }
}

@Preview
@Composable
private fun Libre3WarmupScreenPreview() {
    MaterialTheme {
        Libre3WarmupScreen(onBack = {})
    }
}
