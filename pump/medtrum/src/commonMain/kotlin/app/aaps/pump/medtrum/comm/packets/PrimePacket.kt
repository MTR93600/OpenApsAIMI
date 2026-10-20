package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.enums.CommandType.PRIME

class PrimePacket() : MedtrumPacket() {

    init {
        opCode = PRIME.code
    }
}
