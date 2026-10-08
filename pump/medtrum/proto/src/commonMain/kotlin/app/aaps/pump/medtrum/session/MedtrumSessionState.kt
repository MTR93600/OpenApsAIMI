package app.aaps.pump.medtrum.session

import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState
import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.extension.toByteArray
import app.aaps.pump.medtrum.extension.toInt
import kotlin.math.round

/**
 * Portable pump state for the Medtrum session.
 *
 * Transposed from the Android `MedtrumPump` (pump/medtrum). The Android version
 * persists every field to SharedPreferences, exposes StateFlows for the UI,
 * and calls `PumpSync` for AAPS history — all M3 concerns. This class keeps
 * the in-memory state and the pure update logic; persistence and history sync
 * are M3 (via [MedtrumProtocolState] consumers and `MedtrumRecordSync`).
 *
 * Member-for-member, the field semantics match `MedtrumPump`.
 */
class MedtrumSessionState(
    private val logger: MedtrumLogger = MedtrumLogger.NO_OP,
    val clock: MedtrumClock,
) : MedtrumProtocolState {

    // Identity
    override var pumpSN: Long = 0L
    override var deviceType: Int = 0
    override var swVersion: String = ""

    // Patch
    override var patchId: Long = 0L
    override var patchSessionToken: Long = 0L
    override var patchStartTime: Long = 0L
    override var currentSequenceNumber: Int = 0
    override var syncedSequenceNumber: Int = 0
    override var desiredPatchExpiration: Boolean = false
    override var desiredAlarmSetting: AlarmSetting = AlarmSetting.LIGHT_VIBRATE_AND_BEEP
    override var desiredHourlyMaxInsulin: Int = 40
    override var desiredDailyMaxInsulin: Int = 180
    override var actualBasalProfile: ByteArray = byteArrayOf(0)
    override var pumpTimeZoneOffset: Int = 0

    // Pump state
    override var pumpState: MedtrumPumpState = MedtrumPumpState.NONE
        set(value) {
            // Maintain patchPrimed flag: set once we reach PRIMING, clear only on STOPPED
            when {
                value >= MedtrumPumpState.PRIMING && value < MedtrumPumpState.STOPPED -> patchPrimed = true
                value == MedtrumPumpState.STOPPED                                     -> patchPrimed = false
            }
            field = value
        }
    override var activeAlarms: MutableSet<AlarmState> = mutableSetOf()
    override var pumpWarning: AlarmState = AlarmState.NONE
    override var primeProgress: Int = 0
    override var reservoir: Double = 0.0
    override var batteryVoltage_A: Double = 0.0
    override var batteryVoltage_B: Double = 0.0

    // Therapy state
    override var lastBolusTime: Long? = null
    override var lastBolusAmount: Double? = null
    override var lastBasalStartTime: Long = 0L
    override var lastBasalRate: Double = 0.0
    override var lastTimeReceivedFromPump: Long = 0L
    override var suspendTime: Long = 0L
    override var patchAge: Long = 0L

    /** BLE link state (Android: StateFlow; here a plain var, M3 observes it). */
    var connectionState: ConnectionState = ConnectionState.DISCONNECTED

    /** True once the patch reached PRIMING in this activation; cleared only by STOPPED. */
    var patchPrimed: Boolean = false
        private set

    // Bolus tracking (Android: partly StateFlow for UI)
    var bolusStartTime: Long = 0L
    var bolusAmountToBeDelivered: Double = 0.0
    var bolusAmountDelivered: Double = 0.0
    var bolusProgressLastTimeStamp: Long = 0L
    var bolusStopped: Boolean = false
    var bolusDone: Boolean = true
    var bolusErrorReason: String? = null

    /** Hook for the session to observe bolus progress/completion. */
    var onBolusUpdate: (() -> Unit)? = null

    // Basal tracking
    var lastBasalType: BasalType = BasalType.NONE
    var lastBasalSequence: Int = 0
    var lastBasalPatchId: Long = 0L
    var lastBasalDuration: Int = 0
    var lastStopSequence: Int = 0
    var lastStopPatchId: Long = 0L

    val tempBasalInProgress: Boolean
        get() = lastBasalType == BasalType.ABSOLUTE_TEMP || lastBasalType == BasalType.RELATIVE_TEMP

    override fun handleBasalStatusUpdate(
        basalType: BasalType,
        basalValue: Double,
        basalSequence: Int,
        basalPatchId: Long,
        basalStartTime: Long
    ) {
        handleBasalStatusUpdate(basalType, basalValue, basalSequence, basalPatchId, basalStartTime, clock.nowMillis())
    }

    override fun handleBasalStatusUpdate(
        basalType: BasalType,
        basalRate: Double,
        basalSequence: Int,
        basalPatchId: Long,
        basalStartTime: Long,
        receivedTime: Long
    ) {
        logger.debug(
            "PUMPCOMM",
            "handleBasalStatusUpdate: basalType: $basalType basalValue: $basalRate basalSequence: $basalSequence " +
                "basalPatchId: $basalPatchId basalStartTime: $basalStartTime receivedTime: $receivedTime"
        )
        // Note: the Android version reconciles the AAPS temp-basal history here
        // (pumpSync/temporaryBasalStorage). That is M3 — the session keeps the
        // pump-reported state, which is what the protocol needs.
        lastBasalType = basalType
        lastBasalRate = basalRate
        lastBasalSequence = basalSequence
        if (basalSequence > currentSequenceNumber) {
            currentSequenceNumber = basalSequence
        }
        lastBasalPatchId = basalPatchId
        if (basalPatchId != patchId) {
            logger.error("PUMPCOMM", "handleBasalStatusUpdate: PatchId in status update does not match current patchId!")
        }
        lastBasalStartTime = basalStartTime
        lastBasalDuration = 0
        // Interface members (kept in sync for packet consumers)
        this.lastBasalStartTime = basalStartTime
        this.lastBasalRate = basalRate
    }

    override fun handleBolusStatusUpdate(bolusType: Int, bolusCompleted: Boolean, amountDelivered: Double) {
        logger.debug(
            "PUMPCOMM",
            "handleBolusStatusUpdate: bolusType: $bolusType bolusCompleted: $bolusCompleted amountDelivered: $amountDelivered"
        )
        bolusProgressLastTimeStamp = clock.nowMillis()
        bolusAmountDelivered = amountDelivered
        bolusDone = bolusCompleted
        onBolusUpdate?.invoke()
    }

    override fun handleStopStatusUpdate(stopSequence: Int, stopPatchId: Long) {
        logger.debug("PUMPCOMM", "handleStopStatusUpdate: stopSequence: $stopSequence stopPatchId: $stopPatchId")
        lastStopSequence = stopSequence
        if (stopSequence > currentSequenceNumber) {
            currentSequenceNumber = stopSequence
        }
        lastStopPatchId = stopPatchId
        if (stopPatchId != patchId) {
            logger.error("PUMPCOMM", "handleStopStatusUpdate: PatchId in status update does not match current patchId!")
        }
    }

    override fun handleNewPatch(newPatchId: Long, sequenceNumber: Int, newStartTime: Long) {
        patchId = newPatchId
        patchStartTime = newStartTime
        currentSequenceNumber = sequenceNumber
        syncedSequenceNumber = 1
        // Note: the Android version inserts CANNULA_CHANGE / INSULIN_CHANGE therapy
        // events into pumpSync here — M3 via MedtrumRecordSync consumers.
    }

    override fun addAlarm(alarmState: AlarmState) {
        activeAlarms.add(alarmState)
    }

    override fun removeAlarm(alarmState: AlarmState) {
        activeAlarms.remove(alarmState)
    }

    override fun clearAlarmState() {
        activeAlarms.clear()
    }

    /** Reset on patch deactivation / serial change (Android: resetPatchParameters). */
    fun resetPatchParameters() {
        patchId = 0
        syncedSequenceNumber = 1
        currentSequenceNumber = 1
        reservoir = 0.0
        batteryVoltage_B = 0.0
        patchStartTime = 0L
    }

    /**
     * Build the 3-byte-per-slot basal profile array for [ActivatePacket]/[SetBasalProfilePacket].
     *
     * Transposed from `MedtrumPump.buildMedtrumProfileArray`, with the AAPS
     * `Profile` replaced by plain (rateUph, timeAsSeconds) pairs — M3 adapts.
     * Returns null when a rate or time does not fit (same guard as Android).
     */
    fun buildMedtrumProfileArray(basals: List<Pair<Double, Int>>): ByteArray? {
        var bytes = byteArrayOf()
        for ((value, timeAsSeconds) in basals) {
            val rate = round(value / 0.05).toInt()
            val time = timeAsSeconds / 60
            if (rate > 0xFFF || time > 0xFFF) {
                logger.error("PUMP", "buildMedtrumProfileArray: rate or time too large: $rate, $time")
                return null
            }
            bytes += ((rate shl 12) + time).toByteArray(3)
            logger.debug("PUMP", "buildMedtrumProfileArray: value: $value time: $timeAsSeconds, converted: $rate, $time")
        }
        return (basals.size).toByteArray(1) + bytes
    }

    /**
     * Hourly basal rate from a profile array at [timestamp] (minutes-of-day via [clock]).
     *
     * Transposed from `MedtrumPump.getHourlyBasalFromMedtrumProfileArray`; the
     * Android `GregorianCalendar` is replaced by [MedtrumClock.minuteOfDay].
     */
    fun getHourlyBasalFromMedtrumProfileArray(basalProfile: ByteArray, timestamp: Long): Double {
        val basalCount = basalProfile[0].toInt()
        var basal = 0.0
        if (basalProfile.size < 4 || (basalProfile.size - 1) % 3 != 0 || basalCount > 24) {
            logger.debug("PUMP", "getHourlyBasalFromMedtrumProfileArray: No valid basal profile set")
            return basal
        }
        val hourOfDayMinutes = clock.minuteOfDay(timestamp)
        for (index in 0 until basalCount) {
            val currentIndex = 1 + (index * 3)
            val nextIndex = currentIndex + 3
            val rateAndTime = basalProfile.copyOfRange(currentIndex, nextIndex).toInt()
            val rate = (rateAndTime shr 12) * 0.05
            val startMinutes = rateAndTime and 0xFFF
            val endMinutes = if (nextIndex < basalProfile.size) {
                val nextRateAndTime = basalProfile.copyOfRange(nextIndex, nextIndex + 3).toInt()
                nextRateAndTime and 0xFFF
            } else {
                24 * 60
            }
            if (hourOfDayMinutes in startMinutes until endMinutes) {
                basal = rate
                logger.debug("PUMP", "getHourlyBasalFromMedtrumProfileArray: basal: $basal")
                break
            }
        }
        return basal
    }
}
