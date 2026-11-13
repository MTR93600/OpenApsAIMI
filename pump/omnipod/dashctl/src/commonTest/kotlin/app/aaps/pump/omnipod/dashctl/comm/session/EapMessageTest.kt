package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.pump.omnipod.dashctl.comm.exceptions.MessageIOException
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** Port of the Android `EapMessageTest`, same vector, plus checks that Android does not have. */
class EapMessageTest {

    @Test fun testParseAndBack() {
        val payload =
            parityHex("01bd0038170100000205000000c55c78e8d3b9b9e935860a7259f6c001050000c2cd1248451103bd77a6c7ef88c441ba7e0200006cff5d18")
        val eapMsg = EapMessage.parse(SilentPodLogger, payload)
        val back = eapMsg.toByteArray()
        assertEquals(payload.toParityHex(), back.toParityHex())
    }

    // Not in Android.

    @Test
    fun `the Android challenge has AUTN then RAND then the controller IV`() {
        val eapMsg = EapMessage.parse(
            SilentPodLogger,
            parityHex("01bd0038170100000205000000c55c78e8d3b9b9e935860a7259f6c001050000c2cd1248451103bd77a6c7ef88c441ba7e0200006cff5d18")
        )
        assertEquals(EapCode.REQUEST, eapMsg.code)
        assertEquals(0xbd.toByte(), eapMsg.identifier)
        assertEquals(1, eapMsg.subType.toInt())
        assertEquals(3, eapMsg.attributes.size)
        assertEquals("00c55c78e8d3b9b9e935860a7259f6c0", assertIs<EapAkaAttributeAutn>(eapMsg.attributes[0]).payload.toParityHex())
        assertEquals("c2cd1248451103bd77a6c7ef88c441ba", assertIs<EapAkaAttributeRand>(eapMsg.attributes[1]).payload.toParityHex())
        assertEquals("6cff5d18", assertIs<EapAkaAttributeCustomIV>(eapMsg.attributes[2]).payload.toParityHex())
    }

    @Test
    fun `success has no attributes and is four bytes`() {
        val msg = EapMessage(code = EapCode.SUCCESS, identifier = 0x42, attributes = arrayOf())
        assertEquals("03420004", msg.toByteArray().toParityHex())
        val back = EapMessage.parse(SilentPodLogger, parityHex("03420004"))
        assertEquals(EapCode.SUCCESS, back.code)
        assertEquals(0x42.toByte(), back.identifier)
    }

    @Test
    fun `pod answer with RES and IV is read`() {
        val answer = EapMessage(
            code = EapCode.RESPONSE,
            identifier = 0x11,
            attributes = arrayOf(EapAkaAttributeRes(parityHex("a40bc6d13861447e")), EapAkaAttributeCustomIV(parityHex("b0b1b2b3")))
        ).toByteArray()
        assertEquals("0211001c1701000003030040a40bc6d13861447e7e020000b0b1b2b3", answer.toParityHex())

        val parsed = EapMessage.parse(SilentPodLogger, answer)
        assertEquals("a40bc6d13861447e", assertIs<EapAkaAttributeRes>(parsed.attributes[0]).payload.toParityHex())
        assertEquals("b0b1b2b3", assertIs<EapAkaAttributeCustomIV>(parsed.attributes[1]).payload.toParityHex())
    }

    @Test
    fun `AUTS of a resynchronization is read as 14 bytes`() {
        // type 4, length 4 (x4 = 16 bytes), then the 14 bytes of AUTS. No reserved bytes.
        val auts = "84ff173947a67567985de71e4890"
        val parsed = EapMessage.parse(SilentPodLogger, parityHex("02110018170400000404$auts"))
        assertEquals(EapMessage.SUBTYPE_SYNCHRONIZATION_FAILURE, parsed.subType)
        assertEquals(auts, assertIs<EapAkaAttributeAuts>(parsed.attributes.single()).payload.toParityHex())
    }

    @Test
    fun `CLIENT_ERROR_CODE is never parsed as on Android`() {
        // Android bug kept: the attribute is cut to 2 bytes, then parse wants 4.
        assertFailsWith<MessageIOException> {
            EapMessage.parse(SilentPodLogger, parityHex("0211000c17010000160100" + "01"))
        }
    }
}
