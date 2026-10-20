package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.enums.CommandType.POLL_PATCH

class PollPatchPacket() : MedtrumPacket() {

    init {
        opCode = POLL_PATCH.code
    }
}
