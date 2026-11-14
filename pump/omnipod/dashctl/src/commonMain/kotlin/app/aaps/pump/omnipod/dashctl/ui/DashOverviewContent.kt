package app.aaps.pump.omnipod.dashctl.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.ScreenAction
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.pump.PumpCommunicationStatus
import app.aaps.core.ui.compose.pump.PumpOverviewScreen
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.core.ui.compose.pump.StatusBanner
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.history.DashHistoryRecord
import app.aaps.pump.omnipod.dashctl.queue.command.CommandAcknowledgeAlert
import app.aaps.pump.omnipod.dashctl.queue.command.CommandHandleTimeChange
import app.aaps.pump.omnipod.dashctl.queue.command.CommandPlayTestBeep
import app.aaps.pump.omnipod.dashctl.queue.command.CommandResumeDelivery
import app.aaps.pump.omnipod.dashctl.queue.command.CommandSilenceAlerts
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Which wizard the pod screen opens. */
sealed interface DashWizardRequest {

    data class Activation(val type: DashActivationType) : DashWizardRequest
    data object Deactivation : DashWizardRequest
}

/**
 * The Omnipod Dash pump screen, the counterpart of Android `OmnipodDashComposeContent` with
 * `DashOverviewViewModel` and `OmnipodComposeHost`: the pod rows ([dashOverviewRows]), the buttons
 * ([dashOverviewButtons]), the communication banner and queue status of the shared
 * [PumpCommunicationStatus], the discard confirmation and the error window of a failed command.
 * "Activate Pod" and "Deactivate Pod" open [wizard] in place of the screen.
 *
 * The pod state is plain fields, so the screen reads it again every second, as the iOS Medtrum screen.
 * While no command runs, [caution] is shown in the banner: the driver is new on the iPhone.
 */
