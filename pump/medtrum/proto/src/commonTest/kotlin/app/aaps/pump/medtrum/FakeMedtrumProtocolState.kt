package app.aaps.pump.medtrum

import app.aaps.pump.medtrum.comm.MedtrumProtocolState
import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState

/**
 * Fake [MedtrumProtocolState] for protocol tests.
 * Records state updates for verification.
 */
class FakeMedtrumProtocolState : MedtrumProtocolState {
    override var pumpSN: Long = 0L
    override var deviceType: Int = 0
    override var swVersion: String = ""
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
    override var pumpState: MedtrumPumpState = MedtrumPumpState.NONE
    override var activeAlarms: MutableSet<AlarmState> = mutableSetOf()
    override var pumpWarning: AlarmState = AlarmState.NONE
    override var primeProgress: Int = 0
    override var reservoir: Double = 0.0
    override var batteryVoltage_A: Double = 0.0
    override var batteryVoltage_B: Double = 0.0
    override var lastBolusTime: Long? = null
    override var lastBolusAmount: Double? = null
    override var lastBasalStartTime: Long = 0L
    override var lastBasalRate: Double = 0.0
    override var lastTimeReceivedFromPump: Long = 0L
    override var suspendTime: Long = 0L
    override var patchAge: Long = 0L

    val basalUpdates = mutableListOf<String>()
    val alarmsAdded = mutableListOf<AlarmState>()
    val alarmsRemoved = mutableListOf<AlarmState>()

    override fun handleBasalStatusUpdate(basalType: BasalType, basalValue: Double, basalSequence: Int, basalPatchId: Long, basalStartTime: Long) {
        basalUpdates.add("basal:$basalType:$basalValue:$basalSequence")
    }

    override fun handleBasalStatusUpdate(basalType: BasalType, basalRate: Double, basalSequence: Int, basalPatchId: Long, basalStartTime: Long, receivedTime: Long) {
        basalUpdates.add("basal:$basalType:$basalRate:$basalSequence:$receivedTime")
    }

    override fun handleBolusStatusUpdate(bolusType: Int, bolusCompleted: Boolean, amountDelivered: Double) = Unit
    override fun handleStopStatusUpdate(stopSequence: Int, stopPatchId: Long) = Unit
    override fun handleNewPatch(newPatchId: Long, sequenceNumber: Int, newStartTime: Long) {
        patchId = newPatchId
        currentSequenceNumber = sequenceNumber
        patchStartTime = newStartTime
    }

    override fun addAlarm(alarmState: AlarmState) {
        activeAlarms.add(alarmState)
        alarmsAdded.add(alarmState)
    }

    override fun removeAlarm(alarmState: AlarmState) {
        activeAlarms.remove(alarmState)
        alarmsRemoved.add(alarmState)
    }

    override fun clearAlarmState() {
        activeAlarms.clear()
    }
}
