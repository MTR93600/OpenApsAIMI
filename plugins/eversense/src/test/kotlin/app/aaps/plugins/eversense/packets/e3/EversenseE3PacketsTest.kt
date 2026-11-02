package app.aaps.plugins.eversense.packets.e3

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EversenseE3PacketsTest {

    @Test
    fun `keep alive push is a push packet`() {
        assertTrue(EversenseE3Packets.isPushPacket(80.toByte()))
    }

    @Test
    fun `transmitter battery push is a push packet`() {
        assertTrue(EversenseE3Packets.isPushPacket(71.toByte()))
    }

    @Test
    fun `sensor read alert push is a push packet`() {
        assertTrue(EversenseE3Packets.isPushPacket(73.toByte()))
    }

    @Test
    fun `an unrelated byte is not a push packet`() {
        assertFalse(EversenseE3Packets.isPushPacket(0x00.toByte()))
    }
}
