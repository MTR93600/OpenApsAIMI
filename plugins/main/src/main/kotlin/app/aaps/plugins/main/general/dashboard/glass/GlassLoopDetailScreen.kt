package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.RM
import app.aaps.core.data.ue.Action
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.ui.compose.runningMode.RunningModeManagementViewModel
import kotlinx.coroutines.delay
import app.aaps.core.ui.R as CoreUiR
import app.aaps.ui.R as UiR

/**
 * Glass-branded re-skin of [app.aaps.ui.compose.runningMode.RunningModeScreen]. Binds to the SAME
 * [RunningModeManagementViewModel] instance the shared screen uses (Activity-scoped, not route-scoped —
 * the caller must pass in the existing instance rather than obtaining a fresh one via `hiltViewModel()`).
 *
 * The shared screen's own sections ([app.aaps.ui.compose.runningMode.RunningModeScreen]'s
 * `LoopControlSection`/`SuspendSection`/`PumpDisconnectSection`) are `private` to that file, so they cannot
 * be reused here — this screen rebuilds the equivalent UI from scratch, mirroring the same section
 * structure and button set. Styling follows the same choice already made (and reviewed) for the Target
 * screen, the other Activity-scoped-ViewModel Glass screen in this plan: `AapsTheme`/
 * `MaterialTheme.colorScheme` rather than hand-rolled GlycoCalm hex tokens. That keeps this screen
 * automatically theme-aware without threading an `isDark` parameter (this screen's own signature omits
 * one, same as Target's), and keeps the two Activity-scoped-ViewModel Glass screens styled consistently
 * with each other.
 */
