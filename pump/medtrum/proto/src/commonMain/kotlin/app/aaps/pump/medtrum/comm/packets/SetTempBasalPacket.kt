package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState

import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.CommandType.SET_TEMP_BASAL
import app.aaps.pump.medtrum.extension.toByteArray
import app.aaps.pump.medtrum.extension.toInt
import app.aaps.pump.medtrum.extension.toLong
import app.aaps.pump.medtrum.util.MedtrumTimeUtil
import kotlin.math.round

class SetTempBasalPacket(private val state: MedtrumProtocolState, logger: MedtrumLogger = MedtrumLogger.NO_OP, private val timeUtil: MedtrumTimeUtil = MedtrumTimeUtil(), private val absoluteRate: Double, private val durationInMinutes: Int) : MedtrumPacket(logger) {


    companion object {

        private const val RESP_BASAL_TYPE_START = 6
        private const val RESP_BASAL_TYPE_END = RESP_BASAL_TYPE_START + 1
        private const val RESP_BASAL_RATE_START = RESP_BASAL_TYPE_END
        private const val RESP_BASAL_RATE_END = RESP_BASAL_RATE_START + 2
        private const val RESP_BASAL_SEQUENCE_START = RESP_BASAL_RATE_END
        private const val RESP_BASAL_SEQUENCE_END = RESP_BASAL_SEQUENCE_START + 2
        private const val RESP_BASAL_PATCH_ID_START = RESP_BASAL_SEQUENCE_END
        private const val RESP_BASAL_PATCH_ID_END = RESP_BASAL_PATCH_ID_START + 2
        private const val RESP_BASAL_START_TIME_START = RESP_BASAL_PATCH_ID_END
        private const val RESP_BASAL_START_TIME_END = RESP_BASAL_START_TIME_START + 4
    }

    init {
        opCode = SET_TEMP_BASAL.code
        expectedMinRespLength = RESP_BASAL_START_TIME_END
    }

    override fun getRequest(): ByteArray {
        /**
         * byte 0: opCode
         * byte 1: tempBasalType
         * byte 2-3: tempBasalRate
         * byte 4-5: tempBasalDuration
         */
        val tempBasalType: Byte = 6 // Fixed to temp basal value for now
        val tempBasalRate: Int = round(absoluteRate / 0.05).toInt()
        val tempBasalDuration: Int = durationInMinutes
        return byteArrayOf(opCode) + tempBasalType + tempBasalRate.toByteArray(2) + tempBasalDuration.toByteArray(2)
    }

    override fun handleResponse(data: ByteArray): Boolean {
        val success = super.handleResponse(data)
        if (success) {
            val basalType = enumValues<BasalType>()[data.copyOfRange(RESP_BASAL_TYPE_START, RESP_BASAL_TYPE_END).toInt()]
            val basalRate = data.copyOfRange(RESP_BASAL_RATE_START, RESP_BASAL_RATE_END).toInt() * 0.05
            val basalSequence = data.copyOfRange(RESP_BASAL_SEQUENCE_START, RESP_BASAL_SEQUENCE_END).toInt()
            val basalPatchId = data.copyOfRange(RESP_BASAL_PATCH_ID_START, RESP_BASAL_PATCH_ID_END).toLong()

            val rawTime = data.copyOfRange(RESP_BASAL_START_TIME_START, RESP_BASAL_START_TIME_END).toLong()
            val basalStartTime = timeUtil.convertPumpTimeToSystemTimeMillis(data.copyOfRange(RESP_BASAL_START_TIME_START, RESP_BASAL_START_TIME_END).toLong())
            logger.debug("PUMPCOMM", "Basal status update: type=$basalType, rate=$basalRate, sequence=$basalSequence, patchId=$basalPatchId, startTime=$basalStartTime, rawTime=$rawTime")

            state.handleBasalStatusUpdate(basalType, basalRate, basalSequence, basalPatchId, basalStartTime)
        }
        return success
    }
}
