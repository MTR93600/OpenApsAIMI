package app.aaps.pump.apex

import app.aaps.pump.apex.interfaces.ApexBluetoothCallback
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventDismissNotification
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.interfaces.rx.events.EventPreferenceChange
import app.aaps.core.interfaces.rx.events.EventProfileSwitchChanged
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.notifyAll
import app.aaps.core.utils.toHex
import app.aaps.core.utils.waitMillis
import app.aaps.pump.apex.connectivity.ApexBluetooth
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.connectivity.commands.device.Bolus
import app.aaps.pump.apex.connectivity.commands.device.CancelBolus
import app.aaps.pump.apex.connectivity.commands.device.CancelTemporaryBasal
import app.aaps.pump.apex.connectivity.commands.device.DeviceCommand
import app.aaps.pump.apex.connectivity.commands.device.ExtendedBolus
import app.aaps.pump.apex.connectivity.commands.device.GetValue
import app.aaps.pump.apex.connectivity.commands.device.RequestHeartbeat
import app.aaps.pump.apex.connectivity.commands.device.SetConnectionProfile
import app.aaps.pump.apex.connectivity.commands.device.SyncDateTime
import app.aaps.pump.apex.connectivity.commands.device.TemporaryBasal
import app.aaps.pump.apex.connectivity.commands.device.UpdateBasalProfileRates
import app.aaps.pump.apex.connectivity.commands.device.UpdateSystemState
import app.aaps.pump.apex.connectivity.commands.device.UpdateUsedBasalProfile
import app.aaps.pump.apex.connectivity.commands.pump.Alarm
import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.connectivity.commands.pump.AlarmObject
import app.aaps.pump.apex.connectivity.commands.pump.BasalProfile
import app.aaps.pump.apex.connectivity.commands.pump.BolusEntry
import app.aaps.pump.apex.connectivity.commands.pump.CommandResponse
import app.aaps.pump.apex.connectivity.commands.pump.Heartbeat
import app.aaps.pump.apex.connectivity.commands.pump.PumpCommand
import app.aaps.pump.apex.connectivity.commands.pump.PumpObject
import app.aaps.pump.apex.connectivity.commands.pump.PumpObjectModel
import app.aaps.pump.apex.connectivity.commands.pump.StatusV1
import app.aaps.pump.apex.connectivity.commands.pump.StatusV2
import app.aaps.pump.apex.connectivity.commands.pump.TDDEntry
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.events.EventApexPumpDataChanged
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import dagger.android.DaggerService
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import kotlinx.coroutines.sync.Mutex
import org.joda.time.DateTime
import java.util.Timer
import java.util.TimerTask
import javax.inject.Inject
import kotlin.concurrent.schedule
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * @author Roman Rikhter (teledurak@gmail.com)
 */
class ApexService: DaggerService(), ApexBluetoothCallback {
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var preferences: Preferences
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var apexBluetooth: ApexBluetooth
    @Inject lateinit var apexDeviceInfo: ApexDeviceInfo
    @Inject lateinit var apexPumpPlugin: ApexPumpPlugin
    @Inject lateinit var uiInteraction: UiInteraction
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var commandQueue: CommandQueue
    @Inject lateinit var pumpSync: PumpSync
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var pump: ApexPump
    @Inject lateinit var config: Config
    @Inject lateinit var status: ApexDriverStatus

    companion object {
        const val COMMAND_RESPONSE_TIMEOUT = 5000L
        const val SINGLE_VALUE_RESPONSE_TIMEOUT = 5000L
        const val COMPLEX_VALUE_RESPONSE_TIMEOUT = 30000L

        const val USED_BASAL_PATTERN_INDEX = 7
        const val HEARTBEAT_PERIOD_MINUTES = 2
        val FIRST_SUPPORTED_PROTO = ProtocolVersion.PROTO_4_9
        val LAST_SUPPORTED_PROTO = ProtocolVersion.PROTO_4_11
    }

    private data class InCommandResponse(
        var response: CommandResponse? = null,
        var waiting: Boolean = false,
    ) {
        fun clear(notify: Boolean = false) {
            response = null
            waiting = true
            if (notify) this.notifyAll()
        }
    }

    private data class InGetValueResult(
        var isSingleObject: Boolean = false,
        var targetObject: PumpObject? = null,
        var waiting: Boolean = false,
        var response: ArrayList<PumpObjectModel>? = null,
    ) {
        fun add(data: PumpObjectModel) {
            if (response == null) response = arrayListOf()
            response!!.add(data)
        }

        fun clear(notify: Boolean = false) {
            response = null
            targetObject = null
            isSingleObject = false
            waiting = true
            if (notify) this.notifyAll()
        }
    }

    private val commandLock = Mutex()
    private val disposable = CompositeDisposable()

    private val getValueResult = InGetValueResult()
    private val commandResponse = InCommandResponse()

    private var timer = Timer("ApexService-timer")

    private var getValueLastTaskTimestamp: Long = 0
    private var unreachableTimerTask: TimerTask? = null

    private var lastBolusDateTime = DateTime(0)
    private var lastConnectedTimestamp = System.currentTimeMillis()

    private var manualDisconnect = false
    private var doNotReconnect = false
    private var connectionFinished = false

    private var connectionId = 0

    val isReadyForExecutingCommands: Boolean get() = connectionFinished

    val lastConnected: Long
        get() = if (connectionStatus != ApexBluetooth.Status.CONNECTED) {
            lastConnectedTimestamp
        } else System.currentTimeMillis()

    private fun intGetValue(value: GetValue.Value): List<PumpObjectModel>? = synchronized(getValueResult) {
        if (connectionStatus != ApexBluetooth.Status.CONNECTED) {
            aapsLogger.debug(LTag.PUMPCOMM, "Get ${value.name} | Error - pump is disconnected")
            return null
        }

        getValueResult.clear()
        getValueResult.targetObject = when (value) {
            GetValue.Value.StatusV1 -> PumpObject.StatusV1
            GetValue.Value.StatusV2 -> PumpObject.StatusV2
            GetValue.Value.TDDs -> PumpObject.TDDEntry
            GetValue.Value.Alarms -> PumpObject.AlarmEntry
            GetValue.Value.BasalProfiles -> PumpObject.BasalProfile
            GetValue.Value.Version -> PumpObject.FirmwareEntry
            GetValue.Value.BolusHistory, GetValue.Value.LatestBoluses -> PumpObject.BolusEntry
            GetValue.Value.LatestTemporaryBasals -> return null
            GetValue.Value.WizardStatus -> return null
        }
        getValueResult.isSingleObject = when (value) {
            GetValue.Value.StatusV1, GetValue.Value.StatusV2, GetValue.Value.Version -> true
            else -> false
        }

        status.addAction(
            ApexDriverStatus.Action.FallbackGettingValue,
            rh.gs(R.string.action_fallback_getting_obj, value.name)
        )

        apexBluetooth.send(GetValue(apexDeviceInfo, value))
        try {
            aapsLogger.debug(LTag.PUMPCOMM, "Get ${value.name} | Waiting for response")
            getValueResult.waitMillis(if (getValueResult.isSingleObject) SINGLE_VALUE_RESPONSE_TIMEOUT else COMPLEX_VALUE_RESPONSE_TIMEOUT)
        } catch (_: InterruptedException) {
            aapsLogger.error(LTag.PUMPCOMM, "Get ${value.name} | Timed out")
            isGetThreadRunning = false
            status.removeAction(ApexDriverStatus.Action.FallbackGettingValue)
            return null
        }

        if (getValueResult.response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "Get ${value.name} | Timed out")
            isGetThreadRunning = false
            status.removeAction(ApexDriverStatus.Action.FallbackGettingValue)
            return null
        }

