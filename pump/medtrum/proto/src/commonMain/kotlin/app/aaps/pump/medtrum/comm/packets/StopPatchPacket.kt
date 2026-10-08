package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState

import app.aaps.pump.medtrum.comm.enums.CommandType.STOP_PATCH
import app.aaps.pump.medtrum.extension.toInt
import app.aaps.pump.medtrum.extension.toLong

class StopPatchPacket(private val state: MedtrumProtocolState, logger: MedtrumLogger = MedtrumLogger.NO_OP) : MedtrumPacket(logger) {


    companion object {

        private const val RESP_STOP_SEQUENCE_START = 6
        private const val RESP_STOP_SEQUENCE_END = RESP_STOP_SEQUENCE_START + 2
        private const val RESP_STOP_PATCH_ID_START = RESP_STOP_SEQUENCE_END
        private const val RESP_STOP_PATCH_ID_END = RESP_STOP_PATCH_ID_START + 2
    }

    init {
        opCode = STOP_PATCH.code
        expectedMinRespLength = RESP_STOP_PATCH_ID_END
    }

    override fun handleResponse(data: ByteArray): Boolean {
        val success = super.handleResponse(data)
        if (success) {
            val stopSequence = data.copyOfRange(RESP_STOP_SEQUENCE_START, RESP_STOP_SEQUENCE_END).toInt()
            val stopPatchId = data.copyOfRange(RESP_STOP_PATCH_ID_START, RESP_STOP_PATCH_ID_END).toLong()

            state.handleStopStatusUpdate(stopSequence, stopPatchId)
        }
        return success
    }
}
