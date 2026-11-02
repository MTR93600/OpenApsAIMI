package app.aaps.plugins.eversense.packets.e3

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GetBatteryPercentagePacketTest {

    private fun makePacket(value: Int): GetBatteryPercentagePacket {
        val packet = GetBatteryPercentagePacket()
        packet.appendData(intArrayOf(0, 0, 0, 0, value).map { it.toUByte() }.toUByteArray())
        return packet
    }

    @Test
    fun `empty receivedData returns null`() {
        assertNull(GetBatteryPercentagePacket().parseResponse())
    }

    @Test
    fun `index 0 is 0 percent`() {
        assertEquals(0, makePacket(0).parseResponse()?.percentage)
    }

    @Test
    fun `index 1 is 5 percent`() {
        assertEquals(5, makePacket(1).parseResponse()?.percentage)
    }

    @Test
    fun `index 2 is 10 percent`() {
        assertEquals(10, makePacket(2).parseResponse()?.percentage)
    }

    @Test
    fun `index 3 is 25 percent`() {
        assertEquals(25, makePacket(3).parseResponse()?.percentage)
    }

    @Test
    fun `index 11 is 100 percent`() {
        assertEquals(100, makePacket(11).parseResponse()?.percentage)
    }

    @Test
    fun `out of range index is unknown`() {
        assertEquals(-1, makePacket(255).parseResponse()?.percentage)
    }

    @Test
    fun `an index just above the table is unknown`() {
        // 255 is also the code of BatteryLevel.UNKNOWN itself, so it does not exercise the
        // "no entry matches" fallback. 12 does.
        assertEquals(-1, makePacket(12).parseResponse()?.percentage)
    }

    @Test
    fun `raw index is kept`() {
        assertEquals(2, makePacket(2).parseResponse()?.rawLevel)
    }
}