        aapsLogger.debug(LTag.PUMPCOMM, "Get ${value.name} | Completed")
        status.removeAction(ApexDriverStatus.Action.FallbackGettingValue)
        getValueResult.response
    }

    private fun intExecuteWithResponse(command: DeviceCommand): CommandResponse? = synchronized(commandResponse) {
        if (connectionStatus != ApexBluetooth.Status.CONNECTED) {
            aapsLogger.debug(LTag.PUMPCOMM, "$command | Error - pump is disconnected")
            return null
        }

        status.addAction(
            ApexDriverStatus.Action.FallbackExecutingCommand,
            rh.gs(R.string.action_fallback_executing_obj, command.toString())
        )

        commandResponse.clear()
        apexBluetooth.send(command)
        try {
            aapsLogger.debug(LTag.PUMPCOMM, "$command | Waiting for response")
            commandResponse.waitMillis(COMMAND_RESPONSE_TIMEOUT)
        } catch (_: InterruptedException) {
            aapsLogger.error(LTag.PUMPCOMM, "$command | Timed out")
            commandResponse.waiting = false
            status.removeAction(ApexDriverStatus.Action.FallbackExecutingCommand)
            return null
        }

        if (commandResponse.response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "$command | Timed out")
            commandResponse.waiting = false
            status.removeAction(ApexDriverStatus.Action.FallbackExecutingCommand)
            return null
        }

        aapsLogger.debug(LTag.PUMPCOMM, "$command | Completed")
        status.removeAction(ApexDriverStatus.Action.FallbackExecutingCommand)
        commandResponse.response
    }

    private val cannotBeReconnected: Boolean
        get() =
            apexBluetooth.status != ApexBluetooth.Status.CONNECTED || // if pump was disconnected
            doNotReconnect || // if some command has already requested reconnect
            !connectionFinished || // if driver is initializing
            pump.inProgressBolus?.lockHistory == true // if driver is listening to bolus progress

    fun getValue(value: GetValue.Value, noOptimizations: Boolean = false): List<PumpObjectModel>? {
        synchronized(commandLock) {
            val firstTry = intGetValue(value)
            if (firstTry != null || noOptimizations || cannotBeReconnected) return@getValue firstTry
            doNotReconnect = true
            status.addAction(ApexDriverStatus.Action.Reconnecting, R.string.action_reconnecting)
            disconnect(true)
        }

        if (!ensureConnected()) {
            aapsLogger.error(LTag.PUMPCOMM, "Get ${value.name} | Timed out waiting for reconnection")
            status.removeAction(ApexDriverStatus.Action.Reconnecting)
            synchronized(commandLock) { doNotReconnect = false }
            return null
        }

        status.removeAction(ApexDriverStatus.Action.Reconnecting)
        synchronized(commandLock) {
            doNotReconnect = false
            val final = intGetValue(value)
            return@getValue final
        }
    }

    private fun executeWithResponse(command: DeviceCommand, noOptimizations: Boolean = false): CommandResponse? {
        synchronized(commandLock) {
            val firstTry = intExecuteWithResponse(command)
            if (firstTry != null || noOptimizations || cannotBeReconnected) return@executeWithResponse firstTry
            doNotReconnect = true
            status.addAction(ApexDriverStatus.Action.Reconnecting, R.string.action_reconnecting)
            disconnect(true)
        }

        if (!ensureConnected()) {
            aapsLogger.error(LTag.PUMPCOMM, "$command | Timed out waiting for reconnection")
            status.removeAction(ApexDriverStatus.Action.Reconnecting)
            synchronized(commandLock) { doNotReconnect = false }
            return null
        }

        status.removeAction(ApexDriverStatus.Action.Reconnecting)
        synchronized(commandLock) {
            doNotReconnect = false
            val final = intExecuteWithResponse(command)
            return@executeWithResponse final
        }
    }

    private fun ensureConnected(): Boolean {
        var times = 0
        while (!connectionFinished && times < 100) {
            aapsLogger.debug(LTag.PUMPCOMM, "Waiting for successful connection")
            SystemClock.sleep(500)
            times++
        }
        return connectionFinished
    }

    override fun onCreate() {
        super.onCreate()
        aapsLogger.debug(LTag.PUMP, "Service created")
        apexBluetooth.setCallback(this)

        disposable += rxBus
            .toObservable(EventPreferenceChange::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({
                        when (it.changedKey) {
                            ApexStringKey.FirmwareVer.key -> onFwVerChanged()
                            ApexStringKey.SerialNumber.key -> onSerialChanged()
                            ApexStringKey.CalcBatteryType.key, ApexDoubleKey.BatteryLowVoltage.key, ApexDoubleKey.BatteryHighVoltage.key, ApexBooleanKey.CalculateBatteryPercentage.key -> onBatteryStuffChanged()
                            ApexDoubleKey.MaxBolus.key -> if (pump.maxBolus != preferences.get(ApexDoubleKey.MaxBolus)) updateSettings("ApexService-PreferencesListener-MaxBolus")
                            ApexDoubleKey.MaxBasal.key -> if (pump.maxBasal != preferences.get(ApexDoubleKey.MaxBasal)) updateSettings("ApexService-PreferencesListener-MaxBasal")
                            ApexStringKey.AlarmSoundLength.key -> if (pump.lastV2?.alarmLength?.name != preferences.get(ApexStringKey.AlarmSoundLength)) updateSettings("ApexService-PreferencesListener-AlarmSoundLength")
                        }
                       }, fabricPrivacy::logException)

        pump.serialNumber = apexDeviceInfo.serialNumber
    }

    override fun onDestroy() {
        aapsLogger.debug(LTag.PUMP, "Service destroyed")
        disposable.clear()
        disconnect()
        super.onDestroy()
    }

    private fun onSerialChanged() {
        pump.serialNumber = apexDeviceInfo.serialNumber
        if (apexBluetooth.status != ApexBluetooth.Status.DISCONNECTED) disconnect()
        startConnection()
    }

    private fun onFwVerChanged() {
        if (apexBluetooth.status != ApexBluetooth.Status.DISCONNECTED) disconnect()
        startConnection()
    }

    private fun onBatteryStuffChanged() {
        getStatus("ApexService-onBatteryStuffChanged", force = true)
    }

    //////// Public methods

    fun checkPump(caller: String, optimize: Boolean = false): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "checkPump - $caller")

        try {
            status.addAction(ApexDriverStatus.Action.CheckingPump, R.string.action_checking_pump)
            return getStatus("ApexService-checkPump", optimize)
        } finally {
            status.removeAction(ApexDriverStatus.Action.CheckingPump)
        }
    }

    fun syncDateTime(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "syncDateTime - $caller")

        status.addAction(ApexDriverStatus.Action.UpdatingDateTime, R.string.action_updating_dt)
        val response = executeWithResponse(SyncDateTime(apexDeviceInfo, DateTime.now()), noOptimizations = true)
        status.removeAction(ApexDriverStatus.Action.UpdatingDateTime)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[syncDateTime caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to sync time: ${response.code.name}")
            return false
        }

        return true
    }

    fun requestHeartbeat(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "requestHeartbeat - $caller")

        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_10) != true) {
            aapsLogger.warn(LTag.PUMPCOMM, "requestHeartbeat isn't supported yet.")
            return true
        }

        status.addAction(ApexDriverStatus.Action.RequestingHeartbeat, R.string.action_requesting_heartbeat)
        val response = executeWithResponse(RequestHeartbeat(apexDeviceInfo, HEARTBEAT_PERIOD_MINUTES), noOptimizations = true)
        status.removeAction(ApexDriverStatus.Action.RequestingHeartbeat)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[requestHeartbeat caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to notify about connection: ${response.code.name}")
            return false
        }

        return true
    }

    fun bolus(dbi: DetailedBolusInfo, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus - $caller")
        if (dbi.insulin > pump.maxBolus) {
            aapsLogger.error(LTag.PUMP, "[bolus caller=$caller] Requested ${dbi.insulin}U is greater than maximum set ${pump.maxBolus}")
            return false
        }

        val doseRaw = (dbi.insulin / 0.025).roundToInt()
        val temporaryId = DateTime.now().withSecondOfMinute(59).withMillisOfSecond(0).millis

        val action = if (dbi.bolusType == BS.Type.SMB)
            ApexDriverStatus.Action.SettingMicroBolus
        else
            ApexDriverStatus.Action.SettingBolus

        if (!checkPump("ApexService-bolus", optimize = true)) return false

        status.addAction(action, rh.gs(
            if (dbi.bolusType == BS.Type.SMB)
                R.string.action_setting_smb
            else
                R.string.action_setting_bolus,
            dbi.insulin,
        ))
        val response = executeWithResponse(Bolus(apexDeviceInfo, doseRaw), noOptimizations = true)
        status.removeAction(action)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[bolus caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code == CommandResponse.Code.Invalid) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Cannot begin bolus while in special mode")
            createSpecialModeAlarm()
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to begin bolus: ${response.code.name}")
            return false
        }

        val syncResult = pumpSync.addBolusWithTempId(
            timestamp = dbi.timestamp,
            amount = dbi.insulin,
            temporaryId = temporaryId,
            type = dbi.bolusType,
            pumpSerial = apexDeviceInfo.serialNumber,
            pumpType = PumpType.APEX_TRUCARE_III,
        )
        aapsLogger.debug(LTag.PUMP, "Initial bolus [${dbi.insulin}U] sync succeeded? $syncResult")

        pump.inProgressBolus = ApexPump.InProgressBolus(
            requestedDose = dbi.insulin,
            temporaryId = temporaryId,
            detailedBolusInfo = dbi,
            lockHistory = true,
            treatment = ApexPump.Treatment(
                insulin = dbi.insulin,
                carbs = dbi.carbs.toInt(),
                isSMB = dbi.bolusType == BS.Type.SMB,
                id = dbi.id
            )
        )

        return true
    }

    fun extendedBolus(dose: Double, durationMinutes: Int, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "extendedBolus - $caller")
        val doseRaw = (dose / 0.025).roundToInt()

        val durationRaw = durationMinutes / 15
        if (durationMinutes % 15 > 0) aapsLogger.warn(LTag.PUMPCOMM, "[extendedBolus caller=$caller] Bolus duration is not aligned to 15 minute steps! Rounded down.")

        if (!checkPump("ApexService-extendedBolus", optimize = true)) return false

        status.addAction(
            ApexDriverStatus.Action.SettingExtendedBolus,
            rh.gs(R.string.action_setting_ext_bolus, dose, durationMinutes)
        )
        val response = executeWithResponse(ExtendedBolus(apexDeviceInfo, doseRaw, durationRaw), noOptimizations = true)
        status.removeAction(ApexDriverStatus.Action.SettingExtendedBolus)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[extendedBolus caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to begin extended bolus: ${response.code.name}")
            return false
        }

        return true
    }

    fun temporaryBasal(dose: Double, durationMinutes: Int, type: PumpSync.TemporaryBasalType? = null, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "temporaryBasal - $caller")
        if (dose > pump.maxBasal) {
            aapsLogger.error(LTag.PUMP, "[temporaryBasal caller=$caller] Requested ${dose}U is greater than maximum set ${pump.maxBasal}U")
            return false
        }

        val doseRaw = (dose / 0.025).roundToInt()

        val durationRaw = durationMinutes / 15
        if (durationMinutes % 15 > 0) aapsLogger.warn(LTag.PUMPCOMM, "[temporaryBasal caller=$caller] Bolus duration is not aligned to 15 minute steps! Rounded down.")

        if (!checkPump("ApexService-temporaryBasal", optimize = true)) return false

        status.addAction(
            ApexDriverStatus.Action.SettingTBR,
            rh.gs(R.string.action_setting_tbr, dose, durationMinutes)
        )
        val response = executeWithResponse(TemporaryBasal(apexDeviceInfo, true, durationRaw, doseRaw))
        status.removeAction(ApexDriverStatus.Action.SettingTBR)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[temporaryBasal caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to start temporary basal: ${response.code.name}")
            return false
        }

        val id = System.currentTimeMillis()
        pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = id,
            pumpId = id,
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
            rate = dose,
            duration = durationMinutes.toLong() * 60 * 1000,
            isAbsolute = true,
            type = type,
        )

        if (type == PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND) {
            aapsLogger.debug(LTag.PUMP, "Emulated pump suspend detected - disconnecting pump after the queue ends.")
            onEmulatedSuspend()
        }

        aapsLogger.debug(LTag.PUMP, "Started TBR ${dose}U for ${durationMinutes}min by $caller")
        getStatus("ApexService-temporaryBasal")
        return true
    }

    fun cancelBolus(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "cancelBolus - $caller")

        pump.inProgressBolus?.let {
            // Communication would take longer than just finishing the bolus.
            if (it.requestedDose - it.currentDose < 0.10) {
                aapsLogger.debug(LTag.PUMPCOMM, "[cancelBolus caller=$caller] Skipping, progress ${it.currentDose} / ${it.requestedDose} U")
                return@cancelBolus true
            }
        } ?: return true

        status.addAction(ApexDriverStatus.Action.CancelingBolus, R.string.action_canceling_bolus)
        val response = executeWithResponse(CancelBolus(apexDeviceInfo))
        status.removeAction(ApexDriverStatus.Action.CancelingBolus)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[cancelBolus caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to cancel bolus: ${response.code.name}")
            return false
        }

        onBolusFailed(true)
        getStatus("ApexService-cancelBolus")
        return true
    }

    fun cancelTemporaryBasal(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "cancelTemporaryBasal - $caller")

        status.addAction(ApexDriverStatus.Action.CancelingTBR, R.string.action_canceling_tbr)
        val response = executeWithResponse(CancelTemporaryBasal(apexDeviceInfo))
        status.removeAction(ApexDriverStatus.Action.CancelingTBR)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[cancelTemporaryBasal caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to cancel temporary basal: ${response.code.name}")
            return false
        }

        val stop = System.currentTimeMillis()
        pumpSync.syncStopTemporaryBasalWithPumpId(
            timestamp = stop,
            endPumpId = stop,
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
        )

        getStatus("ApexService-cancelTBR")
        return true
    }

    fun updateSettings(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "updateSettings - $caller")

        status.addAction(ApexDriverStatus.Action.UpdatingSettings, R.string.action_updating_settings)
        val response = executeWithResponse(
            pump.lastV1!!.toUpdateSettingsV1(
                apexDeviceInfo,
                AlarmLength.valueOf(preferences.get(ApexStringKey.AlarmSoundLength)),
                maxSingleBolus = (preferences.get(ApexDoubleKey.MaxBolus) / 0.025).roundToInt(),
                maxBasalRate = (preferences.get(ApexDoubleKey.MaxBasal) / 0.025).roundToInt(),
                enableAdvancedBolus = false,
            )
        )
        status.removeAction(ApexDriverStatus.Action.UpdatingSettings)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateSettings caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update settings: ${response.code.name}")
            return false
        }

        return true
    }

    fun updateSystemState(suspend: Boolean, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "updateSystemState - $caller")

        status.addAction(ApexDriverStatus.Action.UpdatingSystemState, R.string.action_updating_sys_state)
        val response = executeWithResponse(UpdateSystemState(apexDeviceInfo, suspend))
        status.removeAction(ApexDriverStatus.Action.UpdatingSystemState)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateSystemState caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update system state: ${response.code.name}")
            return false
        }

        return true
    }

    fun setConnectionProfile(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "setConnectionProfile - $caller")

        status.addAction(ApexDriverStatus.Action.UpdatingConnectionProfile, R.string.action_updating_connection)
        val response = executeWithResponse(SetConnectionProfile(apexDeviceInfo))
        status.removeAction(ApexDriverStatus.Action.UpdatingConnectionProfile)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[setConnectionProfile caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to set connection profile: ${response.code.name}")
            return false
        }

        return true
    }

    fun updateBasalPatternIndex(id: Int, caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "updateBasalPatternIndex - $caller")

        status.addAction(ApexDriverStatus.Action.SettingBasalProfileIndex, R.string.action_setting_basal_no)
        val response = executeWithResponse(UpdateUsedBasalProfile(apexDeviceInfo, id))
        status.removeAction(ApexDriverStatus.Action.SettingBasalProfileIndex)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateBasalPatternIndex caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update basal pattern index: ${response.code.name}")
            return false
        }

        if (!commandQueue.isRunning(Command.CommandType.BASAL_PROFILE)) rxBus.send(EventProfileSwitchChanged())
        return true
    }

    fun updateCurrentBasalPattern(doses: List<Double>, caller: String): Boolean {
        require(doses.size == 48)

        aapsLogger.debug(LTag.PUMPCOMM, "updateCurrentBasalPattern - $caller")

        status.addAction(ApexDriverStatus.Action.SettingBasalProfileContents, R.string.action_setting_basal_profile)
        val response = executeWithResponse(UpdateBasalProfileRates(
            apexDeviceInfo,
            doses.map { (it / 0.025).roundToInt() }
        ))
        status.removeAction(ApexDriverStatus.Action.SettingBasalProfileContents)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[updateBasalPatternIndex caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        if (response.code != CommandResponse.Code.Accepted) {
            aapsLogger.error(LTag.PUMPCOMM, "[caller=$caller] Failed to update basal pattern index: ${response.code.name}")
            return false
        }

        return true
    }

    fun getTDDs(caller: String): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "getTDDs - $caller")

        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) != true) {
            aapsLogger.warn(LTag.PUMPCOMM, "TDDs are unreliable on 6.25 and older!")
            return false
        }

        status.addAction(ApexDriverStatus.Action.GettingTDDs, R.string.action_getting_tdds)
        val response = getValue(GetValue.Value.TDDs)
        status.removeAction(ApexDriverStatus.Action.GettingTDDs)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getTDDs caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        return true
    }

    fun getBoluses(caller: String, isFullHistory: Boolean = false): Boolean {
        aapsLogger.debug(LTag.PUMPCOMM, "getBoluses - $caller")

        if (pump.inProgressBolus?.lockHistory == true) {
            aapsLogger.info(LTag.PUMPCOMM, "Pump history is locked. Bolus is in progress.")
            return true
        }

        status.addAction(ApexDriverStatus.Action.GettingBoluses, R.string.action_getting_boluses)
        val response = getValue((if (isFullHistory) GetValue.Value.BolusHistory else GetValue.Value.LatestBoluses))
        status.removeAction(ApexDriverStatus.Action.GettingBoluses)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getBoluses full=$isFullHistory caller=$caller] Timed out while trying to communicate with the pump")
            return false
        }

        return true
    }

    fun getStatus(caller: String, optimize: Boolean = false, force: Boolean = false): Boolean {
        if (abs(DateTime.now().millis - pump.dateTime.millis) < 25000 && !force) {
            aapsLogger.debug(LTag.PUMPCOMM, "Status is already fresh, skipping unnecessary update.")
            return true
        }

        val hasV2 = pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) == true

        aapsLogger.debug(LTag.PUMPCOMM, "getStatus - $caller")

        status.addAction(ApexDriverStatus.Action.GettingStatus,
             if (hasV2) rh.gs(R.string.action_getting_status_v, 1)
             else rh.gs(R.string.action_getting_status)
        )
        val responseV1 = getValue(GetValue.Value.StatusV1, noOptimizations = !optimize)
        if (!hasV2 || responseV1 == null) status.removeAction(ApexDriverStatus.Action.GettingStatus)

        if (responseV1 == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getStatus caller=$caller] V1 | Timed out while trying to communicate with the pump")
            return false
        }

        if (hasV2) {
            status.updateAction(ApexDriverStatus.Action.GettingStatus, rh.gs(R.string.action_getting_status_v, 2))
            val responseV2 = getValue(GetValue.Value.StatusV2, noOptimizations = true)
            status.removeAction(ApexDriverStatus.Action.GettingStatus)

            if (responseV2 == null) {
                aapsLogger.error(LTag.PUMPCOMM, "[getStatus caller=$caller] V2 | Timed out while trying to communicate with the pump")
                return false
            }
        }

        return true
    }

    fun getBasalProfiles(caller: String): Map<Int, List<Double>>? {
        val ret = mutableMapOf<Int, List<Double>>()
        aapsLogger.debug(LTag.PUMPCOMM, "getBasalProfiles - $caller")

        status.addAction(ApexDriverStatus.Action.GettingBasalProfiles, R.string.action_getting_basal)
        val response = getValue(GetValue.Value.BasalProfiles)
        status.removeAction(ApexDriverStatus.Action.GettingBasalProfiles)

        if (response == null) {
            aapsLogger.error(LTag.PUMPCOMM, "[getBasalProfiles caller=$caller] Timed out while trying to communicate with the pump")
            return null
        }

        for (i in response) {
            require(i is BasalProfile)
            ret[i.index] = i.rates.map { it * 0.025 }
        }

        return ret
    }

    //////// Public values

    val connectionStatus: ApexBluetooth.Status
        get() = apexBluetooth.status

    //////// Pump commands handlers

    @Synchronized
    private fun onEmulatedSuspend() {
        Thread {
            SystemClock.sleep(2500)
            status.updateConnectionState(ApexDriverStatus.ConnectionState.Disconnecting)
            while (commandQueue.size() != 0) {
                aapsLogger.debug(LTag.PUMP, "Waiting for queue to end")
                SystemClock.sleep(250)
            }
            aapsLogger.debug(LTag.PUMP, "Disconnecting")
            disconnect()
        }.start()
        SystemClock.sleep(100)
    }

    private fun onBolusProgress(dose: Double) {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus progress $dose")
        pump.inProgressBolus?.let {
            it.currentDose = dose
            val isSMB = it.detailedBolusInfo.bolusType == BS.Type.SMB

            status.updateOrAddAction(
                if (isSMB)
                    ApexDriverStatus.Action.MicroBolusing
                else
                    ApexDriverStatus.Action.Bolusing,
                rh.gs(
                    if (isSMB)
                        R.string.action_bolusing_smb
                    else
                        R.string.action_bolusing,
                    it.currentDose,
                    it.requestedDose
                )
            )

            rxBus.send(EventOverviewBolusProgress(rh.gs(R.string.status_delivering, dose), null, (it.currentDose / it.requestedDose * 100).roundToInt()))
        }
    }

    private fun onBolusCompleted(dose: Double) {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus completed")
        pump.inProgressBolus?.let {
            it.currentDose = dose
            it.lockHistory = false

            status.removeAction(
                if (it.detailedBolusInfo.bolusType == BS.Type.SMB)
                    ApexDriverStatus.Action.MicroBolusing
                else
                    ApexDriverStatus.Action.Bolusing
            )
            rxBus.send(EventOverviewBolusProgress(rh.gs(R.string.status_delivered, dose), null, 100))

            // Request new bolus history to fixup bolus ID.
            Thread { getBoluses("ApexService-onBolusCompleted") }.start()
        }
    }

    private fun onBolusFailed(cancelled: Boolean = false) {
        aapsLogger.debug(LTag.PUMPCOMM, "bolus failed (cancelled? $cancelled)")
        pump.inProgressBolus?.let {
            it.lockHistory = false

            status.removeAction(
                if (it.detailedBolusInfo.bolusType == BS.Type.SMB)
                    ApexDriverStatus.Action.MicroBolusing
                else
                    ApexDriverStatus.Action.Bolusing
            )

            if (cancelled) {
                it.cancelled = true
                rxBus.send(EventOverviewBolusProgress(rh.gs(R.string.status_bolus_cancelled)))
            }

            if (it.currentDose >= 0.025) {
                // Request new bolus history to fixup bolus ID and delivered amount.
                Thread { getBoluses("ApexService-onBolusCompleted") }.start()
            } else {
                aapsLogger.debug(LTag.PUMPCOMM, "bolus entirely failed!")
                synchronized(it) {
                    it.failed = true
                    it.notifyAll()
                }
                SystemClock.sleep(100)
                pump.inProgressBolus = null
            }
        }
    }

    private fun onCommandResponse(response: CommandResponse) {
        aapsLogger.debug(LTag.PUMPCOMM, "got command response - ${response.code.name} / ${response.dose}")
        when (response.code) {
            CommandResponse.Code.Accepted, CommandResponse.Code.Invalid -> {
                if (!commandResponse.waiting) return
                commandResponse.response = response
                commandResponse.waiting = false
                synchronized(commandResponse) {
                    commandResponse.notifyAll()
                }
            }
            CommandResponse.Code.StandardBolusProgress -> onBolusProgress(response.dose * 0.025)
            CommandResponse.Code.ExtendedBolusProgress -> return
            CommandResponse.Code.Completed             -> onBolusCompleted(response.dose * 0.025)
            else                                       -> return
        }
    }

    private fun onAlarmsChanged(update: ApexPump.StatusUpdate) {
        val prev = update.previous?.alarms
        // Alarm was dismissed
        if (!prev.isNullOrEmpty() && update.current.alarms.isEmpty()) {
            rxBus.send(EventDismissNotification(Notification.PUMP_ERROR))
            rxBus.send(EventDismissNotification(Notification.PUMP_WARNING))
        }

        // New alarms
        if (prev.isNullOrEmpty() && update.current.alarms.isNotEmpty()) {
            var anyUrgent = false

            for (alarm in update.current.alarms) {
                val name = when (alarm) {
                    Alarm.NoDosage, Alarm.NoDelivery -> rh.gs(R.string.alarm_occlusion)
                    Alarm.NoReservoir -> rh.gs(R.string.alarm_reservoir_empty)
                    Alarm.DeadBattery -> rh.gs(R.string.alarm_battery_dead)
                    Alarm.LowBattery -> rh.gs(R.string.alarm_w_battery_low)
                    Alarm.LowReservoir -> rh.gs(R.string.alarm_w_reservoir_low)
                    Alarm.EncoderError, Alarm.FRAMError, Alarm.ClockError, Alarm.TimeError,
                    Alarm.TimeAnomalyError, Alarm.MotorAbnormal, Alarm.MotorPowerAbnormal,
                    Alarm.MotorError -> rh.gs(R.string.alarm_hardware_fault, alarm.name)
                    Alarm.Unknown -> rh.gs(R.string.alarm_unknown_error)
                    Alarm.CheckGlucose -> rh.gs(R.string.alarm_check_bg)
                    else -> rh.gs(R.string.alarm_unknown_error_name, alarm.name)
                }
                val isUrgent = when(alarm) {
                    Alarm.LowBattery, Alarm.LowReservoir, Alarm.CheckGlucose -> false
                    else -> true
                }
                if (isUrgent) anyUrgent = true

                uiInteraction.addNotification(
                    if (isUrgent) Notification.PUMP_ERROR else Notification.PUMP_WARNING,
                    rh.gs(R.string.alarm_label, name),
                    if (isUrgent) Notification.URGENT else Notification.NORMAL,
                )
                pumpSync.insertAnnouncement(
                    error = rh.gs(R.string.alarm_label, name),
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                )
            }

            if (anyUrgent && pump.isBolusing) {
                // Pump sends early heartbeat while bolusing if there's an error while bolusing.
                aapsLogger.error(LTag.PUMP, "Bolus has failed!")
                Thread { onBolusFailed() }.start()
            }
        }
    }

    private fun onBasalChanged(update: ApexPump.StatusUpdate) {
        if (update.current.basal == null) {
            uiInteraction.addNotification(
                Notification.PUMP_SUSPENDED,
                rh.gs(R.string.notification_pump_is_suspended),
                if (pump.isBolusing) Notification.URGENT else Notification.NORMAL,
            )
            commandQueue.loadEvents(null)
            return
        } else {
            rxBus.send(EventDismissNotification(Notification.PUMP_SUSPENDED))
        }
    }

    private fun onSettingsChanged(update: ApexPump.StatusUpdate) {
        if (pump.settingsAreUnadvised && preferences.get(ApexDoubleKey.MaxBasal) != 0.0 && preferences.get(ApexDoubleKey.MaxBolus) != 0.0) updateSettings("ApexService-onSettingsChanged")
        if (update.current.currentBasalPattern != USED_BASAL_PATTERN_INDEX) updateBasalPatternIndex(USED_BASAL_PATTERN_INDEX, "ApexService-onSettingsChanged")
    }

    private fun onBatteryChanged(update: ApexPump.StatusUpdate) {
        val cur = update.current
        update.previous?.let { old ->
            // Percentage became higher - battery was changed.
            if (cur.batteryLevel.percentage - 26 > old.batteryLevel.percentage && preferences.get(ApexBooleanKey.LogBatteryChange)) {
                pumpSync.insertTherapyEventIfNewWithTimestamp(
                    timestamp = System.currentTimeMillis(),
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                    type = TE.Type.PUMP_BATTERY_CHANGE,
                )
                aapsLogger.debug(LTag.PUMP, "Logged battery change")
            }
        }
    }

    private fun onReservoirChanged(update: ApexPump.StatusUpdate) {
        val cur = update.current
        update.previous?.let { old ->
            // Reservoir level became higher - insulin was changed.
            if (cur.reservoirLevel - 2 > old.reservoirLevel && preferences.get(ApexBooleanKey.LogInsulinChange)) {
                pumpSync.insertTherapyEventIfNewWithTimestamp(
                    timestamp = System.currentTimeMillis(),
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                    type = TE.Type.INSULIN_CHANGE,
                )
                aapsLogger.debug(LTag.PUMP, "Logged insulin change")
            }
        }
    }

    private fun onSystemStateChanged(v1: StatusV1) {
        if (v1.isLocked == true) {
            uiInteraction.addNotification(
                Notification.PUMP_IS_LOCKED,
                rh.gs(R.string.pump_is_locked),
                Notification.URGENT,
            )
        } else {
            uiInteraction.dismissNotification(Notification.PUMP_IS_LOCKED)
        }
    }

    private fun onStatusV1(status: StatusV1) {
        val update = pump.updateFromV1(status)
        aapsLogger.debug(LTag.PUMPCOMM, "Got V1 | Status updates: ${update.changes.joinToString(", ") { it.name }}")

        preferences.put(ApexDoubleKey.MaxBasal, update.current.maxBasal)
        preferences.put(ApexDoubleKey.MaxBolus, update.current.maxBolus)
        apexPumpPlugin.updatePumpDescription()

        onAlarmsChanged(update)
        onBasalChanged(update)
        onReservoirChanged(update)
        onSystemStateChanged(status)

        // We may retrieve the forgotten in V1 alarm length from V2.
        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) == false) {
            onSettingsChanged(update)
            onBatteryChanged(update)
        }

        rxBus.send(EventApexPumpDataChanged())
    }

    private fun onStatusV2(status: StatusV2) {
        val update = pump.updateFromV2(status)
        aapsLogger.debug(LTag.PUMPCOMM, "Got V2 | Status updates: ${update.changes.joinToString(", ") { it.name }}")

        preferences.put(ApexStringKey.AlarmSoundLength, status.alarmLength!!.name)

        onSettingsChanged(update)
        onBatteryChanged(update)

        rxBus.send(EventApexPumpDataChanged())
    }

    private fun onHeartbeat() {
        aapsLogger.debug(LTag.PUMPCOMM, "Got heartbeat")
        if (connectionStatus == ApexBluetooth.Status.DISCONNECTED) {
            aapsLogger.error(LTag.PUMPCOMM, "BUG: Got heartbeat but pump is disconnected!")
            return
        }

        status.addAction(ApexDriverStatus.Action.Heartbeat, R.string.action_heartbeat)
        if (!getStatus("HeartbeatHandler")) return status.removeAction(ApexDriverStatus.Action.Heartbeat)
        if (!getBoluses("HeartbeatHandler")) return status.removeAction(ApexDriverStatus.Action.Heartbeat)
        status.removeAction(ApexDriverStatus.Action.Heartbeat)

        status.updateConnectionState(ApexDriverStatus.ConnectionState.Connected)
    }

    private fun onVersion(version: Version) {
        aapsLogger.debug(LTag.PUMPCOMM, "Got version - $version")
    }

    @Synchronized
    private fun onBolusEntry(entry: BolusEntry) {
        // Extended bolus entries do not have duration stored, do not use them.
        if (entry.extendedDose > 0) return

        aapsLogger.debug(LTag.PUMP, "Processing bolus [${entry.standardDose * 0.025}U -> ${entry.standardPerformed * 0.025}U] on ${entry.dateTime}")

        if (entry.dateTime > lastBolusDateTime) {
            lastBolusDateTime = entry.dateTime
            pump.lastBolus = entry
            rxBus.send(EventApexPumpDataChanged())
        }

        // Find the bolus in history and sync it.
        // Pump may round up boluses, use 0.11 for failsafe.
        pump.inProgressBolus?.let {
            val delta = abs(entry.dateTime.millis - it.temporaryId)
            // Pump saves all boluses like they were issued on the 59th second of minute.
            // Considering that in the condition.
            if (delta <= 1000 || (delta > 59000 && delta < 61000)) {
                aapsLogger.debug(LTag.PUMP, "Syncing current bolus [${entry.standardDose * 0.025}U -> ${entry.standardPerformed * 0.025}U]")
                val deltaU = abs(entry.standardDose * 0.025 - if (it.useFallbackDose) it.requestedDose else it.currentDose)
                if (!it.cancelled && deltaU > 0.11) {
                    aapsLogger.debug(LTag.PUMP, "Not this bolus: $delta > 0.11")
                    return
                }

                val syncResult = pumpSync.syncBolusWithTempId(
                    timestamp = entry.dateTime.millis,
                    temporaryId = it.temporaryId,
                    amount = entry.standardPerformed * 0.025,
                    pumpId = entry.dateTime.millis,
                    pumpType = PumpType.APEX_TRUCARE_III,
                    pumpSerial = apexDeviceInfo.serialNumber,
                    type = it.detailedBolusInfo.bolusType,
                )
                aapsLogger.debug(LTag.PUMP, "Final bolus [${entry.standardDose * 0.025}U -> ${entry.standardPerformed * 0.025}U] sync succeeded? $syncResult")
                synchronized(pump.inProgressBolus!!) {
                    pump.inProgressBolus!!.notifyAll()
                }
                SystemClock.sleep(100)
                pump.inProgressBolus = null

                getStatus("ApexService-updateAfterBolus")
                return
            }
            if (entry.index < 2) return
        }

        // Otherwise, just sync the bolus with the DB
        pumpSync.syncBolusWithPumpId(
            timestamp = entry.dateTime.millis,
            pumpId = entry.dateTime.millis,
            amount = entry.standardPerformed * 0.025,
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
            type = null,
        )
        aapsLogger.debug(LTag.PUMP, "Synced bolus ${entry.standardPerformed * 0.025}U on ${entry.dateTime}")
    }

    // !! Unreliable on 6.25 firmware, TODO: think about solution
    private fun onTDDEntry(entry: TDDEntry) {
        // Ignore unreliable TDDs on 6.25 and older FWs
        if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) != true) return

        pumpSync.createOrUpdateTotalDailyDose(
            timestamp = entry.dateTime.millis,
            pumpId = entry.dateTime.millis,
            pumpType = PumpType.APEX_TRUCARE_III,
            pumpSerial = apexDeviceInfo.serialNumber,
            bolusAmount = entry.bolus * 0.025,
            basalAmount = entry.basal * 0.025 + entry.temporaryBasal * 0.025,
            totalAmount = entry.total * 0.025,
        )
        aapsLogger.debug(LTag.PUMP, "Synced TDD ${entry.total * 0.025}U on ${entry.dateTime}")
    }

    //////// BLE

    private fun onInitialConnection() {
        preferences.put(ApexDoubleKey.MaxBasal, 0.0)
        preferences.put(ApexDoubleKey.MaxBolus, 0.0)
        pumpSync.connectNewPump()

        setConnectionProfile("ApexService-onInitialConnection")
    }

    fun startConnection() {
        if (apexDeviceInfo.serialNumber.isEmpty()) return
        manualDisconnect = false
        apexBluetooth.connect()
    }

    private var lastDisconnect = 0L
    fun disconnect(isReconnect: Boolean = false) {
        if (SystemClock.uptimeMillis() - lastDisconnect < 15000 && isReconnect) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Last disconnect was not long ago, skipping this one")
            return
        }

        lastDisconnect = SystemClock.uptimeMillis()
        manualDisconnect = !isReconnect
        apexBluetooth.disconnect()
        if (isReconnect)
            apexBluetooth.connect()
        SystemClock.sleep(50)
        if (connectionFinished) connectionFinished = false
    }


    override fun onConnect() {
        try {
            aapsLogger.debug(LTag.PUMPCOMM, "onConnect")
            status.addAction(ApexDriverStatus.Action.Initializing, R.string.action_initializing)

            connectionId++

            val prefFw = FirmwareVersion.valueOf(preferences.get(ApexStringKey.FirmwareVer))
            if (prefFw == FirmwareVersion.AUTO) {
                status.addAction(ApexDriverStatus.Action.GettingVersion, R.string.action_getting_version)
                val version = getValue(GetValue.Value.Version)?.firstOrNull()
                status.removeAction(ApexDriverStatus.Action.GettingVersion)

                if (version !is Version) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to get version - disconnecting.")
                    return disconnect(true)
                }

                aapsLogger.debug(LTag.PUMPCOMM, version.toString())
                pump.firmwareVersion = version
            } else {
                pump.firmwareVersion = Version(prefFw.major, prefFw.minor, prefFw.protocolVersion)
                aapsLogger.debug(LTag.PUMPCOMM, "Manual version: ${prefFw.name}")
            }

            val version = pump.firmwareVersion!!
            if (!version.isSupported(FIRST_SUPPORTED_PROTO, LAST_SUPPORTED_PROTO)) {
                aapsLogger.error(LTag.PUMPCOMM, "Unsupported protocol v${version.protocolMajor}.${version.protocolMinor} - disconnecting.")
                uiInteraction.addNotification(
                    Notification.PUMP_ERROR,
                    rh.gs(R.string.notification_pump_unsupported),
                    Notification.URGENT,
                )
                return disconnect()
            }

            onVersion(version)

            if (!syncDateTime("BLE-onConnect")) {
                aapsLogger.error(LTag.PUMPCOMM, "Failed to sync date and time - disconnecting.")
                return disconnect(true)
            }

            if (apexDeviceInfo.serialNumber != preferences.get(ApexStringKey.LastConnectedSerialNumber)) {
                onInitialConnection()
                preferences.put(ApexStringKey.LastConnectedSerialNumber, apexDeviceInfo.serialNumber)
            }

            // Do a fast reconnect on failed commands
            if (!doNotReconnect || pump.inProgressBolus != null) {
                if (!getStatus("BLE-onConnect")) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to get status - disconnecting.")
                    return disconnect(true)
                }
                if (!getBoluses("BLE-onConnect")) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to get boluses - disconnecting.")
                    return disconnect(true)
                }
            }

            if (pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_10) == true) {
                if (!requestHeartbeat("BLE-onConnect")) {
                    aapsLogger.error(LTag.PUMPCOMM, "Failed to notify about connection - disconnecting.")
                    return disconnect(true)
                }
            } else {
                spawnHeartbeatLoop()
            }

            unreachableTimerTask?.cancel()
            unreachableTimerTask = null
            status.updateConnectionState(ApexDriverStatus.ConnectionState.Connected)
            pump.isInitialized = true
            connectionFinished = true
            doNotReconnect = false
        } finally {
            status.removeAction(ApexDriverStatus.Action.Initializing)
        }
    }

    @Synchronized
    private fun spawnHeartbeatLoop() {
        Thread {
            val savedConnectionId = connectionId

            while (true) {
                val now = DateTime.now()
                val msTillNextMinute = now.withSecondOfMinute(5).plus(HEARTBEAT_PERIOD_MINUTES * 60000L).millis - now.millis
                SystemClock.sleep(msTillNextMinute)

                if (connectionStatus != ApexBluetooth.Status.CONNECTED) {
                    aapsLogger.debug(LTag.PUMPCOMM, "Pump has been disconnected. Stopping thread")
                    return@Thread
                }
                if (connectionId != savedConnectionId) {
                    aapsLogger.debug(LTag.PUMPCOMM, "Incorrect connection ID. Stopping thread")
                    return@Thread
                }

                aapsLogger.debug(LTag.PUMPCOMM, "Triggering fake heartbeat")
                onHeartbeat()
            }
        }.start()
    }

    private var isDisconnectLoopRunning = false
    private fun spawnLoop() {
        if (isDisconnectLoopRunning) return
        isDisconnectLoopRunning = true
        Thread {
            while (connectionStatus != ApexBluetooth.Status.CONNECTED && !manualDisconnect) {
                if (connectionStatus == ApexBluetooth.Status.DISCONNECTED) {
                    aapsLogger.debug(LTag.PUMPCOMM, "Starting connection loop")
                    if (!doNotReconnect) startConnection()
                }
                SystemClock.sleep(100)
            }

            if (manualDisconnect) {
                aapsLogger.debug(LTag.PUMPCOMM, "Manual disconnect detected!")
                disconnect()
            }

            aapsLogger.debug(LTag.PUMPCOMM, "Exiting")
            isDisconnectLoopRunning = false
        }.start()
    }

    override fun onDisconnect() {
        aapsLogger.debug(LTag.PUMPCOMM, "onDisconnect")
        pump.inProgressBolus?.lockHistory = false
        pump.inProgressBolus?.useFallbackDose = true
        connectionFinished = false

        isGetThreadRunning = false
        synchronized(getValueResult) {
            getValueResult.waiting = false
            getValueResult.notifyAll()
        }
        synchronized(commandResponse) {
            commandResponse.waiting = false
            commandResponse.notifyAll()
        }


        lastConnectedTimestamp = System.currentTimeMillis()

        if (unreachableTimerTask == null)
            unreachableTimerTask = timer.schedule(120000) {
                uiInteraction.addNotification(
                    Notification.PUMP_UNREACHABLE,
                    rh.gs(R.string.error_pump_unreachable),
                    Notification.URGENT,
                )
                aapsLogger.error(LTag.PUMP, "Pump unreachable!")
            }

        if (!manualDisconnect) spawnLoop()
    }

    private fun createSpecialModeAlarm() {
        uiInteraction.runAlarm(
            rh.gs(R.string.bolus_error_pump_special_mode),
            rh.gs(R.string.pump_special_mode),
            app.aaps.core.ui.R.raw.boluserror
        )
    }

    private var isGetThreadRunning = false
    override fun onPumpCommand(command: PumpCommand) {
        if (command.id == null) {
            aapsLogger.error(LTag.PUMPCOMM, "Invalid command with crc ${command.checksum}")
            return
        }

        val type = PumpObject.findObject(command.id!!, command.objectData, aapsLogger)
        aapsLogger.debug(LTag.PUMPCOMM, "from PUMP: ${command.id!!.name}, ${type?.name}")

        if (type == null) return
        if (type == PumpObject.CommandResponse) return onCommandResponse(CommandResponse(command))

        val obj = when (type) {
            PumpObject.Heartbeat       -> Heartbeat()
            PumpObject.StatusV1        -> StatusV1(command, apexDeviceInfo)
            PumpObject.StatusV2        -> StatusV2(command)
            PumpObject.BasalProfile    -> BasalProfile(command)
            PumpObject.AlarmEntry      -> AlarmObject(command, apexDeviceInfo)
            PumpObject.TDDEntry        -> TDDEntry(command, apexDeviceInfo)
            PumpObject.BolusEntry      -> BolusEntry(command, apexDeviceInfo)
            PumpObject.FirmwareEntry   -> Version(command)
            else                       -> return
        }
        val validationMsg = obj.validate()
        if (validationMsg != null) {
            aapsLogger.error(LTag.PUMPCOMM, "Got invalid (reason: $validationMsg) object of type $type - ${command.objectData.toHex()}")
            if (config.isEngineeringMode()) {
                uiInteraction.addNotification(
                    Notification.APEX_SERVICE_MSG,
                    "Invalid object data ($type, $validationMsg) - please send log to developers!",
                    Notification.URGENT,
                )
            }
            return
        }

        notifyAboutResponse(command, obj, type)
        Thread { processObject(obj) }.start()
    }

    private fun processObject(obj: PumpObjectModel) {
        when (obj) {
            is StatusV1 -> onStatusV1(obj)
            is StatusV2 -> onStatusV2(obj)
            is Heartbeat -> onHeartbeat()
            is BolusEntry -> onBolusEntry(obj)
            is TDDEntry -> onTDDEntry(obj)
            else -> {}
        }
    }

    @Synchronized
    private fun notifyAboutResponse(command: PumpCommand, obj: PumpObjectModel, type: PumpObject) {
        if (!getValueResult.waiting) {
            aapsLogger.debug(LTag.PUMPCOMM, "Got pump command but not waiting for it")
            return
        }
        if (type != getValueResult.targetObject) {
            aapsLogger.debug(LTag.PUMPCOMM, "Got incorrect object type (${type.name} vs ${getValueResult.targetObject?.name})")
            return
        }

        // Pump may send fake bolus entry if there are no boluses in history.
        // We shouldn't handle it.
        if (command.objectData[2].toUInt().toInt() == 0xFF && command.objectData[3].toUInt().toInt() == 0xFF) {
            aapsLogger.debug(LTag.PUMPCOMM, "Got fake bolus entry - skipping")
            getValueResult.waiting = false
            getValueResult.response = arrayListOf()
            synchronized(getValueResult) {
                getValueResult.notifyAll()
            }
            return
        }

        getValueResult.add(obj)
        if (getValueResult.isSingleObject) {
            aapsLogger.debug(LTag.PUMPCOMM, "Got single value - everything is ready")
            getValueResult.waiting = false
            synchronized(getValueResult) {
                getValueResult.notifyAll()
            }
        } else {
            aapsLogger.debug(LTag.PUMPCOMM, "Updating last timestamp")
            getValueLastTaskTimestamp = System.currentTimeMillis()
            runGetThread()
        }
    }

    @Synchronized
    private fun runGetThread() {
        if (isGetThreadRunning) return
        isGetThreadRunning = true
        aapsLogger.debug(LTag.PUMPCOMM, "Running GET thread")
        Thread {
            while (isGetThreadRunning) {
                val now = System.currentTimeMillis()
                if (now - getValueLastTaskTimestamp >= 500) {
                    break
                } else {
                    aapsLogger.debug(LTag.PUMPCOMM, "Response is not ready yet")
                }
                SystemClock.sleep(100)
            }
            if (!isGetThreadRunning) {
                aapsLogger.debug(LTag.PUMPCOMM, "GET thread killed")
                return@Thread
            }
            isGetThreadRunning = false

            aapsLogger.debug(LTag.PUMPCOMM, "Chunked response has completed")
            getValueResult.waiting = false
            synchronized(getValueResult) {
                getValueResult.notifyAll()
            }
        }.start()
        // Let thread start
        SystemClock.sleep(50)
    }

    //////// Binder

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder {
        aapsLogger.debug(LTag.PUMP, "Binding service")
        return binder
    }

    inner class LocalBinder : Binder() {
        val serviceInstance: ApexService
            get() = this@ApexService
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        aapsLogger.debug(LTag.PUMP, "Service started")
        return START_STICKY
    }
}