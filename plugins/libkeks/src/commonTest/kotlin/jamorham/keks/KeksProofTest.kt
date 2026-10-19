package jamorham.keks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The zero knowledge proof, which is what makes each round of the handshake believable.
 */
class KeksProofTest {

    @Test
    fun proofHashMatchesJava() {
        for (row in KeksGolden.zeroKnowledgeHash) {
            val hash = Calc.getZeroKnowledgeHash(pointOf(row[0]), pointOf(row[1]), pointOf(row[2]), hexBytes(row[3]))
            assertEquals(row[4], hash.toHex(), "party ${row[3]}")
        }
    }

    @Test
    fun aJavaProofIsAcceptedAndADifferentPartyIsNot() {
        for (row in KeksGolden.zeroKnowledgeProof) {
            val ok = Calc.validateZeroKnowledgeProof(
                pointOf(row[0]), pointOf(row[1]), pointOf(row[2]), bigOf(row[3]), hexBytes(row[4]),
            )
            assertEquals(row[5].toBoolean(), ok, "proof should verify")
            assertTrue(ok)
            val bad = Calc.validateZeroKnowledgeProof(
                pointOf(row[0]), pointOf(row[1]), pointOf(row[2]), bigOf(row[3]),
                Config.Get.BOB.bytes,
            )
            assertEquals(row[6].toBoolean(), bad, "the same proof under another party")
            assertFalse(bad)
        }
    }

    @Test
    fun packetSerialisationMatchesJava() {
        for (row in KeksGolden.packet) {
            val packet = Packet(bigOf(row[0]), pointOf(row[1]), pointOf(row[2]))
            assertEquals(row[3].take(KeksGolden.packetSize * 2), packet.output().hex())
            val parsed = Packet.parse(hexBytes(row[3]))!!
            assertEquals(row[4], parsed.hash.toHex())
            assertEquals(row[5], parsed.publicKeyPoint1.getEncoded().hex())
            assertEquals(row[6], parsed.publicKeyPoint2.getEncoded().hex())
        }
    }

    @Test
    fun aShortPacketIsNotParsed() {
        assertTrue(KeksGolden.packetShortInputIsNull)
        assertEquals(null, Packet.parse(ByteArray(KeksGolden.packetSize - 1)))
    }
}
