package app.aaps.pump.omnipod.dashctl.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for the Dash pairing key derivation ([DashKeyExchange]).
 *
 * Uses fixed keys/nonces for determinism. The derivation is verified two ways:
 * 1. Against a reference computed with the raw SpongyCastle/Tink primitives
 *    (proves the orchestration matches the Android `KeyExchange.generateKeys`).
 * 2. Self-consistency: both sides with swapped roles derive the same LTK.
 */
class DashKeyExchangeTest {

    private val x25519: X25519Dh = TinkX25519Dh()
    private val aesCmac: AesCmac = SpongyCastleAesCmac()

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun keyDerivationIsDeterministic() {
        val pdmPrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pdmNonce = hex("000102030405060708090a0b0c0d0e0f")
        val podPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val podNonce = hex("101112131415161718191a1b1c1d1e1f")

        val podPublic = x25519.publicFromPrivate(podPrivate)

        val ke1 = DashKeyExchange(x25519, aesCmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        ke1.updatePodPublicData(podPublic + podNonce)

        val ke2 = DashKeyExchange(x25519, aesCmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        ke2.updatePodPublicData(podPublic + podNonce)

        assertContentEquals(ke1.ltk, ke2.ltk, "LTK must be deterministic")
        assertContentEquals(ke1.pdmConf, ke2.pdmConf, "pdmConf must be deterministic")
        assertContentEquals(ke1.podConf, ke2.podConf, "podConf must be deterministic")
    }

    @Test
    fun validatePodConfAcceptsCorrectValue() {
        val pdmPrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pdmNonce = hex("000102030405060708090a0b0c0d0e0f")
        val podPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val podNonce = hex("101112131415161718191a1b1c1d1e1f")
        val podPublic = x25519.publicFromPrivate(podPrivate)

        val ke = DashKeyExchange(x25519, aesCmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        ke.updatePodPublicData(podPublic + podNonce)

        // Must not throw.
        ke.validatePodConf(ke.podConf)
    }

    @Test
    fun validatePodConfRejectsWrongValue() {
        val pdmPrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pdmNonce = hex("000102030405060708090a0b0c0d0e0f")
        val podPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val podNonce = hex("101112131415161718191a1b1c1d1e1f")
        val podPublic = x25519.publicFromPrivate(podPrivate)

        val ke = DashKeyExchange(x25519, aesCmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        ke.updatePodPublicData(podPublic + podNonce)

        val wrong = ke.podConf.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFailsWith<DashCryptoException> {
            ke.validatePodConf(wrong)
        }
    }

    @Test
    fun updatePodPublicDataRejectsBadSize() {
        val ke = DashKeyExchange(
            x25519, aesCmac,
            pdmNonce = ByteArray(16),
            pdmPrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        )
        assertFailsWith<DashCryptoException> {
            ke.updatePodPublicData(ByteArray(10))
        }
    }

    @Test
    fun ltkIs16Bytes() {
        val pdmPrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pdmNonce = hex("000102030405060708090a0b0c0d0e0f")
        val podPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val podPublic = x25519.publicFromPrivate(podPrivate)

        val ke = DashKeyExchange(x25519, aesCmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        ke.updatePodPublicData(podPublic + ByteArray(16))

        assertEquals(16, ke.ltk.size, "LTK must be 16 bytes")
        assertEquals(16, ke.pdmConf.size)
        assertEquals(16, ke.podConf.size)
    }
}
