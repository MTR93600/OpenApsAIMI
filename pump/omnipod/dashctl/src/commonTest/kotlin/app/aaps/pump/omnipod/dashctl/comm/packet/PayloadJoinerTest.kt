package app.aaps.pump.omnipod.dashctl.comm.packet

import kotlin.test.Test
import kotlin.test.assertEquals

/** Port of the Android `PayloadJoinerTest`, same vectors. */
class PayloadJoinerTest {

    @Test fun testJoiner() {
        val f1 = packetHex("00,01,54,57,10,23,03,00,00,c0,ff,ff,ff,fe,08,20,2e,a8,50,30".replace(",", ""))
        val f2 = packetHex("01,04,bc,20,1f,f6,3d,00,01,a5,ff,ff,ff,fe,08,20,2e,a8,50,30".replace(",", ""))
        val payload = "54,57,10,23,03,00,00,c0,ff,ff,ff,fe,08,20,2e,a8,50,30,3d,00,01,a5".replace(",", "")
        val joiner = PayloadJoiner(f1)
        joiner.accumulate(f2)
        val result = joiner.finalize()
        assertEquals(payload, result.toPacketHex())
    }
}
