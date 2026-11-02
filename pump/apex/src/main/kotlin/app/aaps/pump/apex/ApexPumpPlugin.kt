package app.aaps.pump.apex

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.DoseStepSize
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.PumpPluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.pump.mapState
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.keys.interfaces.ElementVisibility
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.withEntries
import app.aaps.core.ui.compose.icons.IcPatchPump
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.utils.waitMillis
import app.aaps.pump.apex.compose.ApexComposeContent
import app.aaps.pump.apex.connectivity.ApexBluetooth
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.misc.BatteryType
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import app.aaps.pump.apex.utils.keys.ApexStringNonKey
import app.aaps.pump.apex.utils.keys.withVisibility
import app.aaps.pump.apex.utils.toApexReadableProfile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToLong
import app.aaps.pump.common.R as PumpCommonR

/**
 * Apex TruCare III driver.
 *
 * Work in progress, ported from dev_OAPSAIMI, where it was never wired into the app either. On
 * purpose this class is NOT registered as a pump driver: it carries no `@ContributesIntoMap` /
 * `@PumpDriver`, and `:pump:apex` is excluded from the app (`pumpExclusions` in app/build.gradle.kts).
 * So no user can select it. Wiring it in needs a code review of the driver and tests with a real pump
 * first. Android only: there is no CoreBluetooth driver for this pump.
 *
 * @author Roman Rikhter (teledurak@gmail.com)
 */
