package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState

import app.aaps.pump.medtrum.comm.enums.CommandType.GET_TIME
import app.aaps.pump.medtrum.extension.toLong
import app.aaps.pump.medtrum.util.MedtrumTimeUtil

class GetTimePacket(private val state: MedtrumProtocolState, private val timeUtil: MedtrumTimeUtil = MedtrumTimeUtil(), logger: MedtrumLogger = MedtrumLogger.NO_OP) : MedtrumPacket(logger) {


    companion object {

        private const val RESP_TIME_START = 6
        private const val RESP_TIME_END = RESP_TIME_START + 4
    }

    init {
        opCode = GET_TIME.code
        expectedMinRespLength = RESP_TIME_END
    }

    override fun handleResponse(data: ByteArray): Boolean {
        val success = super.handleResponse(data)
        if (success) {
            val time = timeUtil.convertPumpTimeToSystemTimeMillis(data.copyOfRange(RESP_TIME_START, RESP_TIME_END).toLong())
            state.lastTimeReceivedFromPump = time
        }

        return success
    }
}
