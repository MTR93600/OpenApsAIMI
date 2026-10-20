package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.CommandType.CANCEL_TEMP_BASAL
import app.aaps.pump.medtrum.extension.toInt
import app.aaps.pump.medtrum.extension.toLong
import app.aaps.pump.medtrum.util.MedtrumTimeUtil

/**
 * Cancel temp basal packet — ported from the Android driver (pump/medtrum).
 * The unused `DateUtil` injection is dropped (dead code in the original).
 */
class CancelTempBasalPacket(
    private val state: MedtrumProtocolState,
    private val timeUtil: MedtrumTimeUtil = MedtrumTimeUtil(),
    logger: MedtrumLogger = MedtrumLogger.NO_OP
) : MedtrumPacket(logger) {

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
        opCode = CANCEL_TEMP_BASAL.code
        expectedMinRespLength = RESP_BASAL_START_TIME_END
    }

    override fun handleResponse(data: ByteArray): Boolean {
        val success = super.handleResponse(data)
        if (success) {
            val basalType = enumValues<BasalType>()[data.copyOfRange(RESP_BASAL_TYPE_START, RESP_BASAL_TYPE_END).toInt()]
            val basalRate = data.copyOfRange(RESP_BASAL_RATE_START, RESP_BASAL_RATE_END).toInt() * 0.05
            val basalSequence = data.copyOfRange(RESP_BASAL_SEQUENCE_START, RESP_BASAL_SEQUENCE_END).toInt()
            val basalPatchId = data.copyOfRange(RESP_BASAL_PATCH_ID_START, RESP_BASAL_PATCH_ID_END).toLong()
            val basalStartTime = timeUtil.convertPumpTimeToSystemTimeMillis(data.copyOfRange(RESP_BASAL_START_TIME_START, RESP_BASAL_START_TIME_END).toLong())

            state.handleBasalStatusUpdate(basalType, basalRate, basalSequence, basalPatchId, basalStartTime)
        }
        return success
    }
}