class DashOverviewContent(
    private val stateManager: DashPodStateManager,
    private val commandQueue: CommandQueue,
    private val rxBus: RxBus,
    private val rh: TextResolver,
    private val format: DashOverviewFormat,
    private val caution: TextRef,
    /** The toolbar title of the screen (the plugin name). */
    private val title: String,
    private val now: () -> Long,
    /**
     * The wizard for a request; it calls its `onClose` when done. It gets the toolbar of this screen,
     * as Android `OmnipodWizardScreen`, so the back arrow is removed while the wizard runs and the
     * person cannot leave in the middle of a part.
     */
    private val wizard: @Composable (request: DashWizardRequest, setToolbarConfig: (ToolbarConfig) -> Unit, onClose: () -> Unit) -> Unit,
    /**
     * Android overview "Discard Pod" after the confirmation: the pod state is cleared, nothing is sent.
     * It must not stop when the screen closes (the plugin runs it on a process scope); when it is
     * refused it throws, and the reason is shown.
     */
    private val discardPod: suspend () -> Unit,
    /** The pod history of the last 5 days, newest first, for "Pod History" (Android `DashPodHistoryViewModel`). */
    private val history: () -> List<DashHistoryRecord> = { emptyList() },
) : ComposablePluginContent {

    @Composable
    override fun Render(setToolbarConfig: (ToolbarConfig) -> Unit, onNavigateBack: () -> Unit, onSettings: (() -> Unit)?) {
        var request by remember { mutableStateOf<DashWizardRequest?>(null) }
        var showHistory by remember { mutableStateOf(false) }
        // Android `OmnipodComposeHost`: the screen sets its toolbar each time no wizard shows. The
        // wizard takes the toolbar while it runs and clears it when it closes, so it must be set again.
        val showingWizard = request != null
        val historyTitle = stringResource(DashStrings.dash_button_pod_history)
        LaunchedEffect(showingWizard, showHistory) {
            if (showHistory) {
                // Android: the history screen has its own title and a back arrow to the pod screen.
                setToolbarConfig(
                    ToolbarConfig(
                        title = historyTitle,
                        navigationIcon = {
                            IconButton(onClick = { showHistory = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.back))
                            }
                        },
                        actions = {}
                    )
                )
            } else if (!showingWizard) {
                setToolbarConfig(
                    ToolbarConfig(
                        title = title,
                        navigationIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.back))
                            }
                        },
                        actions = {
                            onSettings?.let { open ->
                                IconButton(onClick = open) {
                                    Icon(Icons.Filled.Settings, contentDescription = stringResource(CoreUiStrings.settings))
                                }
                            }
                        }
                    )
                )
            }
        }
        request?.let { open ->
            wizard(open, setToolbarConfig) { request = null }
            return
        }
        if (showHistory) {
            val records = remember { history() }
            DashPodHistoryScreen(records, rh, format)
            return
        }
        val scope = rememberCoroutineScope()
        val communication = remember(scope) { PumpCommunicationStatus(rxBus, commandQueue, rh, scope) }
        val banner by communication.statusBannerFlow.collectAsStateWithLifecycle()
        val queueStatus by communication.queueStatusFlow.collectAsStateWithLifecycle()
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                tick++
            }
        }
        var confirmDiscard by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val cautionText = stringResource(caution)

        fun runCustomCommand(command: CustomCommand, failure: TextRef) {
            scope.launch {
                val result = commandQueue.customCommand(command)
                if (!result.success) error = rh.gs(DashStrings.dash_error_with_reason, rh.gs(failure), result.comment)
            }
        }

        val actions = remember(tick, queueStatus) {
            val queueEmpty = commandQueue.size() == 0 && commandQueue.performing() == null
            dashOverviewButtons(stateManager, queueEmpty, now()) { commandQueue.isCustomCommandInQueue(it) }.map { button ->
                button to ScreenAction(
                    label = rh.gs(button.action.label),
                    enabled = button.enabled,
                    visible = button.visible,
                    onClick = {
                        when (button.action) {
                            DashOverviewAction.REFRESH         -> scope.launch { commandQueue.readStatus(rh.gs(CoreUiStrings.refresh)) }
                            DashOverviewAction.SILENCE_ALERTS  -> runCustomCommand(CommandSilenceAlerts(), DashStrings.dash_error_failed_to_silence_alerts)
                            DashOverviewAction.RESUME_DELIVERY -> runCustomCommand(CommandResumeDelivery(), DashStrings.dash_error_failed_to_resume_delivery)
                            DashOverviewAction.SET_TIME        -> runCustomCommand(CommandHandleTimeChange(true), DashStrings.dash_error_failed_to_set_time)
                            DashOverviewAction.POD_HISTORY     -> showHistory = true
                            DashOverviewAction.ACTIVATE_POD    -> request = DashWizardRequest.Activation(dashActivationType(stateManager.activationProgress))
                            DashOverviewAction.DEACTIVATE_POD  -> request = DashWizardRequest.Deactivation
                            DashOverviewAction.PLAY_TEST_BEEP  -> runCustomCommand(CommandPlayTestBeep(), DashStrings.dash_error_failed_to_play_test_beep)
                            DashOverviewAction.DISCARD_POD     -> confirmDiscard = true
                        }
                    }
                )
            }
        }
        val alertButtons = remember(tick, queueStatus) {
            val queueEmpty = commandQueue.size() == 0 && commandQueue.performing() == null
            dashAlertSilenceButtons(stateManager, queueEmpty) { commandQueue.isCustomCommandInQueue(it) }.map { button ->
                ScreenAction(
                    label = button.label(rh),
                    enabled = button.enabled,
                    visible = true,
                    onClick = { runCustomCommand(CommandAcknowledgeAlert(button.alert), DashStrings.dash_error_failed_to_silence_alerts) }
                )
            }
        }
        val rows = remember(tick) { dashOverviewRows(stateManager, rh, format, now()) }

        if (confirmDiscard) {
            OkCancelDialog(
                title = stringResource(DashStrings.dash_button_discard_pod),
                message = stringResource(DashStrings.dash_discard_pod_confirmation),
                onConfirm = {
                    confirmDiscard = false
                    scope.launch {
                        try {
                            discardPod()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.message ?: rh.gs(DashStrings.dash_wizard_unexpected_error)
                        }
                    }
                },
                onDismiss = { confirmDiscard = false }
            )
        }
        error?.let { message ->
            OkDialog(title = stringResource(CoreUiStrings.warning), message = message, onDismiss = { error = null })
        }

        PumpOverviewScreen(
            state = PumpOverviewUiState(
                statusBanner = banner ?: StatusBanner(text = cautionText, level = StatusLevel.WARNING),
                infoRows = rows,
                primaryActions = actions.filter { it.first.action.isPrimary }.map { it.second } + alertButtons,
                managementActions = actions.filterNot { it.first.action.isPrimary }.map { it.second },
                queueStatus = queueStatus
            )
        )
    }
}
