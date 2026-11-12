package app.aaps.pump.omnipod.dashctl.comm.packet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Port of the Android `PayloadSplitterTest`, same vectors. */
class PayloadSplitterTest {

    @Test fun testSplitter() {
        val f1 = "00,01,54,57,10,23,03,00,00,c0,ff,ff,ff,fe,08,20,2e,a8,50,30".replace(",", "")
        val f2 = "01,04,bc,20,1f,f6,3d,00,01,a5,ff,ff,ff,fe,08,20,2e,a8,50,30".replace(",", "")
        val payload = packetHex("54,57,10,23,03,00,00,c0,ff,ff,ff,fe,08,20,2e,a8,50,30,3d,00,01,a5".replace(",", ""))

        val splitter = PayloadSplitter(payload)
        val packets = splitter.splitInPackets()

        assertEquals(2, packets.size)
        assertEquals(f1, packets[0].toByteArray().toPacketHex())
        val p2 = packets[1].toByteArray()
        assertTrue(p2.size >= 10)
        assertEquals(f2.substring(0, 20), p2.copyOfRange(0, 10).toPacketHex())
    }
}
