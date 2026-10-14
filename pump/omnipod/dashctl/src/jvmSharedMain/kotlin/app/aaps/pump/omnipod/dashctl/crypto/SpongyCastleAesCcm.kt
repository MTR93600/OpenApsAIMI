package app.aaps.pump.omnipod.dashctl.crypto

import org.spongycastle.crypto.engines.AESEngine
import org.spongycastle.crypto.modes.CCMBlockCipher
import org.spongycastle.crypto.params.AEADParameters
import org.spongycastle.crypto.params.KeyParameter

/**
 * [AesCcmCipher] on SpongyCastle, matching the Android `EnDecrypt` construction exactly:
 * `CCMBlockCipher(AESEngine())` with `AEADParameters(KeyParameter(ck), macSizeBits, nonce, header)`.
 */
class SpongyCastleAesCcm : AesCcmCipher {

    override fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
        macSizeBytes: Int
    ): ByteArray {
        val cipher = CCMBlockCipher(AESEngine())
        cipher.init(
            true,
            AEADParameters(KeyParameter(key), macSizeBytes * 8, nonce, associatedData)
        )
        val out = ByteArray(plaintext.size + macSizeBytes)
        cipher.processPacket(plaintext, 0, plaintext.size, out, 0)
        return out
    }

    override fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        ciphertextWithMac: ByteArray,
        macSizeBytes: Int
    ): ByteArray {
        val cipher = CCMBlockCipher(AESEngine())
        cipher.init(
            false,
            AEADParameters(KeyParameter(key), macSizeBytes * 8, nonce, associatedData)
        )
        val out = ByteArray(ciphertextWithMac.size - macSizeBytes)
        try {
            cipher.processPacket(ciphertextWithMac, 0, ciphertextWithMac.size, out, 0)
        } catch (e: Exception) {
            throw DashCryptoException("AES-CCM authentication failed", e)
        }
        return out
    }
}