@Composable
fun GlassLoopDetailScreen(
    viewModel: RunningModeManagementViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Mirrors the shared RunningModeScreen's own polling: the running mode can change from outside this
    // screen (e.g. automation), so refresh on open and then every 15s while the screen is visible.
    LaunchedEffect(Unit) {
        viewModel.loadState()
        while (true) {
            delay(15_000L)
            viewModel.loadState()
        }
    }

    AapsTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(state.currentModeText.ifEmpty { stringResource(CoreUiR.string.running_mode) }) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiR.string.back))
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!state.reasons.isNullOrEmpty()) {
                    LoopSectionCard(title = stringResource(CoreUiR.string.constraints)) {
                        Text(
                            text = state.reasons!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Gate on the actual profile-validity flag, not on an empty allowedNextModes (which is also
                // empty when the pump force-suspends — that case is already explained by the reasons card
                // above and must not show "no profile set"). Mirrors the shared screen's own gate exactly.
                if (!state.isLoading && !state.profileSet) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = stringResource(CoreUiR.string.no_profile_set),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                val showLoopSection = state.allowedNextModes.any {
                    it in listOf(RM.Mode.DISABLED_LOOP, RM.Mode.OPEN_LOOP, RM.Mode.CLOSED_LOOP, RM.Mode.CLOSED_LOOP_LGS)
                }
                if (showLoopSection) {
                    LoopControlSection(
                        allowedModes = state.allowedNextModes,
                        onAction = { targetMode, action, durationMinutes ->
                            viewModel.executeAction(targetMode, action, durationMinutes)
                            onNavigateBack()
                        }
                    )
                }

                val showSuspendSection = state.allowedNextModes.contains(RM.Mode.SUSPENDED_BY_USER) ||
                    (state.allowedNextModes.contains(RM.Mode.RESUME) && state.currentMode == RM.Mode.SUSPENDED_BY_USER)
                if (showSuspendSection) {
                    SuspendSection(
                        currentMode = state.currentMode,
                        allowedModes = state.allowedNextModes,
                        onAction = { targetMode, action, durationMinutes ->
                            viewModel.executeAction(targetMode, action, durationMinutes)
                            onNavigateBack()
                        }
                    )
                }

                // Available on follower (AAPSCLIENT) too — the mode write propagates via NS sync to the
                // main phone whose reconciler enacts it on the pump. Mirrors the shared screen's own gate.
                val showPumpSection = state.allowedNextModes.contains(RM.Mode.DISCONNECTED_PUMP) ||
                    (state.allowedNextModes.contains(RM.Mode.RESUME) && state.currentMode == RM.Mode.DISCONNECTED_PUMP)
                if (showPumpSection) {
                    PumpDisconnectSection(
                        currentMode = state.currentMode,
                        allowedModes = state.allowedNextModes,
                        tempDurationStep15mAllowed = state.tempDurationStep15mAllowed,
                        tempDurationStep30mAllowed = state.tempDurationStep30mAllowed,
                        onAction = { targetMode, action, durationMinutes ->
                            viewModel.executeAction(targetMode, action, durationMinutes)
                            onNavigateBack()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoopSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun LoopControlSection(
    allowedModes: List<RM.Mode>,
    onAction: (RM.Mode, Action, Int) -> Unit
) {
    LoopSectionCard(title = stringResource(CoreUiR.string.running_mode)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (allowedModes.contains(RM.Mode.CLOSED_LOOP)) {
                LoopModeButton(
                    text = stringResource(CoreUiR.string.closedloop),
                    icon = Icons.Filled.Bolt,
                    onClick = { onAction(RM.Mode.CLOSED_LOOP, Action.CLOSED_LOOP_MODE, 0) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (allowedModes.contains(RM.Mode.CLOSED_LOOP_LGS)) {
                LoopModeButton(
                    text = stringResource(CoreUiR.string.lowglucosesuspend),
                    icon = Icons.Filled.Shield,
                    onClick = { onAction(RM.Mode.CLOSED_LOOP_LGS, Action.LGS_LOOP_MODE, 0) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (allowedModes.contains(RM.Mode.OPEN_LOOP)) {
                LoopModeButton(
                    text = stringResource(CoreUiR.string.openloop),
                    icon = Icons.Filled.PlayArrow,
                    onClick = { onAction(RM.Mode.OPEN_LOOP, Action.OPEN_LOOP_MODE, 0) },
                    modifier = Modifier.weight(1f)
                )
            }
            if (allowedModes.contains(RM.Mode.DISABLED_LOOP)) {
                LoopModeButton(
                    text = stringResource(CoreUiR.string.disableloop),
                    icon = Icons.Filled.PowerSettingsNew,
                    onClick = { onAction(RM.Mode.DISABLED_LOOP, Action.LOOP_DISABLED, 0) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SuspendSection(
    currentMode: RM.Mode,
    allowedModes: List<RM.Mode>,
    onAction: (RM.Mode, Action, Int) -> Unit
) {
    val isSuspended = currentMode == RM.Mode.SUSPENDED_BY_USER
    val title = if (isSuspended) stringResource(CoreUiR.string.resumeloop) else stringResource(CoreUiR.string.suspendloop)

    LoopSectionCard(title = title) {
        if (isSuspended && allowedModes.contains(RM.Mode.RESUME)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LoopModeButton(
                    text = stringResource(UiR.string.resume),
                    icon = Icons.Filled.PlayArrow,
                    onClick = { onAction(RM.Mode.RESUME, Action.RESUME, 0) }
                )
            }
        } else if (allowedModes.contains(RM.Mode.SUSPENDED_BY_USER)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LoopModeButton(
                    text = stringResource(UiR.string.duration1h),
                    icon = Icons.Filled.Pause,
                    onClick = { onAction(RM.Mode.SUSPENDED_BY_USER, Action.SUSPEND, 60) },
                    modifier = Modifier.weight(1f)
                )
                LoopModeButton(
                    text = stringResource(UiR.string.duration2h),
                    icon = Icons.Filled.Pause,
                    onClick = { onAction(RM.Mode.SUSPENDED_BY_USER, Action.SUSPEND, 120) },
                    modifier = Modifier.weight(1f)
                )
                LoopModeButton(
                    text = stringResource(UiR.string.duration3h),
                    icon = Icons.Filled.Pause,
                    onClick = { onAction(RM.Mode.SUSPENDED_BY_USER, Action.SUSPEND, 180) },
                    modifier = Modifier.weight(1f)
                )
                LoopModeButton(
                    text = stringResource(UiR.string.duration10h),
                    icon = Icons.Filled.Pause,
                    onClick = { onAction(RM.Mode.SUSPENDED_BY_USER, Action.SUSPEND, 600) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun PumpDisconnectSection(
    currentMode: RM.Mode,
    allowedModes: List<RM.Mode>,
    tempDurationStep15mAllowed: Boolean,
    tempDurationStep30mAllowed: Boolean,
    onAction: (RM.Mode, Action, Int) -> Unit
) {
    val isDisconnected = currentMode == RM.Mode.DISCONNECTED_PUMP
    val title = if (isDisconnected) stringResource(UiR.string.reconnect) else stringResource(UiR.string.disconnectpump)

    LoopSectionCard(title = title) {
        if (isDisconnected && allowedModes.contains(RM.Mode.RESUME)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                LoopModeButton(
                    text = stringResource(UiR.string.reconnect),
                    icon = Icons.Filled.CloudOff,
                    onClick = { onAction(RM.Mode.RESUME, Action.RECONNECT, 0) }
                )
            }
        } else if (allowedModes.contains(RM.Mode.DISCONNECTED_PUMP)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (tempDurationStep15mAllowed) {
                    LoopModeButton(
                        text = stringResource(UiR.string.duration15m),
                        icon = Icons.Filled.BluetoothDisabled,
                        onClick = { onAction(RM.Mode.DISCONNECTED_PUMP, Action.DISCONNECT, 15) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (tempDurationStep30mAllowed) {
                    LoopModeButton(
                        text = stringResource(UiR.string.duration30m),
                        icon = Icons.Filled.BluetoothDisabled,
                        onClick = { onAction(RM.Mode.DISCONNECTED_PUMP, Action.DISCONNECT, 30) },
                        modifier = Modifier.weight(1f)
                    )
                }
                LoopModeButton(
                    text = stringResource(UiR.string.duration1h),
                    icon = Icons.Filled.BluetoothDisabled,
                    onClick = { onAction(RM.Mode.DISCONNECTED_PUMP, Action.DISCONNECT, 60) },
                    modifier = Modifier.weight(1f)
                )
                LoopModeButton(
                    text = stringResource(UiR.string.duration2h),
                    icon = Icons.Filled.BluetoothDisabled,
                    onClick = { onAction(RM.Mode.DISCONNECTED_PUMP, Action.DISCONNECT, 120) },
                    modifier = Modifier.weight(1f)
                )
                LoopModeButton(
                    text = stringResource(UiR.string.duration3h),
                    icon = Icons.Filled.BluetoothDisabled,
                    onClick = { onAction(RM.Mode.DISCONNECTED_PUMP, Action.DISCONNECT, 180) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun LoopModeButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(80.dp),
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
        }
    }
}
