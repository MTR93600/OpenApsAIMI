package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.enums.CommandType.RESUME_PUMP

class ResumePumpPacket() : MedtrumPacket() {

    init {
        opCode = RESUME_PUMP.code
    }

    override fun getRequest(): ByteArray {
        return byteArrayOf(opCode)
    }
}
