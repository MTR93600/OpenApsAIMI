package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.enums.CommandType.CLEAR_ALARM
import app.aaps.pump.medtrum.extension.toByteArray

class ClearPumpAlarmPacket(private val clearType: Int) : MedtrumPacket() {

    init {
        opCode = CLEAR_ALARM.code
    }

    override fun getRequest(): ByteArray {
        return byteArrayOf(opCode) + clearType.toByteArray(2)
    }
}
