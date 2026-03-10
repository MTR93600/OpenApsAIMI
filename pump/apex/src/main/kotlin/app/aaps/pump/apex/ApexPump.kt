package app.aaps.pump.apex

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.events.EventOverviewBolusProgress
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.apex.connectivity.commands.pump.Alarm
import app.aaps.pump.apex.connectivity.commands.pump.BolusEntry
import app.aaps.pump.apex.connectivity.commands.pump.StatusV1
import app.aaps.pump.apex.connectivity.commands.pump.StatusV2
import app.aaps.pump.apex.connectivity.commands.pump.Version
import app.aaps.pump.apex.misc.BatteryType
import app.aaps.pump.apex.utils.keys.ApexBooleanKey
import app.aaps.pump.apex.utils.keys.ApexDoubleKey
import app.aaps.pump.apex.utils.keys.ApexStringKey
import org.joda.time.DateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * @author Roman Rikhter (teledurak@gmail.com)
 */
@Singleton
class ApexPump @Inject constructor(
    val preferences: Preferences,
) {
    private var _status: PumpStatus? = null
    val status: PumpStatus?
        get() = _status

    val batteryLevel: BatteryLevel
        get() = status?.batteryLevel ?: BatteryLevel(0, 0.0, true)

    val isAdvancedBolusEnabled: Boolean
        get() = status?.isAdvancedBolusEnabled ?: false

    val currentBasalPattern: Int
        get() = status?.currentBasalPattern ?: 0

    val maxBasal: Double
        get() = status?.maxBasal ?: 0.0

    val maxBolus: Double
        get() = status?.maxBolus ?: 0.0

    val dateTime: DateTime
        get() = status?.dateTime ?: DateTime(0)

    val reservoirLevel: Double
        get() = status?.reservoirLevel ?: 0.0

    val alarms: List<Alarm>
        get() = status?.alarms ?: listOf()

    val tbr: TBR?
        get() = status?.tbr

    val basal: Basal?
        get() = status?.basal

    val isSuspended: Boolean
        get() = basal == null

    val isTBRunning: Boolean
        get() = tbr != null

    val isBolusing: Boolean
        get() = inProgressBolus != null

    val settingsAreUnadvised: Boolean
        get() = isAdvancedBolusEnabled || currentBasalPattern != ApexService.USED_BASAL_PATTERN_INDEX

    var lastV1: StatusV1? = null
    var lastV2: StatusV2? = null

    var inProgressBolus: InProgressBolus? = null
    var lastBolus: BolusEntry? = null
    var firmwareVersion: Version? = null
    var serialNumber: String = ""
    var isInitialized: Boolean = true

    fun updateFromV1(obj: StatusV1): StatusUpdate {
        val updates = arrayListOf<Update>()
        val new = PumpStatus(
            batteryLevel = BatteryLevel(obj.batteryLevel!!.approximatePercentage, batteryLevel.voltage, true),
            isAdvancedBolusEnabled = obj.advancedBolusEnabled,
            currentBasalPattern = obj.currentBasalPattern,
            maxBasal = obj.maxBasal * 0.025,
            maxBolus = obj.maxBolus * 0.025,
            tbr = if (obj.isTemporaryBasalActive) TBR(
                if (obj.temporaryBasalRateIsAbsolute) obj.temporaryBasalRate * 0.025 else null,
                if (obj.temporaryBasalRateIsAbsolute) null else obj.temporaryBasalRate,
                obj.temporaryBasalRateIsAbsolute,
                obj.temporaryBasalRateDuration,
                obj.temporaryBasalRateElapsed,
            ) else null,
            basal = if (obj.currentBasalRate == UShort.MAX_VALUE.toInt())
                null
            else Basal(
                obj.currentBasalRate * 0.025,
                obj.currentBasalEndHour,
                obj.currentBasalEndMinute,
            ),
            dateTime = obj.dateTime,
            reservoirLevel = obj.reservoir / 1000.0,
            alarms = obj.alarms
        )

        updates.apply { when {
            new.basal != basal -> add(Update.BasalChanged)
            new.tbr != tbr -> add(Update.TBRChanged)
            new.maxBasal != maxBasal || new.maxBolus != maxBolus -> add(Update.ConstraintsChanged)
            new.currentBasalPattern != currentBasalPattern || new.isAdvancedBolusEnabled != isAdvancedBolusEnabled -> add(Update.UnadvisedSettingsChanged)
            new.batteryLevel != batteryLevel -> add(Update.BatteryChanged)
            new.reservoirLevel != reservoirLevel -> add(Update.ReservoirChanged)
            new.alarms != alarms -> add(Update.AlarmsChanged)
        } }

        lastV1 = obj

        val ret = StatusUpdate(
            changes = updates,
            previous = status,
            current = new,
        )
        _status = new
        return ret
    }

    private fun calcFromVoltage(voltage: Double): Int {
        val batteryType = BatteryType.valueOf(preferences.get(ApexStringKey.CalcBatteryType))
        val lowVoltage = when (batteryType) {
            BatteryType.Custom -> preferences.get(ApexDoubleKey.BatteryLowVoltage)
            else -> batteryType.lowVoltage
        }
        val highVoltage = when (batteryType) {
            BatteryType.Custom -> preferences.get(ApexDoubleKey.BatteryHighVoltage)
            else -> batteryType.highVoltage
        }

        // Credits: Medtronic pump driver
        val percent = (voltage - lowVoltage) / (highVoltage - lowVoltage)
        var percentInt = (percent * 100.0).toInt()
        if (percentInt < 0) percentInt = 1
        if (percentInt > 100) percentInt = 100

        if (batteryLevel.percentage == 0) return 0
        return percentInt
    }

    fun updateFromV2(obj: StatusV2): StatusUpdate {
        val percentage = if (preferences.get(ApexBooleanKey.CalculateBatteryPercentage))
            calcFromVoltage(obj.batteryVoltage)
        else
            batteryLevel.percentage

        val new = PumpStatus(
            batteryLevel = BatteryLevel(percentage, obj.batteryVoltage, false),
            dateTime = dateTime,
            reservoirLevel = reservoirLevel,
            tbr = tbr,
            alarms = alarms,
            currentBasalPattern = currentBasalPattern,
            maxBasal = maxBasal,
            maxBolus = maxBolus,
            basal = basal,
            isAdvancedBolusEnabled = isAdvancedBolusEnabled,
        )
        val updates = mutableListOf<Update>()
        updates.apply { when {
            batteryLevel.voltage != new.batteryLevel.voltage -> add(Update.BatteryChanged)
        } }

        lastV2 = obj

        val ret = StatusUpdate(
            changes = updates,
            previous = status,
            current = new,
        )
        _status = new
        return ret
    }

    data class BatteryLevel(
        val percentage: Int,
        val voltage: Double?, // v2+
        val approximate: Boolean
    )

    data class Basal(
        val rate: Double,
        val endHour: Int,
        val endMinute: Int,
    )

    data class TBR(
        val rate: Double?,
        val percentage: Int?,
        val isAbsolute: Boolean,
        val durationMinutes: Int,
        val elapsedMinutes: Int,
    )

    data class Treatment(
        val insulin: Double,
        val carbs: Int,
        val isSMB: Boolean,
        val id: Long?
    )

    data class InProgressBolus(
        var requestedDose: Double = 0.0,
        var currentDose: Double = 0.0,
        var temporaryId: Long = 0,
        var cancelled: Boolean = false,
        var detailedBolusInfo: DetailedBolusInfo,
        var treatment: Treatment,
        var failed: Boolean = false,
        var lockHistory: Boolean = true,
        var useFallbackDose: Boolean = false,
    )

    enum class Update {
        AlarmsChanged,
        BasalChanged,
        TBRChanged,
        ConstraintsChanged,
        UnadvisedSettingsChanged,
        BatteryChanged,
        ReservoirChanged,
    }

    data class PumpStatus(
        val dateTime: DateTime,
        val batteryLevel: BatteryLevel,
        val reservoirLevel: Double,
        val alarms: List<Alarm>,
        val isAdvancedBolusEnabled: Boolean,
        val currentBasalPattern: Int,
        val maxBasal: Double,
        val maxBolus: Double,
        val tbr: TBR?,
        val basal: Basal?,
    ) {
        override fun toString() =
                "Date ${dateTime}, battery ${batteryLevel.percentage}, " +
                "reservoir ${reservoirLevel}, alarms ${alarms.joinToString(", ", "[", "]") { it.name }}, " +
                "maxBasal $maxBasal, maxBolus $maxBolus, " +
                "TBR ${tbr?.rate}, basal ${basal?.rate}"

        fun getPumpStatusIcon(): String = when {
            alarms.isNotEmpty() -> "{fa-bell}"
            basal == null -> "{fa-ban}"
            else -> "{fa-check}"
        }

        fun getPumpStatus(rh: ResourceHelper): String = when {
            alarms.isNotEmpty() -> rh.gs(R.string.overview_pump_status_alarm)
            basal == null -> rh.gs(R.string.overview_pump_status_suspended)
            else -> rh.gs(R.string.overview_pump_status_normal)
        }

        fun getBatteryIcon(): String = when {
            batteryLevel.percentage > 90 -> "{fa-battery-4}"
            batteryLevel.percentage >= 75 -> "{fa-battery-3}"
            batteryLevel.percentage >= 50 -> "{fa-battery-2}"
            batteryLevel.percentage >= 25 -> "{fa-battery-1}"
            else -> "{fa-battery-0}"
        }

        fun getBatteryLevel(rh: ResourceHelper): String = if (batteryLevel.approximate)
            rh.gs(R.string.overview_pump_battery_approximate, batteryLevel.percentage)
        else
            rh.gs(R.string.overview_pump_battery_exact, batteryLevel.percentage, ((batteryLevel.voltage ?: 0.0) * 1000).roundToInt())

        fun getReservoirLevel(rh: ResourceHelper): String = rh.gs(R.string.overview_pump_reservoir, reservoirLevel)

        fun getTBR(rh: ResourceHelper): String = if (tbr != null) {
            val diff = tbr.durationMinutes - tbr.elapsedMinutes
            val value = if (tbr.isAbsolute) tbr.rate else tbr.percentage
            if (diff >= 60)
                rh.gs(
                    if (tbr.isAbsolute)
                        R.string.overview_pump_tempbasal_h
                    else
                        R.string.overview_pump_tempbasal_percentage_h,
                    value, diff / 60, diff % 60
                )
            else
                rh.gs(
                    if (tbr.isAbsolute)
                        R.string.overview_pump_tempbasal
                    else
                        R.string.overview_pump_tempbasal_percentage,
                    value, diff
                )
        } else "-"

        fun getBasal(rh: ResourceHelper): String = if (basal != null) {
            rh.gs(R.string.overview_pump_basal, basal.rate)
        } else "-"
    }

    data class StatusUpdate(
        val changes: List<Update>,
        val previous: PumpStatus?,
        val current: PumpStatus,
    )
}