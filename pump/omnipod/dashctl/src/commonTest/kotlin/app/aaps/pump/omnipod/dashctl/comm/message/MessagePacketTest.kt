package app.aaps.pump.omnipod.dashctl.comm.message

import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Port of the Android `MessagePacketTest`, same test vector. */
class MessagePacketTest {

    private val payload =
        "54,57,11,01,07,00,03,40,08,20,2e,a8,08,20,2e,a9,ab,35,d8,31,60,9b,b8,fe,3a,3b,de,5b,18,37,24,9a,16,db,f8,e4,d3,05,e9,75,dc,81,7c,37,07,cc,41,5f,af,8a".replace(
            ",",
            ""
        )

    @Test fun testParseMessagePacket() {
        val msg = MessagePacket.parse(parityHex(payload))
        assertEquals(MessageType.ENCRYPTED, msg.type)
        assertEquals(Id.fromLong(136326824), msg.source)
        assertEquals(Id.fromLong(136326825), msg.destination)
        assertEquals(7.toByte(), msg.sequenceNumber)
        assertEquals(0.toByte(), msg.ackNumber)
        assertEquals(1.toShort(), msg.eqos)
        assertFalse(msg.priority)
        assertFalse(msg.lastMessage)
        assertFalse(msg.gateway)
        assertTrue(msg.sas)
        assertFalse(msg.tfs)
        assertEquals(0.toShort(), msg.version)
        assertEquals(msg.payload.toParityHex(), payload.substring(32, payload.length))
    }

    @Test fun testSerializeMessagePacket() {
        val msg = MessagePacket(
            type = MessageType.ENCRYPTED,
            source = Id.fromLong(136326824),
            destination = Id.fromLong(136326825),
            sequenceNumber = 7.toByte(),
            ackNumber = 0.toByte(),
            eqos = 1.toShort(),
            priority = false,
            lastMessage = false,
            gateway = false,
            sas = true,
            tfs = false,
            payload = parityHex(payload.substring(32, payload.length))
        )
        assertEquals(payload, msg.asByteArray().toParityHex())
    }
}
