package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState

import app.aaps.pump.medtrum.comm.enums.CommandType.SET_TIME
import app.aaps.pump.medtrum.extension.toByteArray
import app.aaps.pump.medtrum.util.MedtrumTimeUtil

class SetTimePacket(private val state: MedtrumProtocolState, private val timeUtil: MedtrumTimeUtil = MedtrumTimeUtil(), logger: MedtrumLogger = MedtrumLogger.NO_OP) : MedtrumPacket(logger) {


    init {
        opCode = SET_TIME.code
    }

    override fun getRequest(): ByteArray {
        val time = timeUtil.getCurrentTimePumpSeconds()
        return byteArrayOf(opCode) + 2.toByte() + time.toByteArray(4)
    }
}
