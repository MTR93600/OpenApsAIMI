package app.aaps.pump.omnipod.dashctl.ios.crypto

import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D2 vectors against the iOS crypto seams.
 *
 * Same vectors as the D2 gate (RFC 7748 §6.1, 3GPP TS 35.207 Test Set 1):
 * zero deviation tolerated. Runs on device/simulator; the sandbox cannot
 * execute Kotlin/Native.
 */
class IosDashCryptoVectorsTest {

    private val ccm = IosAesCcmCipher()
    private val cmac = IosAesCmac()
    private val ecb = IosAesEcbBlockCipher()
    private val x25519 = IosX25519Dh()
    private val rng = IosSecureRandomBytes()

    // -- X25519 (RFC 7748 §6.1) --------------------------------------------

    @Test
    fun x25519AlicePublic() {
        val private = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val expected = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        assertContentEquals(expected, x25519.publicFromPrivate(private))
    }

    @Test
    fun x25519BobPublic() {
        val private = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val expected = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertContentEquals(expected, x25519.publicFromPrivate(private))
    }

    @Test
    fun x25519SharedSecret() {
        val alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
        assertContentEquals(expected, x25519.computeSharedSecret(alicePrivate, bobPublic))
    }

    // -- AES-CCM (roundtrip + tamper) ---------------------------------------

    @Test
    fun aesCcmRoundtrip() {
        val key = hex("404142434445464748494a4b4c4d4e4f")
        val nonce = hex("101112131415161718191a1b")
        val aad = hex("0001020304050607")
        val plaintext = "Hello, pod!".encodeToByteArray()
        val encrypted = ccm.encrypt(key, nonce, aad, plaintext, 8)
        assertContentEquals(plaintext, ccm.decrypt(key, nonce, aad, encrypted, 8))
    }

    @Test
    fun aesCcmTamperFails() {
        val key = hex("404142434445464748494a4b4c4d4e4f")
        val nonce = hex("101112131415161718191a1b")
        val aad = hex("0001020304050607")
        val plaintext = "Hello, pod!".encodeToByteArray()
        val encrypted = ccm.encrypt(key, nonce, aad, plaintext, 8).copyOf()
        encrypted[0] = (encrypted[0].toInt() xor 0xFF).toByte()
        assertFailsWith<DashCryptoException> {
            ccm.decrypt(key, nonce, aad, encrypted, 8)
        }
    }

    @Test
    fun aesCcmAadTamperFails() {
        val key = hex("404142434445464748494a4b4c4d4e4f")
        val nonce = hex("101112131415161718191a1b")
        val aad = hex("0001020304050607")
        val plaintext = "Hello, pod!".encodeToByteArray()
        val encrypted = ccm.encrypt(key, nonce, aad, plaintext, 8)
        val badAad = hex("0001020304050608")
        assertFailsWith<DashCryptoException> {
            ccm.decrypt(key, nonce, badAad, encrypted, 8)
        }
    }

    // -- AES-CMAC (NIST SP 800-38B test vector) -------------------------------

    @Test
    fun aesCmacNistVector() {
        // NIST SP 800-38B example: key 2b7e1516…, message empty → bb1d6929e95937287fa37d129b756746
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val expected = hex("bb1d6929e95937287fa37d129b756746")
        assertContentEquals(expected, cmac.compute(key, ByteArray(0)))
    }

    // -- AES-ECB (FIPS-197 appendix B) ----------------------------------------

    @Test
    fun aesEcbFipsVector() {
        val key = hex("2b7e151628aed2a6abf7158809cf4f3c")
        val plain = hex("3243f6a8885a308d313198a2e0370734")
        val expected = hex("3925841d02dc09fbdc118597196a0b32")
        assertContentEquals(expected, ecb.encryptBlock(key, plain))
    }

    // -- SecureRandom ---------------------------------------------------------

    @Test
    fun secureRandomLengthAndNonRepeating() {
        val a = rng.nextBytes(32)
        val b = rng.nextBytes(32)
        assertTrue(a.size == 32 && b.size == 32)
        assertTrue(!a.contentEquals(b), "two random draws should differ")
    }

    private fun hex(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
