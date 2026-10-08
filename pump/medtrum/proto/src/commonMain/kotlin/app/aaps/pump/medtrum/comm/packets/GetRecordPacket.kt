package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumBasalRecord
import app.aaps.pump.medtrum.comm.MedtrumBolusRecord
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState
import app.aaps.pump.medtrum.comm.MedtrumRecordSync
import app.aaps.pump.medtrum.comm.MedtrumTddRecord
import app.aaps.pump.medtrum.comm.enums.BasalEndReason
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.BolusType
import app.aaps.pump.medtrum.comm.enums.CommandType.GET_RECORD
import app.aaps.pump.medtrum.extension.toByteArray
import app.aaps.pump.medtrum.extension.toFloat
import app.aaps.pump.medtrum.extension.toInt
import app.aaps.pump.medtrum.extension.toLong
import app.aaps.pump.medtrum.util.MedtrumTimeUtil

/**
 * Get record packet — ported from the Android driver (pump/medtrum).
 *
 * The Android version synced parsed records directly to the database via `PumpSync`
 * / `DetailedBolusInfoStorage` (using `runBlocking`). Here the parsed records are
 * delivered to [recordSync] as portable data classes; the M2/M3 layers implement
 * the persistence strategy. The byte parsing is verbatim.
 *
 * `dateUtil.dateAndTimeString()` in log lines is replaced by the raw timestamp
 * (log text only, no protocol impact). `T.mins(x).msecs()` is inlined as `x * 60000L`.
 */
