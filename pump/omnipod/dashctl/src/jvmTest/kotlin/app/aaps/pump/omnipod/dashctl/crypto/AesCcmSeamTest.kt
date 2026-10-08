package app.aaps.pump.omnipod.dashctl.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import org.spongycastle.crypto.engines.AESEngine
import org.spongycastle.crypto.modes.CCMBlockCipher
import org.spongycastle.crypto.params.AEADParameters
import org.spongycastle.crypto.params.KeyParameter

/**
 * Bit-exact gate for the Dash AES-CCM seam.
 *
 * The critical property: the seam must behave identically to the Android `EnDecrypt`
 * construction (`CCMBlockCipher(AESEngine())` with 64-bit MAC, header as AAD).
 * Verified by:
 * 1. Cross-check: encrypt with the raw SpongyCastle construction (as Android does),
 *    decrypt with the seam — must match.
 * 2. Roundtrip through the seam.
 * 3. Tamper detection (ciphertext and AAD).
 */
class AesCcmSeamTest {

    private val ccm: AesCcmCipher = SpongyCastleAesCcm()

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    /** Raw SpongyCastle encryption, exactly as the Android `EnDecrypt.encrypt` does it. */
    private fun androidEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        header: ByteArray,
        payload: ByteArray
    ): ByteArray {
        val cipher = CCMBlockCipher(AESEngine())
        cipher.init(true, AEADParameters(KeyParameter(key), 64, nonce, header))
        val out = ByteArray(payload.size + 8)
        cipher.processPacket(payload, 0, payload.size, out, 0)
        return out
    }

    @Test
    fun seamDecryptsAndroidCiphertext() {
        val key = hex("00112233445566778899aabbccddeeff")
        val nonce = hex("aabbccddeeff00112233445566")
        val header = hex("000102030405060708090a0b0c0d0e0f")
        val payload = "Bolus 1.5U".encodeToByteArray()

        // Encrypt with the raw Android construction...
        val androidCiphertext = androidEncrypt(key, nonce, header, payload)
        // ...decrypt with the seam.
        val decrypted = ccm.decrypt(key, nonce, header, androidCiphertext, 8)
        assertContentEquals(payload, decrypted)
    }

    @Test
    fun seamEncryptMatchesAndroid() {
        val key = hex("00112233445566778899aabbccddeeff")
        val nonce = hex("aabbccddeeff00112233445566")
        val header = hex("000102030405060708090a0b0c0d0e0f")
        val payload = "Bolus 1.5U".encodeToByteArray()

        val seamCiphertext = ccm.encrypt(key, nonce, header, payload, 8)
        val androidCiphertext = androidEncrypt(key, nonce, header, payload)
        assertContentEquals(androidCiphertext, seamCiphertext)
    }

    @Test
    fun roundtrip() {
        val key = hex("00112233445566778899aabbccddeeff")
        val nonce = hex("aabbccddeeff00112233445566")
        val aad = hex("000102030405060708090a0b0c0d0e0f")
        val plaintext = "Hello, pod!".encodeToByteArray()
        val out = ccm.encrypt(key, nonce, aad, plaintext, 8)
        assertContentEquals(plaintext, ccm.decrypt(key, nonce, aad, out, 8))
    }

    @Test
    fun tamperedCiphertextFails() {
        val key = hex("00112233445566778899aabbccddeeff")
        val nonce = hex("aabbccddeeff00112233445566")
        val aad = hex("000102030405060708090a0b0c0d0e0f")
        val out = ccm.encrypt(key, nonce, aad, "Hello, pod!".encodeToByteArray(), 8)
        out[0] = (out[0].toInt() xor 0x01).toByte()
        assertFailsWith<DashCryptoException> {
            ccm.decrypt(key, nonce, aad, out, 8)
        }
    }

    @Test
    fun tamperedAadFails() {
        val key = hex("00112233445566778899aabbccddeeff")
        val nonce = hex("aabbccddeeff00112233445566")
        val aad = hex("000102030405060708090a0b0c0d0e0f")
        val out = ccm.encrypt(key, nonce, aad, "Hello, pod!".encodeToByteArray(), 8)
        val badAad = aad.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        assertFailsWith<DashCryptoException> {
            ccm.decrypt(key, nonce, badAad, out, 8)
        }
    }
}
