package app.aaps.pump.omnipod.dashctl.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * [AesEcbBlockCipher] on `javax.crypto`, matching the Android `Milenage` construction exactly:
 * `Cipher.getInstance("AES/ECB/NoPadding")` in encrypt mode.
 */
class JavaxAesEcbBlockCipher : AesEcbBlockCipher {

    override fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(key.size == 16) { "AES key must be 16 bytes" }
        require(block.size == 16) { "AES block must be 16 bytes" }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }
}
