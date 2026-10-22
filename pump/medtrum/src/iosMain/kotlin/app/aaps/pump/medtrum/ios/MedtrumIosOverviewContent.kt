package app.aaps.pump.medtrum.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ComposablePluginContent
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.pump.ActionCategory
import app.aaps.core.ui.compose.pump.PumpAction
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.core.ui.compose.pump.PumpOverviewScreen
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.pump.medtrum.MedtrumStrings
import app.aaps.pump.medtrum.comm.MedtrumPumpSyncHistory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Medtrum pump screen on iPhone: the state the driver holds, and the three actions a person
 * needs during a therapy - read the pump again, clear an alarm (which also resumes a pump stopped by
 * its hourly or daily maximum), and change the patch.
 *
 * Built from the shared pump overview, like the Android Medtrum screen. It shows fewer rows than
 * Android; what is there is read from the same session state the loop uses.
 */
internal class MedtrumIosOverviewContent(
    private val host: IosMedtrumDriverHost,
    private val commandQueue: CommandQueue,
    private val rh: TextResolver,
    private val dateUtil: DateUtil,
    /** The patch wizard, the same one the settings entry opens. */
    private val patchWizard: @Composable (onClose: () -> Unit) -> Unit
) : ComposablePluginContent {

    @Composable
    override fun Render(setToolbarConfig: (ToolbarConfig) -> Unit, onNavigateBack: () -> Unit, onSettings: (() -> Unit)?) {
        var showWizard by remember { mutableStateOf(false) }
        if (showWizard) {
            patchWizard { showWizard = false }
            return
        }
        val scope = rememberCoroutineScope()
        // The session state is plain fields, so the screen reads them again every second.
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                tick++
            }
        }
        val state = host.state
        val rows = remember(tick) { infoRows() }
        val refreshReason = rh.gs(CoreUiStrings.refresh)
        PumpOverviewScreen(
            state = PumpOverviewUiState(
                infoRows = rows,
                primaryActions = listOf(
                    PumpAction(
                        label = refreshReason,
                        category = ActionCategory.PRIMARY,
                        onClick = { scope.launch { commandQueue.readStatus(refreshReason) } }
                    ),
                    PumpAction(
                        label = rh.gs(MedtrumStrings.reset_alarms_label),
                        category = ActionCategory.PRIMARY,
                        enabled = state.activeAlarms.isNotEmpty() || state.pumpState.isSuspendedByPump(),
                        onClick = { scope.launch { commandQueue.clearAlarms() } }
                    )
                ),
                managementActions = listOf(
                    PumpAction(
                        label = rh.gs(MedtrumStrings.change_patch_label),
                        category = ActionCategory.MANAGEMENT,
                        onClick = { showWizard = true }
                    )
                )
            )
        )
    }

    private fun infoRows(): List<PumpInfoRow> {
        val state = host.state
        return buildList {
            add(PumpInfoRow(label = rh.gs(MedtrumStrings.pump_state_label), value = rh.gs(state.pumpState.label)))
            add(PumpInfoRow(label = rh.gs(CoreUiStrings.reservoir_label), value = rh.gs(InterfacesStrings.format_insulin_units, state.reservoir)))
            if (state.lastTimeReceivedFromPump > 0L)
                add(PumpInfoRow(label = rh.gs(CoreUiStrings.last_connection_label), value = dateUtil.dateAndTimeString(state.lastTimeReceivedFromPump)))
            val alarms = state.activeAlarms.joinToString("\n") { MedtrumPumpSyncHistory.alarmText(it) }
            if (alarms.isNotEmpty())
                add(PumpInfoRow(label = rh.gs(MedtrumStrings.active_alarms_label), value = alarms, level = StatusLevel.WARNING))
            add(PumpInfoRow(label = rh.gs(MedtrumStrings.patch_no_label), value = state.patchId.toString()))
            if (state.patchStartTime != 0L)
                add(PumpInfoRow(label = rh.gs(MedtrumStrings.patch_activation_time_label), value = dateUtil.dateAndTimeString(state.patchStartTime)))
        }
    }
}
