package app.aaps.plugins.sync.nsclientV3.remotecontrol

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.sync.SyncStrings
import kotlinx.coroutines.delay

/** How often the Active tab reads the notes again while the screen is open, as on dev. */
private const val REFRESH_INTERVAL_MS = 30_000L

class RemoteControlComposeContent(private val title: String) : ComposablePluginContent {

    @Composable
    override fun Render(
        setToolbarConfig: (ToolbarConfig) -> Unit,
        onNavigateBack: () -> Unit,
        onSettings: (() -> Unit)?
    ) {
        val viewModel: RemoteControlViewModel = metroViewModel()
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val unlocked = state.gate == RemoteControlGate.UNLOCKED

        LaunchedEffect(unlocked) {
            setToolbarConfig(
                ToolbarConfig(
                    title = title,
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.back))
                        }
                    },
                    actions = {
                        if (unlocked) {
                            IconButton(onClick = viewModel::lock) {
                                Icon(imageVector = Icons.Default.Lock, contentDescription = stringResource(SyncStrings.aimi_rc_lock))
                            }
                        }
                    }
                )
            )
        }
        LaunchedEffect(Unit) {
            viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
        }
        LaunchedEffect(Unit) {
            while (true) {
                viewModel.refreshActiveModes()
                delay(REFRESH_INTERVAL_MS)
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            RemoteControlScreenContent(
                state = state,
                onSetupPassword = viewModel::setupPassword,
                onLogin = viewModel::login,
                onPasswordShown = viewModel::passwordShown,
                onSavePin = viewModel::savePin,
                onSendMode = viewModel::requestSend,
                onConfirmStop = viewModel::confirmStop,
                onDismissStop = viewModel::dismissStop,
                onSendLlm = viewModel::sendLlm,
                onRefresh = viewModel::refreshActiveModes
            )
            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}
