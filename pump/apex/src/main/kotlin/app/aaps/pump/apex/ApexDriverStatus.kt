package app.aaps.pump.apex

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventPumpStatusChanged
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import app.aaps.core.interfaces.R as InterfacesR
import app.aaps.core.ui.R as CoreUiR

@SingleIn(AppScope::class)
@Inject
class ApexDriverStatus(
    val rh: ResourceHelper,
    val rxBus: RxBus,
    val config: Config,
) {
    // There may be several actions at the same time, but only the most important one is shown.
    private val actions = mutableMapOf<Action, String>()

    // When the state is not Connected, the connection state is shown instead of the actions.
    private var connectionState = ConnectionState.Disconnected

    // Last status message
    private var lastMessage: String? = null

    // All driver actions, the most important first.
    enum class Action {
        DriverError,

        CancelingBolus,
        CancelingTBR,
        SettingBolus,
        SettingMicroBolus,
        SettingExtendedBolus,
        SettingTBR,

        Bolusing,
        MicroBolusing,

        RequestingHeartbeat,
        UpdatingDateTime,
        SettingBasalProfileContents,
        SettingBasalProfileIndex,
        UpdatingSettings,
        UpdatingConnectionProfile,
        UpdatingSystemState,

        GettingVersion,
        GettingTDDs,
        GettingBoluses,
        GettingRefills,
        GettingStatus,
        GettingBasalProfiles,

        FallbackExecutingCommand,
        FallbackGettingValue,

        Initializing,
        Reconnecting,
        CheckingPump,
        Heartbeat,
    }

    enum class ConnectionState {
        Disconnecting,
        Disconnected,
        Connecting,
        Connected;

        val asPumpStatus get() = when (this) {
            Disconnecting -> EventPumpStatusChanged.Status.DISCONNECTING
            Disconnected -> EventPumpStatusChanged.Status.DISCONNECTED
            Connecting -> EventPumpStatusChanged.Status.CONNECTING
            Connected -> EventPumpStatusChanged.Status.CONNECTED
        }
    }

    private fun notifyListeners() {
        val mostImportant = actions.entries.minByOrNull { it.key.ordinal }
        lastMessage = if (config.isEngineeringMode())
            actions.entries.sortedBy { it.key.ordinal }.joinToString("\n") { "· ${it.value}" }
        else
            mostImportant?.value

        // Sending EventPumpStatusChanged while bolusing closes the bolus dialog.
        if (connectionState != ConnectionState.Connected || actions.isEmpty())
            rxBus.send(EventPumpStatusChanged(connectionState.asPumpStatus))
        else
            rxBus.send(EventPumpStatusChanged(
                when (connectionState) {
                    ConnectionState.Disconnected -> rh.gs(CoreUiR.string.disconnected)
                    ConnectionState.Disconnecting -> rh.gs(InterfacesR.string.disconnecting)
                    ConnectionState.Connecting -> rh.gs(CoreUiR.string.connecting)
                    else -> lastMessage?.split("\n")?.firstOrNull() ?: rh.gs(InterfacesR.string.connected)
                }
            ))
        rxBus.send(EventApexPumpDataChanged())
    }

    val message get() = lastMessage

    fun addAction(action: Action, message: String) {
        if (actions.containsKey(action)) return
        updateOrAddAction(action, message)
    }

    fun updateAction(action: Action, message: String) {
        if (!actions.containsKey(action)) return
        updateOrAddAction(action, message)
    }

    fun addAction(action: Action, message: Int) =
        addAction(action, rh.gs(message))

    fun updateOrAddAction(action: Action, message: String) = synchronized(actions) {
        actions[action] = message
        notifyListeners()
    }

    fun removeAction(action: Action) = synchronized(actions)  {
        actions.remove(action)
        notifyListeners()
    }

    fun updateConnectionState(state: ConnectionState) = synchronized(actions) {
        connectionState = state
        notifyListeners()
    }
}