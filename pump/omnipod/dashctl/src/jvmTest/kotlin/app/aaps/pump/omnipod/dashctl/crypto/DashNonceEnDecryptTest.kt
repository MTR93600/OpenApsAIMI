package app.aaps.pump.omnipod.dashctl.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Tests for [DashNonce] (pure-Kotlin port of the Android `Nonce`) and
 * [DashEnDecrypt] (message authenticated encryption orchestration).
 */
class DashNonceEnDecryptTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun nonceLayoutMatchesAndroid() {
        // prefix (8) + sqn bytes 3..7 of big-endian long, direction bit in byte 8.
        // podReceiving=true -> MSB CLEAR (and 127); podReceiving=false -> MSB SET (or 128).
        val prefix = hex("aabbccddeeff0011")
        val nonce = DashNonce(prefix, 0L)

        // First increment: sqn=1, podReceiving=true -> MSB clear.
        val n1 = nonce.increment(podReceiving = true)
        assertEquals(13, n1.size)
        assertContentEquals(prefix, n1.copyOfRange(0, 8))
        // sqn=1 -> bytes 3..7 of 0x0000000000000001 = 00 00 00 00 01, MSB clear.
        assertContentEquals(
            byteArrayOf(0x00.toByte(), 0x00, 0x00, 0x00, 0x01),
            n1.copyOfRange(8, 13)
        )

        // podReceiving=false -> MSB set.
        val n2 = nonce.increment(podReceiving = false)
        assertContentEquals(
            byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00, 0x02),
            n2.copyOfRange(8, 13)
        )
    }

    @Test
    fun nonceSequenceIncrements() {
        val nonce = DashNonce(ByteArray(8), 0L)
        val n1 = nonce.increment(true)
        val n2 = nonce.increment(true)
        // Last byte increments: 1 -> 2.
        assertEquals(0x01.toByte(), n1[12])
        assertEquals(0x02.toByte(), n2[12])
    }

    @Test
    fun enDecryptRoundtrip() {
        // The DashEnDecrypt.encrypt/decrypt hardcode opposite directions (as the Android
        // EnDecrypt does): encrypt is PDM->pod (MSB clear), decrypt is pod->PDM (MSB set).
        // A roundtrip requires simulating both sides with matching nonces.
        val ccm = SpongyCastleAesCcm()
        val ck = hex("00112233445566778899aabbccddeeff")
        val prefix = hex("aabbccddeeff0011")
        val header = hex("000102030405060708090a0b0c0d0e0f")

        // PDM side encrypts (podReceiving=true).
        val pdmNonce = DashNonce(prefix, 0L)
        val nEnc = pdmNonce.increment(podReceiving = true)
        val payload = "Bolus 1.5U".encodeToByteArray()
        val encrypted = ccm.encrypt(ck, nEnc, header, payload, 8)
        assertEquals(payload.size + 8, encrypted.size)

        // Pod side decrypts with the same nonce parameters.
        val podNonce = DashNonce(prefix, 0L)
        val nDec = podNonce.increment(podReceiving = true)
        assertContentEquals(nEnc, nDec)
        val decrypted = ccm.decrypt(ck, nDec, header, encrypted, 8)
        assertContentEquals(payload, decrypted)
    }

    @Test
    fun enDecryptPodToPdmRoundtrip() {
        val ccm = SpongyCastleAesCcm()
        val ck = ByteArray(16) { it.toByte() }
        val prefix = ByteArray(8) { (it + 1).toByte() }
        val header = ByteArray(16)

        // Pod sends to PDM: both sides use podReceiving=false (MSB set).
        // We simulate by calling the nonce directly.
        val podNonce = DashNonce(prefix, 0L)
        val pdmNonce = DashNonce(prefix, 0L)

        val n1 = podNonce.increment(podReceiving = false)
        val payload = ByteArray(32) { it.toByte() }
        val encrypted = ccm.encrypt(ck, n1, header, payload, 8)

        val n2 = pdmNonce.increment(podReceiving = false)
        assertContentEquals(n1, n2, "Nonces must match for the same direction")
        val decrypted = ccm.decrypt(ck, n2, header, encrypted, 8)
        assertContentEquals(payload, decrypted)
    }
}