class GetRecordPacket(
    private val state: MedtrumProtocolState,
    private val recordIndex: Int,
    private val recordSync: MedtrumRecordSync = MedtrumRecordSync.NO_OP,
    private val timeUtil: MedtrumTimeUtil = MedtrumTimeUtil(),
    logger: MedtrumLogger = MedtrumLogger.NO_OP
) : MedtrumPacket(logger) {

    companion object {

        private const val RESP_RECORD_HEADER_START = 6
        private const val RESP_RECORD_HEADER_END = RESP_RECORD_HEADER_START + 1
        private const val RESP_RECORD_UNKNOWN_START = RESP_RECORD_HEADER_END
        private const val RESP_RECORD_UNKNOWN_END = RESP_RECORD_UNKNOWN_START + 1
        private const val RESP_RECORD_TYPE_START = RESP_RECORD_UNKNOWN_END
        private const val RESP_RECORD_TYPE_END = RESP_RECORD_TYPE_START + 1
        private const val RESP_RECORD_UNKNOWN1_START = RESP_RECORD_TYPE_END
        private const val RESP_RECORD_UNKNOWN1_END = RESP_RECORD_UNKNOWN1_START + 1
        private const val RESP_RECORD_SERIAL_START = RESP_RECORD_UNKNOWN1_END
        private const val RESP_RECORD_SERIAL_END = RESP_RECORD_SERIAL_START + 4
        private const val RESP_RECORD_PATCH_ID_START = RESP_RECORD_SERIAL_END
        private const val RESP_RECORD_PATCH_ID_END = RESP_RECORD_PATCH_ID_START + 2
        private const val RESP_RECORD_SEQUENCE_START = RESP_RECORD_PATCH_ID_END
        private const val RESP_RECORD_SEQUENCE_END = RESP_RECORD_SEQUENCE_START + 2
        private const val RESP_RECORD_DATA_START = RESP_RECORD_SEQUENCE_END

        private const val VALID_HEADER = 170
        private const val BOLUS_RECORD = 1
        private const val BOLUS_RECORD_ALT = 65
        private const val BASAL_RECORD = 2
        private const val BASAL_RECORD_ALT = 66
        private const val ALARM_RECORD = 3
        private const val AUTO_RECORD = 4
        private const val TIME_SYNC_RECORD = 5
        private const val AUTO1_RECORD = 6
        private const val AUTO2_RECORD = 7
        private const val AUTO3_RECORD = 8
        private const val TDD_RECORD = 9

    }

    init {
        opCode = GET_RECORD.code
        expectedMinRespLength = RESP_RECORD_DATA_START
    }

    override fun getRequest(): ByteArray {
        return byteArrayOf(opCode) + recordIndex.toByteArray(2) + state.patchId.toByteArray(2)
    }

    override fun handleResponse(data: ByteArray): Boolean {
        val success = super.handleResponse(data)
        if (success) {
            val recordHeader = data.copyOfRange(RESP_RECORD_HEADER_START, RESP_RECORD_HEADER_END).toInt()
            val recordUnknown = data.copyOfRange(RESP_RECORD_UNKNOWN_START, RESP_RECORD_UNKNOWN_END).toInt()
            val recordType = data.copyOfRange(RESP_RECORD_TYPE_START, RESP_RECORD_TYPE_END).toInt()
            val recordSerial = data.copyOfRange(RESP_RECORD_SERIAL_START, RESP_RECORD_SERIAL_END).toLong()
            val recordPatchId = data.copyOfRange(RESP_RECORD_PATCH_ID_START, RESP_RECORD_PATCH_ID_END).toLong()
            val recordSequence = data.copyOfRange(RESP_RECORD_SEQUENCE_START, RESP_RECORD_SEQUENCE_END).toInt()

            logger.debug(
                "PUMPCOMM",
                "GetRecordPacket HandleResponse: Record header: $recordHeader, unknown: $recordUnknown, type: $recordType, serial: $recordSerial, patchId: $recordPatchId, sequence: $recordSequence"
            )

            if (recordHeader == VALID_HEADER) {
                when (recordType) {
                    BOLUS_RECORD, BOLUS_RECORD_ALT -> {
                        handleBolusRecord(data)
                    }

                    BASAL_RECORD, BASAL_RECORD_ALT -> {
                        handleBasalRecord(data)
                    }

                    ALARM_RECORD                   -> {
                        logger.debug("PUMPCOMM", "GetRecordPacket HandleResponse: ALARM_RECORD")
                    }

                    AUTO_RECORD                    -> {
                        logger.debug("PUMPCOMM", "GetRecordPacket HandleResponse: AUTO_RECORD")
                    }

                    TIME_SYNC_RECORD               -> {
                        logger.debug("PUMPCOMM", "GetRecordPacket HandleResponse: TIME_SYNC_RECORD")
                    }

                    AUTO1_RECORD                   -> {
                        logger.debug("PUMPCOMM", "GetRecordPacket HandleResponse: AUTO1_RECORD")
                    }

                    AUTO2_RECORD                   -> {
                        logger.debug("PUMPCOMM", "GetRecordPacket HandleResponse: AUTO2_RECORD")
                    }

                    AUTO3_RECORD                   -> {
                        logger.debug("PUMPCOMM", "GetRecordPacket HandleResponse: AUTO3_RECORD")
                    }

                    TDD_RECORD                     -> {
                        handleTddRecord(data)
                    }

                    else                           -> {
                        logger.debug("PUMPCOMM", "GetRecordPacket HandleResponse: Unknown record type: $recordType")
                    }
                }
            } else {
                logger.error("PUMPCOMM", "GetRecordPacket HandleResponse: Invalid record header")
            }

            // Update sequence number
            state.syncedSequenceNumber = recordSequence // Assume sync upwards
        }

        return success
    }

    private fun handleBolusRecord(data: ByteArray) {
        logger.debug("PUMPCOMM", "GetRecordPacket HandleResponse: BOLUS_RECORD")
        val typeAndWizard = data.copyOfRange(RESP_RECORD_DATA_START, RESP_RECORD_DATA_START + 1).toInt()
        val bolusCause = data.copyOfRange(RESP_RECORD_DATA_START + 1, RESP_RECORD_DATA_START + 2).toInt()
        val unknown = data.copyOfRange(RESP_RECORD_DATA_START + 2, RESP_RECORD_DATA_START + 4).toInt()
        val bolusStartTime = timeUtil.convertPumpTimeToSystemTimeMillis(data.copyOfRange(RESP_RECORD_DATA_START + 4, RESP_RECORD_DATA_START + 8).toLong())
        val bolusNormalAmount = data.copyOfRange(RESP_RECORD_DATA_START + 8, RESP_RECORD_DATA_START + 10).toInt() * 0.05
        val bolusNormalDelivered = data.copyOfRange(RESP_RECORD_DATA_START + 10, RESP_RECORD_DATA_START + 12).toInt() * 0.05
        val bolusExtendedAmount = data.copyOfRange(RESP_RECORD_DATA_START + 12, RESP_RECORD_DATA_START + 14).toInt() * 0.05
        val bolusExtendedDuration = data.copyOfRange(RESP_RECORD_DATA_START + 14, RESP_RECORD_DATA_START + 16).toLong() * 60000L
        val bolusExtendedDelivered = data.copyOfRange(RESP_RECORD_DATA_START + 16, RESP_RECORD_DATA_START + 18).toInt() * 0.05
        val bolusCarb = data.copyOfRange(RESP_RECORD_DATA_START + 18, RESP_RECORD_DATA_START + 20).toInt()
        val bolusGlucose = data.copyOfRange(RESP_RECORD_DATA_START + 20, RESP_RECORD_DATA_START + 22).toInt()
        val bolusIOB = data.copyOfRange(RESP_RECORD_DATA_START + 22, RESP_RECORD_DATA_START + 24).toInt()
        val unknown1 = data.copyOfRange(RESP_RECORD_DATA_START + 24, RESP_RECORD_DATA_START + 26).toInt()
        val unknown2 = data.copyOfRange(RESP_RECORD_DATA_START + 26, RESP_RECORD_DATA_START + 28).toInt()
        val bolusType = enumValues<BolusType>()[typeAndWizard and 0x0F]
        val bolusWizard = (typeAndWizard and 0xF0) != 0
        logger.debug(
            "PUMPCOMM",
            "GetRecordPacket HandleResponse: BOLUS_RECORD: typeAndWizard: $typeAndWizard, bolusCause: $bolusCause, unknown: $unknown, bolusStartTime: $bolusStartTime, " +
                "bolusNormalAmount: $bolusNormalAmount, bolusNormalDelivered: $bolusNormalDelivered, bolusExtendedAmount: $bolusExtendedAmount, bolusExtendedDuration: " +
                "$bolusExtendedDuration, " + "bolusExtendedDelivered: $bolusExtendedDelivered, bolusCarb: $bolusCarb, bolusGlucose: $bolusGlucose, bolusIOB: $bolusIOB, unknown1: $unknown1, unknown2: $unknown2, " + "bolusType: $bolusType, bolusWizard: $bolusWizard"
        )

        val record = MedtrumBolusRecord(
            timestamp = bolusStartTime,
            normalAmount = bolusNormalAmount,
            normalDelivered = bolusNormalDelivered,
            extendedAmount = bolusExtendedAmount,
            extendedDurationMs = bolusExtendedDuration,
            extendedDelivered = bolusExtendedDelivered,
            carbs = bolusCarb,
            glucose = bolusGlucose,
            bolusType = bolusType,
            bolusWizard = bolusWizard
        )
        // The Android version chose between syncBolusWithTempId / syncBolusWithPumpId /
        // syncExtendedBolusWithPumpId based on bolusType and DetailedBolusInfoStorage lookup.
        // That persistence strategy lives in M2/M3; here we deliver the parsed record.
        recordSync.syncBolusRecord(record, state.pumpSN.toString(radix = 16))

        if (bolusStartTime > (state.lastBolusTime ?: 0L)) {
            state.lastBolusTime = bolusStartTime
            state.lastBolusAmount = bolusNormalDelivered
        }
    }

    private fun handleBasalRecord(data: ByteArray) {
        val recordPatchId = data.copyOfRange(RESP_RECORD_PATCH_ID_START, RESP_RECORD_PATCH_ID_END).toLong()
        val recordSequence = data.copyOfRange(RESP_RECORD_SEQUENCE_START, RESP_RECORD_SEQUENCE_END).toInt()

        val basalStartTime = timeUtil.convertPumpTimeToSystemTimeMillis(data.copyOfRange(RESP_RECORD_DATA_START, RESP_RECORD_DATA_START + 4).toLong())
        val basalEndTime = timeUtil.convertPumpTimeToSystemTimeMillis(data.copyOfRange(RESP_RECORD_DATA_START + 4, RESP_RECORD_DATA_START + 8).toLong())
        val basalType = enumValues<BasalType>()[data.copyOfRange(RESP_RECORD_DATA_START + 8, RESP_RECORD_DATA_START + 9).toInt()]
        val basalEndReasonInt = data.copyOfRange(RESP_RECORD_DATA_START + 9, RESP_RECORD_DATA_START + 10).toInt()
        val basalEndReason = enumValues<BasalEndReason>().getOrNull(basalEndReasonInt)
        val basalRate = data.copyOfRange(RESP_RECORD_DATA_START + 10, RESP_RECORD_DATA_START + 12).toInt() * 0.05
        val basalDelivered = data.copyOfRange(RESP_RECORD_DATA_START + 12, RESP_RECORD_DATA_START + 14).toInt() * 0.05
        val basalPercent = data.copyOfRange(RESP_RECORD_DATA_START + 14, RESP_RECORD_DATA_START + 16).toInt()

        logger.debug(
            "PUMPCOMM",
            "GetRecordPacket HandleResponse: BASAL_RECORD: Start: $basalStartTime, End: $basalEndTime, Type: $basalType, EndReason: $basalEndReason, Rate: $basalRate, Delivered: $basalDelivered, Percent: $basalPercent"
        )

        val record = MedtrumBasalRecord(
            basalType = basalType,
            basalRate = basalRate,
            basalDelivered = basalDelivered,
            basalPercent = basalPercent,
            basalSequence = recordSequence,
            basalPatchId = recordPatchId,
            basalStartTime = basalStartTime,
            basalEndTime = basalEndTime,
            basalEndReason = basalEndReason
        )
        // The Android version synced temp basals / suspends via PumpSync with per-type
        // parameters and durations. That persistence strategy lives in M2/M3.
        recordSync.syncBasalRecord(record, state.pumpSN.toString(radix = 16))

        if (basalEndReason == null) {
            logger.error("PUMPCOMM", "GetRecordPacket HandleResponse: BASAL_RECORD: Unknown basal end reason: $basalEndReasonInt")
        } else if (basalEndReason.isSuspendedByPump()) {
            // Pump doesn't seem to sync suspend/stop explicitly, so we need to do it here.
            // The Android version queried pumpSync.expectedPumpState() to decide; in M1 the
            // state update is delivered unconditionally via the state seam and M2/M3 refine it.
            logger.warn("PUMPCOMM", "GetRecordPacket HandleResponse: Got suspended end reason, syncing suspend")
            state.handleBasalStatusUpdate(BasalType.fromBasalEndReason(basalEndReason), 0.0, recordSequence, recordPatchId, basalEndTime)
        }
    }

    private fun handleTddRecord(data: ByteArray) {
        logger.debug("PUMPCOMM", "GetRecordPacket HandleResponse: TDD_RECORD")
        val timestamp = timeUtil.convertPumpTimeToSystemTimeMillis(data.copyOfRange(RESP_RECORD_DATA_START, RESP_RECORD_DATA_START + 4).toLong())
        val timeZoneOffset = data.copyOfRange(RESP_RECORD_DATA_START + 4, RESP_RECORD_DATA_START + 6).toInt()
        val tddMinutes = data.copyOfRange(RESP_RECORD_DATA_START + 6, RESP_RECORD_DATA_START + 8).toInt()
        val glucoseRecordTime = data.copyOfRange(RESP_RECORD_DATA_START + 8, RESP_RECORD_DATA_START + 12).toLong()
        val tdd = data.copyOfRange(RESP_RECORD_DATA_START + 12, RESP_RECORD_DATA_START + 16).toFloat()
        val basalTdd = data.copyOfRange(RESP_RECORD_DATA_START + 16, RESP_RECORD_DATA_START + 20).toFloat()
        val glucose = data.copyOfRange(RESP_RECORD_DATA_START + 20, RESP_RECORD_DATA_START + 24).toFloat()
        val unknown = data.copyOfRange(RESP_RECORD_DATA_START + 24, RESP_RECORD_DATA_START + 28).toFloat()
        val meanSomething = data.copyOfRange(RESP_RECORD_DATA_START + 28, RESP_RECORD_DATA_START + 32).toFloat()
        val usedTdd = data.copyOfRange(RESP_RECORD_DATA_START + 32, RESP_RECORD_DATA_START + 36).toFloat()
        val usedIBasal = data.copyOfRange(RESP_RECORD_DATA_START + 36, RESP_RECORD_DATA_START + 40).toFloat()
        val usedSgBasal = data.copyOfRange(RESP_RECORD_DATA_START + 40, RESP_RECORD_DATA_START + 44).toFloat()
        val usedUMax = data.copyOfRange(RESP_RECORD_DATA_START + 44, RESP_RECORD_DATA_START + 48).toFloat()
        val newTdd = data.copyOfRange(RESP_RECORD_DATA_START + 48, RESP_RECORD_DATA_START + 52).toFloat()
        val newIBasal = data.copyOfRange(RESP_RECORD_DATA_START + 52, RESP_RECORD_DATA_START + 56).toFloat()
        val newSgBasal = data.copyOfRange(RESP_RECORD_DATA_START + 56, RESP_RECORD_DATA_START + 60).toFloat()
        val newUMax = data.copyOfRange(RESP_RECORD_DATA_START + 60, RESP_RECORD_DATA_START + 64).toFloat()

        logger.debug(
            "PUMPCOMM", "TDD_RECORD: timestamp: $timestamp, timeZoneOffset: $timeZoneOffset, tddMinutes: $tddMinutes, glucoseRecordTime: $glucoseRecordTime, tdd: " +
                "$tdd, basalTdd: $basalTdd, glucose: $glucose, unknown: $unknown, meanSomething: $meanSomething, usedTdd: $usedTdd, usedIBasal: $usedIBasal, usedSgBasal: " +
                "$usedSgBasal, usedUMax: $usedUMax, newTdd: $newTdd, newIBasal: $newIBasal, newSgBasal: $newSgBasal, newUMax: $newUMax"
        )

        recordSync.syncTddRecord(
            MedtrumTddRecord(timestamp = timestamp, tdd = tdd.toDouble()),
            state.pumpSN.toString(radix = 16)
        )
    }
}
