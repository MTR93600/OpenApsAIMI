package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.enums.CommandType.SUBSCRIBE
import app.aaps.pump.medtrum.extension.toByteArray

class SubscribePacket() : MedtrumPacket() {

    init {
        opCode = SUBSCRIBE.code
    }

    override fun getRequest(): ByteArray {
        return byteArrayOf(opCode) + 4095.toByteArray(2)
    }
}
