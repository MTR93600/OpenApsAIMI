package app.aaps.pump.medtrum.comm

import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.DetailedBolusInfoStorage
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.BolusType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.comm.enums.ModelType
import kotlinx.coroutines.runBlocking

/**
 * Writes the Medtrum pump history to [PumpSync]. This is what the loop reads to know what the pump
 * delivered. It mirrors the Android driver call by call: the same method, the same pump id (the
 * start time of the record), the same type and the same amounts.
 *
 * Android sources: `GetRecordPacket` (bolus, basal and TDD records), `MedtrumPump.handleBasalStatusUpdate`
 * (temp basal from status), `MedtrumService.setBolus` (temporary bolus) and
 * `MedtrumService.handlePumpStateUpdate` (alarm announcements).
 *
 * The [MedtrumRecordSync] methods are not `suspend` because the packet parser is not. Like Android,
 * they use `runBlocking` around the `suspend` [PumpSync] calls.
 *
 * @param deviceType the device type from the pump (`state.deviceType`), read at every call
 * @param serialProvider the serial number as hex, the same text as Android (`pumpSN.toString(radix = 16)`)
 * @param nowMillis the phone time, used where Android uses `dateUtil.now()`
 */
class MedtrumPumpSyncHistory(
    private val pumpSync: PumpSync,
    private val detailedBolusInfoStorage: DetailedBolusInfoStorage,
    private val temporaryBasalStorage: TemporaryBasalStorage,
    private val deviceType: () -> Int,
    private val serialProvider: () -> String,
    private val nowMillis: () -> Long,
    private val logger: MedtrumLogger = MedtrumLogger.NO_OP,
) : MedtrumRecordSync {

    companion object {

        /** Same as Android `MedtrumPump.FAKE_TBR_LENGTH`, in minutes. */
        const val FAKE_TBR_LENGTH_MIN = 4800L
        private const val FAKE_TBR_LENGTH_MS = FAKE_TBR_LENGTH_MIN * 60_000L

        /** Same as Android `MedtrumPump.pumpType(type)`. */
        fun pumpTypeFor(type: ModelType): PumpType =
            when (type) {
                ModelType.MD0201, ModelType.MD8201 -> PumpType.MEDTRUM_NANO
                ModelType.MD8301                   -> PumpType.MEDTRUM_300U
                else                               -> PumpType.MEDTRUM_UNTESTED
            }

        /** The English text of the Android `alarm_*` strings. `PATCH_FAULT2` shows as patch fault, like Android. */
        fun alarmText(alarm: AlarmState): String = when (alarm) {
            AlarmState.NONE                 -> "None"
            AlarmState.PUMP_LOW_BATTERY     -> "Pump low battery"
            AlarmState.PUMP_LOW_RESERVOIR   -> "Pump low reservoir"
            AlarmState.PUMP_EXPIRES_SOON    -> "Pump expires soon"
            AlarmState.LOW_BG_SUSPENDED     -> "Low BG suspended"
            AlarmState.LOW_BG_SUSPENDED2    -> "Low BG suspended 2"
            AlarmState.AUTO_SUSPENDED       -> "Auto suspended"
            AlarmState.HOURLY_MAX_SUSPENDED -> "hourly max suspended"
            AlarmState.DAILY_MAX_SUSPENDED  -> "daily max suspended"
            AlarmState.SUSPENDED            -> "Suspended"
            AlarmState.PAUSED               -> "Paused"
            AlarmState.OCCLUSION            -> "Occlusion"
            AlarmState.EXPIRED              -> "Expired"
            AlarmState.RESERVOIR_EMPTY      -> "Reservoir empty"
            AlarmState.PATCH_FAULT,
            AlarmState.PATCH_FAULT2         -> "Patch fault"
            AlarmState.BASE_FAULT           -> "Base fault"
            AlarmState.BATTERY_OUT          -> "Battery out"
            AlarmState.NO_CALIBRATION       -> "No calibration"
        }

        /** Same mapping as the start of Android `handlePumpStateUpdate`. Null means: no alarm. */
        fun alarmFor(state: MedtrumPumpState): AlarmState? = when (state) {
            MedtrumPumpState.LOW_BG_SUSPENDED     -> AlarmState.LOW_BG_SUSPENDED
            MedtrumPumpState.LOW_BG_SUSPENDED2    -> AlarmState.LOW_BG_SUSPENDED2
            MedtrumPumpState.AUTO_SUSPENDED       -> AlarmState.AUTO_SUSPENDED
            MedtrumPumpState.HOURLY_MAX_SUSPENDED -> AlarmState.HOURLY_MAX_SUSPENDED
            MedtrumPumpState.DAILY_MAX_SUSPENDED  -> AlarmState.DAILY_MAX_SUSPENDED
            MedtrumPumpState.SUSPENDED            -> AlarmState.SUSPENDED
            MedtrumPumpState.PAUSED               -> AlarmState.PAUSED
            MedtrumPumpState.OCCLUSION            -> AlarmState.OCCLUSION
            MedtrumPumpState.EXPIRED              -> AlarmState.EXPIRED
            MedtrumPumpState.RESERVOIR_EMPTY      -> AlarmState.RESERVOIR_EMPTY
            MedtrumPumpState.PATCH_FAULT          -> AlarmState.PATCH_FAULT
            MedtrumPumpState.PATCH_FAULT2         -> AlarmState.PATCH_FAULT2
            MedtrumPumpState.BASE_FAULT           -> AlarmState.BASE_FAULT
            MedtrumPumpState.BATTERY_OUT          -> AlarmState.BATTERY_OUT
            MedtrumPumpState.NO_CALIBRATION       -> AlarmState.NO_CALIBRATION
            else                                  -> null
        }
    }

    private fun pumpType(): PumpType = pumpTypeFor(ModelType.fromValue(deviceType()))
    private fun serial(): String = serialProvider()

    // -- Records from the pump history (Android: GetRecordPacket) ------------------------------

    override fun syncBolusRecord(record: MedtrumBolusRecord, pumpSerialHex: String) {
        runBlocking {
        val bolusStartTime = record.timestamp
        when (record.bolusType) {
            BolusType.NORMAL   -> {
                val detailedBolusInfo = detailedBolusInfoStorage.findDetailedBolusInfo(bolusStartTime, record.normalDelivered)
                if (detailedBolusInfo != null) {
                    val syncOk = pumpSync.syncBolusWithTempId(
                        timestamp = bolusStartTime,
                        amount = PumpInsulin(record.normalDelivered),
                        temporaryId = detailedBolusInfo.timestamp,
                        type = detailedBolusInfo.bolusType,
                        pumpId = bolusStartTime,
                        pumpType = pumpType(),
                        pumpSerial = pumpSerialHex
                    )
                    if (!syncOk) {
                        logger.warn("PUMPCOMM", "Failed to sync bolus with tempId: ${detailedBolusInfo.timestamp}")
                        // The detailed info can belong to another, similar record. Put it back.
                        detailedBolusInfoStorage.add(detailedBolusInfo)
                    }
                } else {
                    pumpSync.syncBolusWithPumpId(
                        timestamp = bolusStartTime,
                        amount = PumpInsulin(record.normalDelivered),
                        type = null,
                        pumpId = bolusStartTime,
                        pumpType = pumpType(),
                        pumpSerial = pumpSerialHex
                    )
                }
            }

            BolusType.EXTENDED -> {
                pumpSync.syncExtendedBolusWithPumpId(
                    timestamp = bolusStartTime,
                    rate = PumpRate(record.extendedDelivered),
                    duration = record.extendedDurationMs,
                    isEmulatingTB = false,
                    pumpId = bolusStartTime,
                    pumpType = pumpType(),
                    pumpSerial = pumpSerialHex
                )
            }

            BolusType.COMBI    -> {
                // Android: this should never happen, the driver does not use combo boluses.
                val detailedBolusInfo = detailedBolusInfoStorage.findDetailedBolusInfo(bolusStartTime, record.normalDelivered)
                val newRecord = pumpSync.syncBolusWithPumpId(
                    timestamp = bolusStartTime,
                    amount = PumpInsulin(record.normalDelivered),
                    type = detailedBolusInfo?.bolusType,
                    pumpId = bolusStartTime,
                    pumpType = pumpType(),
                    pumpSerial = pumpSerialHex
                )
                pumpSync.syncExtendedBolusWithPumpId(
                    timestamp = bolusStartTime,
                    rate = PumpRate(record.extendedDelivered),
                    duration = record.extendedDurationMs,
                    isEmulatingTB = false,
                    pumpId = bolusStartTime,
                    pumpType = pumpType(),
                    pumpSerial = pumpSerialHex
                )
                logger.error("PUMPCOMM", "COMBO BOLUS record at $bolusStartTime. This should not happen.")
                if (!newRecord && detailedBolusInfo != null) {
                    detailedBolusInfoStorage.add(detailedBolusInfo)
                }
            }

            else               -> logger.error("PUMPCOMM", "BOLUS_RECORD: Unknown bolus type: ${record.bolusType}")
        }
    }
    }

    override fun syncBasalRecord(record: MedtrumBasalRecord, pumpSerialHex: String) {
        runBlocking {
        when {
            record.basalType == BasalType.STANDARD -> {
                // Android: nothing. If we get this record, the standard basal has ended.
            }

            record.basalType.isTempBasal()         -> {
                var duration = record.basalEndTime - record.basalStartTime
                // Android: PumpSync does not accept a zero duration. The pump counts in seconds, so a
                // very short basal can be 0 ms long. 250 ms is enough to be accepted.
                if (duration < 250) duration = 250
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = record.basalStartTime,
                    rate = PumpRate(if (record.basalType == BasalType.ABSOLUTE_TEMP) record.basalRate else record.basalPercent.toDouble()),
                    duration = duration,
                    isAbsolute = record.basalType == BasalType.ABSOLUTE_TEMP,
                    type = PumpSync.TemporaryBasalType.NORMAL,
                    pumpId = record.basalStartTime,
                    pumpType = pumpType(),
                    pumpSerial = pumpSerialHex
                )
            }

            record.basalType.isSuspendedByPump()   -> {
                val duration = record.basalEndTime - record.basalStartTime
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = record.basalStartTime,
                    rate = PumpRate(0.0),
                    duration = duration,
                    isAbsolute = true,
                    type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = record.basalStartTime,
                    pumpType = pumpType(),
                    pumpSerial = pumpSerialHex
                )
            }

            else                                   -> logger.error("PUMPCOMM", "BASAL_RECORD: Unknown basal type: ${record.basalType}")
        }
    }
    }

    override fun syncTddRecord(record: MedtrumTddRecord, pumpSerialHex: String) = runBlocking {
        pumpSync.createOrUpdateTotalDailyDose(
            timestamp = record.timestamp,
            bolusAmount = record.bolusTdd,
            basalAmount = record.basalTdd,
            totalAmount = record.tdd,
            pumpId = record.timestamp,
            pumpType = pumpType(),
            pumpSerial = pumpSerialHex
        )
        Unit
    }

    override fun shouldSyncPumpSuspend(basalEndTime: Long): Boolean = runBlocking {
        val expected = pumpSync.expectedPumpState().temporaryBasal
        expected == null || expected.timestamp <= basalEndTime || expected.duration == FAKE_TBR_LENGTH_MS
    }

    // -- Basal status (Android: MedtrumPump.handleBasalStatusUpdate) ---------------------------

    override fun syncBasalStatus(basalType: BasalType, basalRate: Double, basalStartTime: Long, baseBasalRate: Double): Int = runBlocking {
        val expected = pumpSync.expectedPumpState().temporaryBasal
        var durationInMin = 0
        when {
            basalType.isTempBasal() && expected?.pumpId != basalStartTime                                                    -> {
                // The stored info is removed from the storage by this call, like Android.
                val temporaryBasalInfo = temporaryBasalStorage.findTemporaryBasal(basalStartTime, basalRate)
                // If the duration is unknown, Android uses the patch life time.
                val duration = temporaryBasalInfo?.duration ?: FAKE_TBR_LENGTH_MS
                durationInMin = (duration / 60_000L).toInt()
                val adjustedBasalRate =
                    if (basalType == BasalType.ABSOLUTE_TEMP) basalRate
                    else (basalRate / baseBasalRate) * 100
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = basalStartTime,
                    rate = PumpRate(adjustedBasalRate),
                    duration = duration,
                    isAbsolute = basalType == BasalType.ABSOLUTE_TEMP,
                    type = temporaryBasalInfo?.type,
                    pumpId = basalStartTime,
                    pumpType = pumpType(),
                    pumpSerial = serial()
                )
            }

            basalType.isSuspendedByPump() && expected?.pumpId != basalStartTime                                              -> {
                if (expected != null && expected.timestamp > basalStartTime && expected.duration == FAKE_TBR_LENGTH_MS) {
                    pumpSync.invalidateTemporaryBasalWithPumpId(expected.pumpId ?: 0L, expected.pumpType, expected.pumpSerial)
                }
                pumpSync.syncTemporaryBasalWithPumpId(
                    timestamp = basalStartTime,
                    rate = PumpRate(0.0),
                    duration = FAKE_TBR_LENGTH_MS,
                    isAbsolute = true,
                    type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                    pumpId = basalStartTime,
                    pumpType = pumpType(),
                    pumpSerial = serial()
                )
            }

            basalType == BasalType.NONE && expected?.rate != basalRate && expected?.duration != FAKE_TBR_LENGTH_MS -> {
                // The pump is suspended. Set the fake temp basal.
                setFakeTbr()
            }

            basalType == BasalType.STANDARD                                                                                  -> {
                if (expected != null) {
                    // The pump resumed. Sync the end. Normal basal start = temp basal end. The 250 ms
                    // keep a gap between the start and the stop of the temp basal.
                    pumpSync.syncStopTemporaryBasalWithPumpId(
                        timestamp = basalStartTime + 250,
                        endPumpId = basalStartTime + 250,
                        pumpType = pumpType(),
                        pumpSerial = serial()
                    )
                }
            }
        }
        durationInMin
    }

    private suspend fun setFakeTbr() {
        val now = nowMillis()
        pumpSync.syncTemporaryBasalWithPumpId(
            timestamp = now,
            rate = PumpRate(0.0),
            duration = FAKE_TBR_LENGTH_MS,
            isAbsolute = true,
            type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
            pumpId = now,
            pumpType = pumpType(),
            pumpSerial = serial()
        )
    }

    /**
     * Android `MedtrumPump.setFakeTBRIfNotSet`. Returns true when the fake temp basal was written,
     * so the caller can update the last basal state like Android does.
     */
    fun setFakeTbrIfNotSet(): Boolean = runBlocking {
        val expected = pumpSync.expectedPumpState().temporaryBasal
        if (expected?.duration != FAKE_TBR_LENGTH_MS) {
            setFakeTbr()
            true
        } else false
    }

    // -- New patch (Android: MedtrumPump.handleNewPatch) ---------------------------------------

    override fun syncNewPatch(newStartTime: Long) = runBlocking {
        pumpSync.insertTherapyEventIfNewWithTimestamp(
            timestamp = newStartTime,
            type = TE.Type.CANNULA_CHANGE,
            pumpType = pumpType(),
            pumpSerial = serial()
        )
        pumpSync.insertTherapyEventIfNewWithTimestamp(
            timestamp = newStartTime,
            type = TE.Type.INSULIN_CHANGE,
            pumpType = pumpType(),
            pumpSerial = serial()
        )
        Unit
    }

    // -- Bolus delivered by this app (Android: MedtrumService.setBolus) ------------------------

    /**
     * The bolus command was sent. Android stores the detailed info for the history read and adds a
     * temporary bolus, so the loop counts the insulin while it is delivered. [bolusStart] becomes
     * the timestamp and the temporary id of [detailedBolusInfo], like Android.
     */
    suspend fun bolusStarted(detailedBolusInfo: DetailedBolusInfo, bolusStart: Long) {
        detailedBolusInfo.timestamp = bolusStart // Android: the timestamp is the start of the bolus.
        detailedBolusInfoStorage.add(detailedBolusInfo) // It is picked up when the history is read.
        val newRecord = pumpSync.addBolusWithTempId(
            timestamp = detailedBolusInfo.timestamp,
            amount = PumpInsulin(detailedBolusInfo.insulin),
            temporaryId = detailedBolusInfo.timestamp,
            type = detailedBolusInfo.bolusType,
            pumpType = pumpType(),
            pumpSerial = serial()
        )
        if (!newRecord) logger.error("PUMPCOMM", "Bolus with tempId ${detailedBolusInfo.timestamp} already exists")
    }

    /**
     * The user stopped the bolus before any insulin came out. The pump writes no record for this, so
     * Android turns the temporary bolus into a bolus of 0 U and removes the stored detailed info.
     */
    suspend fun bolusStoppedWithoutInsulin(detailedBolusInfo: DetailedBolusInfo, bolusStart: Long) {
        pumpSync.syncBolusWithTempId(
            timestamp = bolusStart,
            amount = PumpInsulin(0.0),
            temporaryId = bolusStart,
            type = detailedBolusInfo.bolusType,
            pumpId = bolusStart,
            pumpType = pumpType(),
            pumpSerial = serial()
        )
        // Remove the detailed bolus info. Android only calls find for this.
        detailedBolusInfoStorage.findDetailedBolusInfo(bolusStart, detailedBolusInfo.insulin)
    }

    // -- Temp basal started by this app (Android: MedtrumPlugin.setTempBasalAbsolute) ----------

    /** Android stores the request first. The pump status that follows is matched with it by time and rate. */
    fun rememberTempBasalRequest(rate: Double, durationInMinutes: Int, tbrType: PumpSync.TemporaryBasalType) {
        temporaryBasalStorage.add(
            PumpSync.PumpState.TemporaryBasal(nowMillis(), durationInMinutes * 60_000L, rate, true, tbrType, 0L, 0L)
        )
    }

    // -- Alarms (Android: MedtrumService.handlePumpStateUpdate) --------------------------------

    /** Writes [text] to the history as a pump announcement, as Android does for warnings and patch errors. */
    fun announce(text: String) = runBlocking {
        pumpSync.insertAnnouncement(error = text, pumpId = null, pumpType = pumpType(), pumpSerial = serial())
        Unit
    }

    /** Writes the announcement for an alarm state. Returns the alarm, or null when the state is no alarm. */
    fun announcePumpState(state: MedtrumPumpState): AlarmState? {
        val alarm = alarmFor(state) ?: return null
        if (alarm == AlarmState.NONE) return null
        runBlocking {
            pumpSync.insertAnnouncement(
                error = alarmText(alarm),
                pumpId = null,
                pumpType = pumpType(),
                pumpSerial = serial()
            )
        }
        return alarm
    }
}
