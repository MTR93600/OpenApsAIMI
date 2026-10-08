package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.FakeMedtrumProtocolState
import app.aaps.pump.medtrum.comm.MedtrumLogger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ported from the Android driver (pump/medtrum). The Dagger injector is replaced
 * by explicit constructor parameters; Truth assertions by kotlin.test.
 */
class SetBolusPacketTest {

    private val state = FakeMedtrumProtocolState()
    private val logger = MedtrumLogger.NO_OP

    @Test fun getRequestGivenPacketWhenCalledThenReturnOpCode() {
        // Inputs
        val insulin = 2.35

        // Call
        val packet = SetBolusPacket(insulin)
        val result = packet.getRequest()

        // Expected values (from the Android test — ground truth)
        val expected = byteArrayOf(19, 1, 47, 0, 0)
        assertContentEquals(expected, result)
    }

    @Test fun handleResponseGivenValidResponseThenReturnTrue() {
        // A minimal valid response: opcode 19, result 0
        val data = byteArrayOf(0, 19, 0, 0, 0, 0)
        val packet = SetBolusPacket(2.35)
        assertTrue(packet.handleResponse(data))
        assertFalse(packet.failed)
    }
}
