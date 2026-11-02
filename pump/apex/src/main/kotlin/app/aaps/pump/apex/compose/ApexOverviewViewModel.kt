package app.aaps.pump.apex.compose

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventInitializationChanged
import app.aaps.core.interfaces.rx.events.EventQueueChanged
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.ScreenAction
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.PumpCommunicationStatus
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.core.ui.compose.pump.PumpOverviewUiState
import app.aaps.core.ui.compose.pump.tickerFlow
import app.aaps.pump.apex.ApexDriverStatus
import app.aaps.pump.apex.ApexPump
import app.aaps.pump.apex.R
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import app.aaps.core.interfaces.R as InterfacesR
import app.aaps.core.ui.R as CoreUiR

/**
 * The Apex pump screen: the same rows and buttons as the old `ApexFragment` of dev_OAPSAIMI, on the
 * shared pump overview. Registered like every pump view model, but it is only reachable through
 * `ApexPumpPlugin`, which is not in the app.
 */
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
@Stable
@Inject
class ApexOverviewViewModel(
    private val aapsLogger: AAPSLogger,
    private val rh: ResourceHelper,
    rxBus: RxBus,
    private val commandQueue: CommandQueue,
    private val dateUtil: DateUtil,
    private val pump: ApexPump,
    private val driverStatus: ApexDriverStatus,
    private val activePlugin: ActivePlugin,
    private val uel: UserEntryLogger,
    private val preferences: Preferences,
    private val config: Config
) : ViewModel() {

    private val communicationStatus = PumpCommunicationStatus(rxBus, commandQueue, rh, viewModelScope)

    private val rxTrigger = MutableStateFlow(0L)

    init {
        // UNDISPATCHED because RxBus has no replay, so a scheduled collector could miss an event sent
        // before it starts. The bodies only write a timestamp.
        rxBus.toFlow(EventApexPumpDataChanged::class)
            .collectResilient(viewModelScope, aapsLogger, LTag.PUMP, start = CoroutineStart.UNDISPATCHED) { rxTrigger.value = System.currentTimeMillis() }
        rxBus.toFlow(EventInitializationChanged::class)
            .collectResilient(viewModelScope, aapsLogger, LTag.PUMP, start = CoroutineStart.UNDISPATCHED) { rxTrigger.value = System.currentTimeMillis() }
        rxBus.toFlow(EventQueueChanged::class)
            .collectResilient(viewModelScope, aapsLogger, LTag.PUMP, start = CoroutineStart.UNDISPATCHED) { rxTrigger.value = System.currentTimeMillis() }
    }

    val uiState: StateFlow<PumpOverviewUiState> = combine(
        pump.statusFlow,
        pump.lastBolusFlow,
        rxTrigger,
        communicationStatus.refreshTrigger,
        tickerFlow(15_000L)
    ) { _, _, _, _, _ -> buildUiState() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), buildUiState())

    fun onRefreshClick() {
        viewModelScope.launch { commandQueue.readStatus(rh.gs(CoreUiR.string.clicked_connect_to_pump)) }
    }

    fun onCancelBolusClick() {
        uel.log(Action.CANCEL_BOLUS, Sources.Pump)
        commandQueue.cancelAllBoluses(null)
    }

    private fun buildUiState(): PumpOverviewUiState {
        val status = pump.status
        val activePump = activePlugin.activePump
        val unknown = rh.gs(InterfacesR.string.unknown)

        val connection = when {
            status == null            -> unknown
            activePump.isConnected()  -> rh.gs(R.string.overview_connection_status_connected)
            activePump.isConnecting() -> rh.gs(R.string.overview_connection_status_connecting)
            else                      -> rh.gs(R.string.overview_connection_status_disconnected)
        }
        val lastUpdate = when {
            status == null             -> unknown
            config.isEngineeringMode() -> dateUtil.timeStringWithSeconds(status.dateTime.millis)
            else                       -> dateUtil.timeString(status.dateTime.millis)
        }
        val pumpStatusLevel = when {
            status == null             -> StatusLevel.UNSPECIFIED
            status.alarms.isNotEmpty() -> StatusLevel.CRITICAL
            status.basal == null       -> StatusLevel.WARNING
            else                       -> StatusLevel.NORMAL
        }
        val action = driverStatus.message

        val rows = listOf(
            PumpInfoRow(rh.gs(R.string.connection_status), connection),
            PumpInfoRow(rh.gs(R.string.status_label), status?.getPumpStatus(rh) ?: unknown, level = pumpStatusLevel),
            PumpInfoRow(rh.gs(R.string.updated_at), lastUpdate),
            PumpInfoRow(rh.gs(CoreUiR.string.battery_label), status?.getBatteryLevel(rh) ?: unknown),
            PumpInfoRow(rh.gs(CoreUiR.string.reservoir_label), status?.getReservoirLevel(rh) ?: unknown),
            PumpInfoRow(rh.gs(CoreUiR.string.last_bolus_label), pump.lastBolus?.toShortLocalString(rh) ?: unknown),
            PumpInfoRow(rh.gs(CoreUiR.string.base_basal_rate_label), status?.getBasal(rh) ?: unknown),
            PumpInfoRow(rh.gs(CoreUiR.string.tempbasal_label), status?.getTBR(rh) ?: unknown),
            PumpInfoRow(rh.gs(CoreUiR.string.serial_number), pump.serialNumber, visible = !preferences.get(ApexBooleanKey.HideSerial)),
            PumpInfoRow(rh.gs(R.string.firmware_version), pump.firmwareVersion?.toLocalString(rh) ?: unknown),
            PumpInfoRow(rh.gs(R.string.action_label), action ?: "", visible = action != null)
        )

        return PumpOverviewUiState(
            statusBanner = communicationStatus.statusBanner(),
            queueStatus = communicationStatus.queueStatus(),
            infoRows = rows,
            primaryActions = listOf(
                ScreenAction(label = rh.gs(CoreUiR.string.refresh), icon = Icons.Filled.Refresh, onClick = ::onRefreshClick),
                ScreenAction(
                    label = rh.gs(CoreUiR.string.uel_cancel_bolus),
                    icon = Icons.Filled.Cancel,
                    visible = pump.inProgressBolus != null,
                    onClick = ::onCancelBolusClick
                )
            )
        )
    }
}
