package app.aaps.pump.medtrum.comm

import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState

/**
 * Protocol state seam for the Medtrum packets.
 *
 * The Android driver packets read and write `MedtrumPump` (the Android state class,
 * M3 — not ported in M1). This interface captures exactly the state members the
 * protocol packets touch, using only portable types. The M2/M3 layers implement it
 * (adapting the real `MedtrumPump`); tests use a fake.
 *
 * Member-for-member, the semantics match `MedtrumPump` in the Android driver.
 */
interface MedtrumProtocolState {

    // Identity
    var pumpSN: Long
    var deviceType: Int
    var swVersion: String

    // Patch
    var patchId: Long
    var patchSessionToken: Long
    var patchStartTime: Long
    var currentSequenceNumber: Int
    var syncedSequenceNumber: Int
    var desiredPatchExpiration: Boolean
    var desiredAlarmSetting: AlarmSetting
    var desiredHourlyMaxInsulin: Int
    var desiredDailyMaxInsulin: Int
    var actualBasalProfile: ByteArray
    var pumpTimeZoneOffset: Int

    // Pump state
    var pumpState: MedtrumPumpState
    var activeAlarms: MutableSet<AlarmState>
    var pumpWarning: AlarmState
    var primeProgress: Int
    var reservoir: Double
    var batteryVoltage_A: Double
    var batteryVoltage_B: Double

    // Therapy state
    var lastBolusTime: Long?
    var lastBolusAmount: Double?
    var lastBasalStartTime: Long
    var lastBasalRate: Double
    var lastTimeReceivedFromPump: Long
    var suspendTime: Long
    var patchAge: Long

    // State updates called by packets
    fun handleBasalStatusUpdate(basalType: BasalType, basalValue: Double, basalSequence: Int, basalPatchId: Long, basalStartTime: Long)
    fun handleBasalStatusUpdate(basalType: BasalType, basalRate: Double, basalSequence: Int, basalPatchId: Long, basalStartTime: Long, receivedTime: Long)
    fun handleBolusStatusUpdate(bolusType: Int, bolusCompleted: Boolean, amountDelivered: Double)
    fun handleStopStatusUpdate(stopSequence: Int, stopPatchId: Long)
    fun handleNewPatch(newPatchId: Long, sequenceNumber: Int, newStartTime: Long)
    fun addAlarm(alarmState: AlarmState)
    fun removeAlarm(alarmState: AlarmState)
    fun clearAlarmState()
}
