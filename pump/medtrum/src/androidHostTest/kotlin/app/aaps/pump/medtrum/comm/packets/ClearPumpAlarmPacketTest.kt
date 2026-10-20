package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.MedtrumTestBase
import app.aaps.pump.medtrum.extension.toInt
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ClearPumpAlarmPacketTest : MedtrumTestBase() {

    /** Test packet specific behavior */


    @Test fun getRequestGivenPacketWhenCalledThenReturnOpCode() {
        // Inputs
        val opCode = 115
        val clearCode = 4

        // Call
        val packet = ClearPumpAlarmPacket(clearCode)
        val result = packet.getRequest()

        // Expected values
        assertThat(result).hasLength(3)
        assertThat(result[0]).isEqualTo(opCode.toByte())
        assertThat(result.copyOfRange(1, 3).toInt()).isEqualTo(clearCode)
    }
}
