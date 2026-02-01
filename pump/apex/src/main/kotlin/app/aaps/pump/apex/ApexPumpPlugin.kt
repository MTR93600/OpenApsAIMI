package app.aaps.pump.apex

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.DoseStepSize
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.constraints.PluginConstraints
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpPluginBase
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventAppExit
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.constraints.ConstraintObject
import app.aaps.core.utils.waitMillis
import app.aaps.core.validators.preferences.AdaptiveDoublePreference
import app.aaps.core.validators.preferences.AdaptiveListPreference
import app.aaps.core.validators.preferences.AdaptiveStringPreference
import app.aaps.core.validators.preferences.AdaptiveSwitchPreference
import app.aaps.pump.apex.connectivity.ApexBluetooth
import app.aaps.pump.apex.connectivity.FirmwareVersion
import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.connectivity.commands.pump.AlarmLength
import app.aaps.pump.apex.misc.BatteryType
import app.aaps.pump.apex.ui.ApexFragment
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import app.aaps.pump.apex.utils.toApexReadableProfile
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import org.json.JSONException

import org.json.JSONObject
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * @author Roman Rikhter (teledurak@gmail.com)
 */
class ApexPumpPlugin @Inject constructor(
    aapsLogger: AAPSLogger,
    rh: ResourceHelper,
    commandQueue: CommandQueue,
    val context: Context,
    preferences: Preferences,
    val rxBus: RxBus,
    val aapsSchedulers: AapsSchedulers,
    val fabricPrivacy: FabricPrivacy,
    val dateUtil: DateUtil,
    val pump: ApexPump,
    val config: Config,
    private val constraintsChecker: ConstraintsChecker
): PumpPluginBase(
    PluginDescription()
        .mainType(PluginType.PUMP)
        .fragmentClass(ApexFragment::class.java.name)
        .pluginIcon(R.drawable.ic_apex_detailed)
        .pluginName(R.string.apex_plugin_name)
        .shortName(R.string.apex_plugin_shortname)
        .description(R.string.apex_plugin_description)
        .preferencesId(PluginDescription.PREFERENCE_SCREEN),
    emptyList(),
    aapsLogger,
    rh,
    preferences,
    commandQueue
), Pump, PluginConstraints {

    init {
        preferences.registerPreferences(ApexBooleanKey::class.java)
        preferences.registerPreferences(ApexDoubleKey::class.java)
        preferences.registerPreferences(ApexStringKey::class.java)
    }

    private val disposable = CompositeDisposable()

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


    override fun onStart() {
        super.onStart()
        aapsLogger.debug(LTag.PUMP, "Starting APEX plugin")
        context.bindService(Intent(context, ApexService::class.java), connection, Context.BIND_AUTO_CREATE)
        disposable += rxBus
            .toObservable(EventAppExit::class.java)
            .observeOn(aapsSchedulers.io)
            .subscribe({ context.unbindService(connection) }, fabricPrivacy::logException)
    }

    override fun onStop() {
        aapsLogger.debug(LTag.PUMP, "Stopping APEX plugin")
        service?.disconnect()
        context.unbindService(connection)
        disposable.clear()
        super.onStop()
    }

    override val lastDataTime: Long
        get() = pump.dateTime.millis

    override val lastBolusTime: Long?
        get() = pump.lastBolus?.dateTime?.millis

    override val lastBolusAmount: Double?
        get() = pump.lastBolus?.let { (it.standardPerformed + it.extendedPerformed) * 0.025 }

    override val isFakingTempsByExtendedBoluses = false
    override fun canHandleDST() = false

    override fun manufacturer() = ManufacturerType.Apex
    override fun model() = PumpType.APEX_TRUCARE_III
    override fun serialNumber() = preferences.get(ApexStringKey.LastConnectedSerialNumber)

    override val baseBasalRate: Double
        get() = pump.basal?.rate ?: 0.0
    override val reservoirLevel: Double
        get() = pump.reservoirLevel
    override val batteryLevel: Int
        get() = pump.batteryLevel.percentage
    override val pumpDescription = PumpDescription().fillFor(model())

    override fun isBusy() = false //service?.isBusy ?: false
    override fun isSuspended() = pump.isSuspended
    override fun isInitialized() = pump.isInitialized && service != null
    override fun isConnecting() = when (service?.connectionStatus) {
        ApexBluetooth.Status.CONNECTING -> true
        ApexBluetooth.Status.CONNECTED -> service?.isReadyForExecutingCommands == false
        else -> false
    }
    override fun isHandshakeInProgress() = false
    override fun isConnected() = service?.connectionStatus == ApexBluetooth.Status.CONNECTED && service?.isReadyForExecutingCommands == true
    override fun isBatteryChangeLoggingEnabled() = preferences.get(ApexBooleanKey.LogBatteryChange)


    // We should be always connected to the pump.
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



    override fun pumpSpecificShortStatus(veryShort: Boolean): String {
        if (!isInitialized() || !isConnected()) return rh.gs(app.aaps.pump.common.R.string.pump_status_not_initialized)
        val status = pump.status!!

        val ret = "${rh.gs(R.string.status_conn_status)}: ${service!!.connectionStatus.toLocalString(rh)}\n" +
            "${rh.gs(R.string.status_pump_status)}: ${status.getPumpStatus(rh)}\n" +
            "${rh.gs(R.string.status_last_bolus)}: ${pump.lastBolus?.toShortLocalString(rh) ?: "-"}\n" +
            "${rh.gs(R.string.status_tbr)}: ${status.getTBR(rh)}\n" +
            "${rh.gs(R.string.status_basal)}: ${status.getBasal(rh)}\n" +
            "${rh.gs(R.string.status_reservoir)}: ${status.getReservoirLevel(rh)}\n" +
            "${rh.gs(R.string.status_battery)}: ${status.getBatteryLevel(rh)}"

        aapsLogger.debug(LTag.PUMP, "Short status: $ret")
        return ret
    }

    fun updatePumpDescription() {
        aapsLogger.debug(LTag.PUMP, "Updating pump description")
        pumpDescription.maxTempAbsolute = pump.maxBasal
        pumpDescription.basalMaximumRate = pump.maxBasal
    }

    @Synchronized
    override fun loadTDDs(): PumpEnactResult {
        val ret = PumpEnactResultImpl()
        if (!isInitialized() || !isConnected()) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        val status = service!!.getTDDs("ApexPumpPlugin-loadTDDs")
        return ret.apply {
            success = status
            enacted = status
        }
    }

    @Synchronized
    override fun getPumpStatus(reason: String) {
        if (!isInitialized() || !isConnected()) return
        aapsLogger.debug(LTag.PUMP, "Requested pump status cause of $reason")
        if (!service!!.getStatus("ApexPumpPlugin-getPumpStatus")) return
    }

    @Synchronized
    override fun setNewBasalProfile(profile: Profile): PumpEnactResult {
        val ret = PumpEnactResultImpl()
        if (!isInitialized() || !isConnected()) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        var result = service!!.updateBasalPatternIndex(ApexService.USED_BASAL_PATTERN_INDEX, "ApexPumpPlugin-setNewBasalProfile")
        if (!result) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_failed_to_switch_basal_profile_index)
            }
        }

        result = service!!.updateCurrentBasalPattern(profile.toApexReadableProfile(), "ApexPumpPlugin-setNewBasalProfile")
        if (!result) {
            return ret.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_failed_to_update_basal_profile)
            }
        }

        return ret.apply {
            success = true
            enacted = true
        }
    }

    @Synchronized
    override fun isThisProfileSet(profile: Profile): Boolean {
        if (!isInitialized() || !isConnected()) return true
        val pumpBasalProfiles = service!!.getBasalProfiles("ApexPumpPlugin-isThisProfileSet") ?: return true
        val pumpBasalProfile = pumpBasalProfiles[ApexService.USED_BASAL_PATTERN_INDEX]
        for (i in 0..<48) {
            val profileBasal = profile.getBasalTimeFromMidnight(i * 30 * 60)
            val pumpBasal = pumpBasalProfile!![i]
            val precision = DoseStepSize.Apex.getStepSizeForAmount(profileBasal) / 2
            if (abs(pumpBasal - profileBasal) > precision) {
                aapsLogger.info(LTag.PUMP, "Profiles are not same: req $profileBasal != pump $pumpBasal, block $i, time ${i * 30 * 60}")
                return false
            }
        }
        return true
    }

    @Synchronized
    override fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        // Insulin value must be greater than 0
        require(detailedBolusInfo.carbs == 0.0) { detailedBolusInfo.toString() }
        require(detailedBolusInfo.insulin > 0) { detailedBolusInfo.toString() }
        val pumpEnactResult = PumpEnactResultImpl()
        detailedBolusInfo.insulin = constraintsChecker
            .applyBolusConstraints(ConstraintObject(detailedBolusInfo.insulin, aapsLogger))
            .value()

        if (!isInitialized() || !isConnected()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        if (isSuspended()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_pump_suspended)
            }
        }

        val result = service!!.bolus(detailedBolusInfo, "ApexPumpPlugin-deliverTreatment")
        if (!result) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_bolus_start_failed)
            }
        }

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
        return pumpEnactResult.apply {
            success = successful
            if (successful) {
                enacted = bolus.currentDose > 0.024
                bolusDelivered = bolus.currentDose
            }
        }
    }

    @Synchronized
    override fun stopBolusDelivering() {
        if (!isInitialized() || !isConnected()) return
        service!!.cancelBolus("ApexPumpPlugin-stopBolusDelivering")
    }

    @Synchronized
    override fun setTempBasalAbsolute(absoluteRate: Double, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        val pumpEnactResult = PumpEnactResultImpl()
        val rate = constraintsChecker
            .applyBasalConstraints(ConstraintObject(absoluteRate, aapsLogger), profile)
            .value()
        val duration = durationInMinutes - durationInMinutes % 15

        if (!isInitialized() || !isConnected()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        if (isSuspended()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_pump_suspended)
            }
        }

        val status = pump.status!!
        if (enforceNew && status.tbr != null) {
            val result = service!!.cancelTemporaryBasal("ApexPumpPlugin-setTempBasal")
            if (!result) {
                return pumpEnactResult.apply {
                    success = false
                    enacted = false
                    comment = rh.gs(R.string.error_tbr_cancel_failed)
                }
            }
        }

        val result = service!!.temporaryBasal(rate, duration, tbrType, "ApexPumpPlugin-setTempBasal")
        if (!result) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_tbr_set_failed)
            }
        }

        return pumpEnactResult.apply {
            success = true
            enacted = true
        }
    }

    @Synchronized
    override fun setTempBasalPercent(percent: Int, durationInMinutes: Int, profile: Profile, enforceNew: Boolean, tbrType: PumpSync.TemporaryBasalType): PumpEnactResult {
        return PumpEnactResultImpl().apply {
            success = false
            enacted = false
            comment = rh.gs(R.string.error_only_absolute_supported)
        }
    }

    @Synchronized
    override fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val pumpEnactResult = PumpEnactResultImpl()
        if (!isInitialized() || !isConnected()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_not_ready)
            }
        }

        if (isSuspended()) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_pump_suspended)
            }
        }

        val status = service!!.cancelTemporaryBasal("ApexPumpPlugin-cancelTempBasal")
        if (!status) {
            return pumpEnactResult.apply {
                success = false
                enacted = false
                comment = rh.gs(R.string.error_tbr_cancel_failed)
            }
        }

        return pumpEnactResult.apply {
            success = true
            enacted = true
        }
    }

    @Synchronized
    override fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult {
        // Not yet supported
        return PumpEnactResultImpl().apply {
            success = false
            enacted = false
            comment = rh.gs(R.string.error_not_ready)
        }
    }

    @Synchronized
    override fun cancelExtendedBolus(): PumpEnactResult {
        // Not yet supported
        return PumpEnactResultImpl().apply {
            success = false
            enacted = false
            comment = rh.gs(R.string.error_not_ready)
        }
    }

    @Synchronized
    override fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        if (!isInitialized() || !isConnected()) return
        service!!.syncDateTime("ApexService-timezoneOrDSTChanged")
    }

    override fun preprocessPreferences(preferenceFragment: PreferenceFragmentCompat) {
        super.preprocessPreferences(preferenceFragment)

        val is411 = pump.firmwareVersion?.atleastProto(ProtocolVersion.PROTO_4_11) == true
        val isPrecisePercentage = is411 && preferences.get(ApexBooleanKey.CalculateBatteryPercentage)
        val manualVoltage = isPrecisePercentage && preferences.get(ApexStringKey.CalcBatteryType) == BatteryType.Custom.name

        preferenceFragment.findPreference<AdaptiveSwitchPreference>(ApexBooleanKey.CalculateBatteryPercentage.key)?.isVisible = is411
        preferenceFragment.findPreference<AdaptiveListPreference>(ApexStringKey.CalcBatteryType.key)?.isVisible = isPrecisePercentage
        preferenceFragment.findPreference<AdaptiveDoublePreference>(ApexDoubleKey.BatteryLowVoltage.key)?.isVisible = manualVoltage
        preferenceFragment.findPreference<AdaptiveDoublePreference>(ApexDoubleKey.BatteryHighVoltage.key)?.isVisible = manualVoltage
    }

    override fun addPreferenceScreen(preferenceManager: PreferenceManager, parent: PreferenceScreen, context: Context, requiredKey: String?) {
        val versions = FirmwareVersion.realValues.filter {
            if (it.engineeringModeOnly)
                config.isEngineeringMode()
            else
                true
        }

        if (requiredKey != null) return
        val category = PreferenceCategory(context)
        parent.addPreference(category)
        category.apply {
            key = "apex_settings"
            title = rh.gs(R.string.apex_settings)
            initialExpandedChildrenCount = 0
            addPreference(AdaptiveStringPreference(
                ctx = context,
                stringKey = ApexStringKey.SerialNumber,
                title = R.string.setting_serial_number,
            ))
            addPreference(AdaptiveListPreference(
                ctx = context,
                stringKey = ApexStringKey.FirmwareVer,
                title = R.string.firmware_version,
                entries = arrayOf<CharSequence>(rh.gs(R.string.auto)) + versions.map { it.displayName },
                entryValues = arrayOf<CharSequence>(FirmwareVersion.AUTO.name) + versions.map { it.name },
            ))
            addPreference(AdaptiveListPreference(
                ctx = context,
                stringKey = ApexStringKey.AlarmSoundLength,
                title = R.string.setting_alarm_length,
                entries = arrayOf(rh.gs(R.string.setting_alarm_length_long), rh.gs(R.string.setting_alarm_length_medium), rh.gs(R.string.setting_alarm_length_short)),
                entryValues = arrayOf(AlarmLength.Long.name, AlarmLength.Medium.name, AlarmLength.Short.name)
            ))
            addPreference(AdaptiveSwitchPreference(
                ctx = context,
                booleanKey = ApexBooleanKey.CalculateBatteryPercentage,
                title = R.string.setting_calc_battery_title,
                summary = R.string.setting_calc_battery_summary
            ))
            addPreference(AdaptiveListPreference(
                ctx = context,
                stringKey = ApexStringKey.CalcBatteryType,
                title = R.string.setting_calc_battery_type_title,
                entries = arrayOf(rh.gs(R.string.setting_calc_battery_type_alkaline), rh.gs(R.string.setting_calc_battery_type_lithium), rh.gs(R.string.setting_calc_battery_type_ni_mh), rh.gs(R.string.setting_calc_battery_type_ni_zn), rh.gs(R.string.setting_calc_battery_type_custom)),
                entryValues = arrayOf(BatteryType.Alkaline.name, BatteryType.Lithium.name, BatteryType.NiMh.name, BatteryType.NiZn.name, BatteryType.Custom.name)
            ))
            addPreference(AdaptiveDoublePreference(
                ctx = context,
                doubleKey = ApexDoubleKey.BatteryLowVoltage,
                title = R.string.setting_calc_battery_low_vtg,
            ))
            addPreference(AdaptiveDoublePreference(
                ctx = context,
                doubleKey = ApexDoubleKey.BatteryHighVoltage,
                title = R.string.setting_calc_battery_high_vtg,
            ))
            addPreference(AdaptiveDoublePreference(
                ctx = context,
                doubleKey = ApexDoubleKey.MaxBasal,
                title = R.string.setting_max_basal,
            ))
            addPreference(AdaptiveDoublePreference(
                ctx = context,
                doubleKey = ApexDoubleKey.MaxBolus,
                title = R.string.setting_max_bolus,
            ))
            addPreference(AdaptiveSwitchPreference(
                ctx = context,
                booleanKey = ApexBooleanKey.LogInsulinChange,
                title = R.string.setting_log_insulin_change,
            ))
            addPreference(AdaptiveSwitchPreference(
                ctx = context,
                booleanKey = ApexBooleanKey.LogBatteryChange,
                title = R.string.setting_log_battery_change,
            ))
            addPreference(AdaptiveSwitchPreference(
                ctx = context,
                booleanKey = ApexBooleanKey.HideSerial,
                title = R.string.setting_hide_serial,
            ))
        }
    }

    private class PumpEnactResultImpl : PumpEnactResult {
        override var success: Boolean = false
        override var enacted: Boolean = false
        override var comment: String = ""
        override var duration: Int = 0
        override var absolute: Double = 0.0
        override var percent: Int = 0
        override var isPercent: Boolean = false
        override var isTempCancel: Boolean = false
        override var bolusDelivered: Double = 0.0
        override var queued: Boolean = false

        override fun success(success: Boolean): PumpEnactResult = apply { this.success = success }
        override fun enacted(enacted: Boolean): PumpEnactResult = apply { this.enacted = enacted }
        override fun comment(comment: String): PumpEnactResult = apply { this.comment = comment }
        override fun comment(comment: Int): PumpEnactResult = apply { this.comment = "".plus(comment) }
        override fun duration(duration: Int): PumpEnactResult = apply { this.duration = duration }
        override fun absolute(absolute: Double): PumpEnactResult = apply { this.absolute = absolute }
        override fun percent(percent: Int): PumpEnactResult = apply { this.percent = percent }
        override fun isPercent(isPercent: Boolean): PumpEnactResult = apply { this.isPercent = isPercent }
        override fun isTempCancel(isTempCancel: Boolean): PumpEnactResult = apply { this.isTempCancel = isTempCancel }
        override fun bolusDelivered(bolusDelivered: Double): PumpEnactResult = apply { this.bolusDelivered = bolusDelivered }
        override fun queued(queued: Boolean): PumpEnactResult = apply { this.queued = queued }

    }
}