@SingleIn(AppScope::class)
@Inject
class ApexPumpPlugin(
    aapsLogger: AAPSLogger,
    override val rh: ResourceHelper,
    commandQueue: CommandQueue,
    private val context: Context,
    preferences: Preferences,
    private val rxBus: RxBus,
    private val pump: ApexPump,
    private val config: Config,
    private val pumpEnactResultProvider: () -> PumpEnactResult,
    notificationManager: NotificationManager
) : PumpPluginBase(
    pluginDescription = PluginDescription()
        .mainType(PluginType.PUMP)
        .composeContent { _ -> ApexComposeContent(pluginName = rh.gs(R.string.apex_plugin_name)) }
        .icon(IcPatchPump)
        .pluginName(TextRef.AndroidRes(R.string.apex_plugin_name))
        .description(TextRef.AndroidRes(R.string.apex_plugin_description)),
    ownPreferences = ApexBooleanKey.entries + ApexDoubleKey.entries + ApexStringKey.entries + ApexStringNonKey.entries,
    aapsLogger, rh, preferences, commandQueue, notificationManager
), Pump, PumpPluginConstraints {

    private var scope: CoroutineScope? = null

    private var service: ApexService? = null
    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName) {
            aapsLogger.debug(LTag.PUMP, "Service is disconnected")
            service = null
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            aapsLogger.debug(LTag.PUMP, "Service is connected")
            val mLocalBinder = service as ApexService.LocalBinder
            this@ApexPumpPlugin.service = mLocalBinder.serviceInstance.also {
                it.startConnection()
            }
        }
    }

    override suspend fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "Starting APEX plugin")
        context.bindService(Intent(context, ApexService::class.java), connection, Context.BIND_AUTO_CREATE)
        // Own scope on IO, cancelled in onStop. UNDISPATCHED because RxBus has no replay, so a
        // scheduled collector could miss an exit sent before it starts.
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        rxBus.toFlow(EventAppExit::class)
            .collectResilient(newScope, aapsLogger, LTag.PUMP, start = CoroutineStart.UNDISPATCHED) { context.unbindService(connection) }
    }

    override suspend fun onStop() {
        aapsLogger.debug(LTag.PUMP, "Stopping APEX plugin")
        service?.disconnect()
        scope?.cancel()
        scope = null
        context.unbindService(connection)
        // onServiceDisconnected is not called after unbindService, so drop the reference here.
        service = null
        super.onStop()
    }

    override val lastDataTime: StateFlow<Long> = pump.statusFlow.mapState { it?.dateTime?.millis ?: 0L }
    override val lastBolusTime: StateFlow<Long?> = pump.lastBolusFlow.mapState { it?.dateTime?.millis }
    override val lastBolusAmount: StateFlow<PumpInsulin?> =
        pump.lastBolusFlow.mapState { it?.let { bolus -> PumpInsulin((bolus.standardPerformed + bolus.extendedPerformed) * 0.025) } }
    override val reservoirLevel: StateFlow<PumpInsulin> = pump.statusFlow.mapState { PumpInsulin(it?.reservoirLevel ?: 0.0) }
    override val batteryLevel: StateFlow<Int?> = pump.statusFlow.mapState { it?.batteryLevel?.percentage }

    override val isFakingTempsByExtendedBoluses = false
    override fun canHandleDST() = false

    override fun manufacturer() = ManufacturerType.Apex
    override fun model() = PumpType.APEX_TRUCARE_III
    override fun serialNumber() = preferences.get(ApexStringNonKey.LastConnectedSerialNumber)

    override val baseBasalRate: PumpRate
        get() = PumpRate(pump.basal?.rate ?: 0.0)
    override val pumpDescription = PumpDescription().fillFor(model())

    override fun isBusy() = false
    override fun isSuspended() = pump.isSuspended
    override fun isInitialized() = pump.isInitialized && service != null
    override fun isConnecting() = when (service?.connectionStatus) {
        ApexBluetooth.Status.CONNECTING -> true
        ApexBluetooth.Status.CONNECTED  -> service?.isReadyForExecutingCommands == false
        else                            -> false
    }

    override fun isHandshakeInProgress() = false
    override fun isConnected() = service?.connectionStatus == ApexBluetooth.Status.CONNECTED && service?.isReadyForExecutingCommands == true
    override fun isBatteryChangeLoggingEnabled() = preferences.get(ApexBooleanKey.LogBatteryChange)

    // The driver stays connected to the pump all the time.
    override fun connect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Triggered connect: $reason")
        if (service?.apexBluetooth?.status == ApexBluetooth.Status.DISCONNECTED) {
            service?.startConnection()
        }
    }

    override fun disconnect(reason: String) {
        aapsLogger.debug(LTag.PUMP, "Triggered disconnect: $reason")
    }

    override fun stopConnecting() {
        aapsLogger.debug(LTag.PUMP, "Triggered stopConnecting")
    }

    // Pump limits in pump units. Applied only once the pump has reported them: 0 means not known yet,
    // and a limit of 0 would block every dose. The service also refuses a dose above them.
    override fun applyBolusConstraints(insulin: PumpInsulin): PumpInsulin =
        if (pump.maxBolus > 0) PumpInsulin(insulin.cU.coerceAtMost(pump.maxBolus)) else insulin

    override fun applyBasalConstraints(absoluteRate: PumpRate): PumpRate =
        if (pump.maxBasal > 0) PumpRate(absoluteRate.cU.coerceAtMost(pump.maxBasal)) else absoluteRate

    override fun pumpSpecificShortStatus(veryShort: Boolean): String {
        val service = service
        val status = pump.status
        if (!isInitialized() || !isConnected() || service == null || status == null) return rh.gs(PumpCommonR.string.pump_status_not_initialized)

        val ret = rh.gs(
            R.string.short_status,
            service.connectionStatus.toLocalString(rh),
            status.getPumpStatus(rh),
            pump.lastBolus?.toShortLocalString(rh) ?: "-",
            status.getTBR(rh),
            status.getBasal(rh),
            status.getReservoirLevel(rh),
            status.getBatteryLevel(rh)
        )
        aapsLogger.debug(LTag.PUMP, "Short status: $ret")
        return ret
    }

    fun updatePumpDescription() {
        aapsLogger.debug(LTag.PUMP, "Updating pump description")
        pumpDescription.maxTempAbsolute = pump.maxBasal
        pumpDescription.basalMaximumRate = pump.maxBasal
    }

    private fun failed(comment: Int): PumpEnactResult =
        pumpEnactResultProvider().success(false).enacted(false).comment(rh.gs(comment))

    override suspend fun loadTDDs(): PumpEnactResult {
        val service = service
        if (!isInitialized() || !isConnected() || service == null) return failed(R.string.error_not_ready)

        val status = service.getTDDs("ApexPumpPlugin-loadTDDs")
        return pumpEnactResultProvider().success(status).enacted(status)
    }

    override suspend fun getPumpStatus(reason: String) {
        val service = service
        if (!isInitialized() || !isConnected() || service == null) return
        aapsLogger.debug(LTag.PUMP, "Requested pump status cause of $reason")
        service.getStatus("ApexPumpPlugin-getPumpStatus")
    }

    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        val service = service
        if (!isInitialized() || !isConnected() || service == null) return failed(R.string.error_not_ready)

        if (!service.updateBasalPatternIndex(ApexService.USED_BASAL_PATTERN_INDEX, "ApexPumpPlugin-setNewBasalProfile"))
            return failed(R.string.error_failed_to_switch_basal_profile_index)

        if (!service.updateCurrentBasalPattern(profile.toApexReadableProfile(), "ApexPumpPlugin-setNewBasalProfile"))
            return failed(R.string.error_failed_to_update_basal_profile)

        return pumpEnactResultProvider().success(true).enacted(true)
    }

    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        val service = service
        if (!isInitialized() || !isConnected() || service == null) return true
        val pumpBasalProfiles = service.getBasalProfiles("ApexPumpPlugin-isThisProfileSet") ?: return true
        val pumpBasalProfile = pumpBasalProfiles[ApexService.USED_BASAL_PATTERN_INDEX] ?: return true
        for (i in 0..<48) {
            val profileBasal = profile.getBasalTimeFromMidnight(i * 30 * 60)
            val pumpBasal = pumpBasalProfile[i]
            val precision = DoseStepSize.Apex.getStepSizeForAmount(profileBasal) / 2
            if (abs(pumpBasal - profileBasal) > precision) {
                aapsLogger.info(LTag.PUMP, "Profiles are not same: req $profileBasal != pump $pumpBasal, block $i, time ${i * 30 * 60}")
                return false
            }
        }
        return true
    }

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        // Insulin value must be greater than 0
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }
        // Already constrained in IU (queue) and in cU (PumpWithConcentration boundary); no re-apply here.

        val service = service
        if (!isInitialized() || !isConnected() || service == null) return failed(R.string.error_not_ready)
        if (isSuspended()) return failed(R.string.error_pump_suspended)

        if (!service.bolus(detailedBolusInfo, "ApexPumpPlugin-deliverTreatment")) return failed(R.string.error_bolus_start_failed)

        // We send max of 3 commands for bolus: StatusV1, StatusV2, Bolus
        // Max wait time for any of them is 2.5s => 7.5s in total.
        // We may reconnect pump in order to try to fix connection issues. This takes ~45s.
        // Pump sets boluses in steps of 0.025U/s for boluses <=1U, 0.05U/s for boluses >1U.
        val maxReasonableBolusTime = (
            if (detailedBolusInfo.insulin <= 1.0)
                detailedBolusInfo.insulin / 0.025
            else
                detailedBolusInfo.insulin / 0.05
            ).roundToLong() + 45 + 8
        pump.inProgressBolus?.let {
            synchronized(it) {
                it.waitMillis(maxReasonableBolusTime * 1000)
            }
        }

        val bolus = pump.inProgressBolus
        val successful = bolus != null && !bolus.failed
        val result = pumpEnactResultProvider().success(successful)
        if (bolus != null && successful) {
            result.enacted = bolus.currentDose > 0.024
            result.bolusDelivered = bolus.currentDose
        }
        return result
    }

    override fun stopBolusDelivering() {
        val service = service
        if (!isInitialized() || !isConnected() || service == null) return
        service.cancelBolus("ApexPumpPlugin-stopBolusDelivering")
    }

    override suspend fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        // Already constrained by the queue. The pump takes 15 minute steps.
        val duration = durationInMinutes - durationInMinutes % 15

        val service = service
        val status = pump.status
        if (!isInitialized() || !isConnected() || service == null || status == null) return failed(R.string.error_not_ready)
        if (isSuspended()) return failed(R.string.error_pump_suspended)

        if (enforceNew && status.tbr != null) {
            if (!service.cancelTemporaryBasal("ApexPumpPlugin-setTempBasal")) return failed(R.string.error_tbr_cancel_failed)
        }

        if (!service.temporaryBasal(absoluteRate, duration, tbrType, "ApexPumpPlugin-setTempBasal")) return failed(R.string.error_tbr_set_failed)

        return pumpEnactResultProvider().success(true).enacted(true)
    }

    override suspend fun setTempBasalPercent(percent: Int, durationInMinutes: Int, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult =
        failed(R.string.error_only_absolute_supported)

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val service = service
        if (!isInitialized() || !isConnected() || service == null) return failed(R.string.error_not_ready)
        if (isSuspended()) return failed(R.string.error_pump_suspended)

        if (!service.cancelTemporaryBasal("ApexPumpPlugin-cancelTempBasal")) return failed(R.string.error_tbr_cancel_failed)

        return pumpEnactResultProvider().success(true).enacted(true)
    }

    // Extended bolus is not supported yet.
    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult = failed(R.string.error_not_ready)

    override suspend fun cancelExtendedBolus(): PumpEnactResult = failed(R.string.error_not_ready)

    override suspend fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        val service = service
        if (!isInitialized() || !isConnected() || service == null) return
        service.syncDateTime("ApexService-timezoneOrDSTChanged")
    }

    /** Battery settings are only offered when the pump reports the voltage (protocol 4.11 and newer). */
    private val reportsVoltage = ElementVisibility { pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) == true }
    private val calculatesPercentage = ElementVisibility { reportsVoltage.isVisible(it) && it.preferences.get(ApexBooleanKey.CalculateBatteryPercentage) }
    private val customVoltage = ElementVisibility { calculatesPercentage.isVisible(it) && it.preferences.get(ApexStringKey.CalcBatteryType) == BatteryType.Custom.name }

    override fun getPreferenceScreenContent(): PreferenceSubScreenDef {
        // Versions for engineering mode only are offered only there, as on dev.
        val versions = FirmwareVersion.realValues.filter { !it.engineeringModeOnly || config.isEngineeringMode() }
        val firmwareEntries = mapOf(FirmwareVersion.AUTO.name to TextRef.AndroidRes(R.string.auto)) +
            versions.associate { it.name to TextRef.Literal(it.displayName) }

        return PreferenceSubScreenDef(
            key = "apex_settings",
            titleResId = R.string.apex_settings,
            items = listOf(
                ApexStringKey.SerialNumber,
                ApexStringKey.FirmwareVer.withEntries(firmwareEntries),
                ApexStringKey.AlarmSoundLength,
                ApexBooleanKey.CalculateBatteryPercentage.withVisibility(reportsVoltage),
                ApexStringKey.CalcBatteryType.withVisibility(calculatesPercentage),
                ApexDoubleKey.BatteryLowVoltage.withVisibility(customVoltage),
                ApexDoubleKey.BatteryHighVoltage.withVisibility(customVoltage),
                ApexDoubleKey.MaxBasal,
                ApexDoubleKey.MaxBolus,
                ApexBooleanKey.LogInsulinChange,
                ApexBooleanKey.LogBatteryChange,
                ApexBooleanKey.HideSerial
            ),
            icon = pluginDescription.icon
        )
    }
}
