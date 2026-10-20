package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.MedtrumTestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ResumePumpPacketTest : MedtrumTestBase() {

    /** Test packet specific behavior */


    @Test fun getRequestGivenPacketWhenCalledThenReturnOpCode() {
        // Inputs
        val opCode = 29

        // Call
        val packet = ResumePumpPacket()
        val result = packet.getRequest()

        // Expected values
        assertThat(result).asList().containsExactly(opCode.toByte())
    }
}
