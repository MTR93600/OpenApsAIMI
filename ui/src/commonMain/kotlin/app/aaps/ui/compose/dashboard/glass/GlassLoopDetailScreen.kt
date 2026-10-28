package app.aaps.ui.compose.dashboard.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.glass.GlassCard
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.GlassScreenBackground
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.compose.runningMode.LoopControlSection
import app.aaps.ui.compose.runningMode.PendingRunningModeAction
import app.aaps.ui.compose.runningMode.PumpDisconnectSection
import app.aaps.ui.compose.runningMode.RunningModeManagementUiState
import app.aaps.ui.compose.runningMode.RunningModeManagementViewModel
import app.aaps.ui.compose.runningMode.RunningModeSectionContainer
import app.aaps.ui.compose.runningMode.SectionCard
import app.aaps.ui.compose.runningMode.SuspendSection
import app.aaps.ui.compose.runningMode.executeAction
import app.aaps.ui.compose.runningMode.showLoopSection
import app.aaps.ui.compose.runningMode.showPumpSection
import app.aaps.ui.compose.runningMode.showSuspendSection
import kotlinx.coroutines.delay

/** How often the screen reloads the running mode, which can change from outside (automation), as on the normal screen. */
private const val RELOAD_INTERVAL_MS = 15_000L

/**
 * The Glass loop screen: loop mode, suspend and pump disconnect. Port of `GlassLoopDetailScreen` on
 * `dev_OAPSAIMI`.
 *
 * Only the cards are Glass. The sections and the action of every button are the ones of
 * [app.aaps.ui.compose.runningMode.RunningModeScreen], with the same activity scoped
 * [RunningModeManagementViewModel] (the caller passes that instance in). A tap calls
 * [RunningModeManagementViewModel.executeAction], which asks the master to prepare the change and shows
 * its confirmation dialog at app level; nothing changes before "confirm" there.
 */
@Composable
fun GlassLoopDetailScreen(
    viewModel: RunningModeManagementViewModel,
    onNavigateBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        // Reload on open: the view model is activity scoped, so its state may be old.
        viewModel.loadState()
        while (true) {
            delay(RELOAD_INTERVAL_MS)
            viewModel.loadState()
        }
    }

    GlassLoopDetailContent(
        state = state,
        isDark = isGlassDarkMode(),
        onAction = { action ->
            // The confirmation is an app level dialog, so the screen closes straight away, as the normal one does.
            executeAction(viewModel, action)
            onNavigateBack()
        },
        onNavigateBack = onNavigateBack
    )
}

/** The stateless body of [GlassLoopDetailScreen]. */
@Composable
internal fun GlassLoopDetailContent(
    state: RunningModeManagementUiState,
    isDark: Boolean,
    onAction: (PendingRunningModeAction) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val glassContainer: RunningModeSectionContainer = { content -> GlassCard(isDark = isDark) { content() } }
    GlassScreenBackground(isDark = isDark) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)) {
                GlassDetailHeader(
                    title = state.currentModeText.ifEmpty { stringResource(CoreUiStrings.running_mode) },
                    isDark = isDark,
                    onBack = onNavigateBack
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.large)
            ) {
                val reasons = state.reasons
                if (!reasons.isNullOrEmpty()) {
                    SectionCard(title = stringResource(CoreUiStrings.constraints), container = glassContainer) {
                        Text(text = reasons, style = MaterialTheme.typography.bodySmall, color = GlassColors.textMuted(isDark))
                    }
                }

                // Gate on the profile flag, not on an empty mode list: that is also empty when the pump
                // suspends by itself, which the reasons card already explains.
                if (!state.isLoading && !state.profileSet) {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            text = stringResource(CoreUiStrings.no_profile_set),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(AapsSpacing.large)
                        )
                    }
                }

                if (state.showLoopSection) {
                    LoopControlSection(allowedModes = state.allowedNextModes, onAction = onAction, container = glassContainer)
                }
                if (state.showSuspendSection) {
                    SuspendSection(
                        currentMode = state.currentMode,
                        allowedModes = state.allowedNextModes,
                        onAction = onAction,
                        container = glassContainer
                    )
                }
                // Also on a client: the mode is synced to the master, which applies it to the pump.
                if (state.showPumpSection) {
                    PumpDisconnectSection(
                        currentMode = state.currentMode,
                        allowedModes = state.allowedNextModes,
                        tempDurationStep15mAllowed = state.tempDurationStep15mAllowed,
                        tempDurationStep30mAllowed = state.tempDurationStep30mAllowed,
                        onAction = onAction,
                        container = glassContainer
                    )
                }
            }
        }
    }
}
