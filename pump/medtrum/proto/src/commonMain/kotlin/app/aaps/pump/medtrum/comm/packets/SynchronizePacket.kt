package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState
import app.aaps.pump.medtrum.util.MedtrumTimeUtil

import app.aaps.pump.medtrum.comm.enums.CommandType.SYNCHRONIZE
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.extension.toByteArray
import app.aaps.pump.medtrum.extension.toInt

class SynchronizePacket(
    private val state: MedtrumProtocolState,
    private val timeUtil: MedtrumTimeUtil = MedtrumTimeUtil(),
    logger: MedtrumLogger = MedtrumLogger.NO_OP
) : MedtrumPacket(logger) {


    companion object {

        private const val RESP_STATE_START = 6
        private const val RESP_FIELDS_START = 7
        private const val RESP_FIELDS_END = RESP_FIELDS_START + 2
        private const val RESP_SYNC_DATA_START = 9

        private const val MASK_SUSPEND = 0x01
        private const val MASK_NORMAL_BOLUS = 0x02
        private const val MASK_EXTENDED_BOLUS = 0x04
    }

    init {
        opCode = SYNCHRONIZE.code
        expectedMinRespLength = RESP_SYNC_DATA_START + 1
    }

    override fun handleResponse(data: ByteArray): Boolean {
        var success = super.handleResponse(data)
        if (success) {
            val syncState = MedtrumPumpState.fromByte(data[RESP_STATE_START])

            logger.debug("PUMPCOMM", "SynchronizePacket: state: $syncState")
            if (syncState != state.pumpState) {
                logger.debug("PUMPCOMM", "State changed from ${state.pumpState} to $syncState")
                state.pumpState = syncState
            }

            var fieldMask = data.copyOfRange(RESP_FIELDS_START, RESP_FIELDS_END).toInt()
            var syncData = data.copyOfRange(RESP_SYNC_DATA_START, data.size)
            var offset = 0

            if (fieldMask != 0) {
                logger.debug("PUMPCOMM", "SynchronizePacket: fieldMask: $fieldMask")
            }

            // Remove extended bolus field from fieldMask if field is present (extended bolus is not supported)
            if (fieldMask and MASK_SUSPEND != 0) {
                offset += 4 // If field is present, skip 4 bytes
            }
            if (fieldMask and MASK_NORMAL_BOLUS != 0) {
                offset += 3 // If field is present, skip 3 bytes
            }
            if (fieldMask and MASK_EXTENDED_BOLUS != 0) {
                logger.debug("PUMPCOMM", "SynchronizePacket: Extended bolus present removing from fieldMask")
                fieldMask = fieldMask and MASK_EXTENDED_BOLUS.inv()
                syncData = syncData.copyOfRange(0, offset) + syncData.copyOfRange(offset + 3, syncData.size)
            }

            // Let the notification packet handle the rest of the sync data
            success = NotificationPacket(state, timeUtil, logger).handleMaskedMessage(fieldMask.toByteArray(2) + syncData)
        }

        return success
    }
}
