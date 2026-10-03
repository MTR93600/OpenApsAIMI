package app.aaps.core.objects.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * JCE `AES/ECB/NoPadding`. One block, so ECB does not chain anything. SunJCE (and Android's
 * default provider) supply this. BouncyCastle is not on this path.
 */
actual fun platformAesEcbBlock(): AesEcbBlock = JvmAesEcbBlock

private object JvmAesEcbBlock : AesEcbBlock {
    override fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(block.size == AES_BLOCK_BYTES) { "AES block must be $AES_BLOCK_BYTES bytes, was ${block.size}" }
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES key must be 16, 24 or 32 bytes, was ${key.size}"
        }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = cipher.doFinal(block)
        check(out.size == AES_BLOCK_BYTES) { "AES/ECB/NoPadding returned ${out.size} bytes for one block" }
        return out
    }
}
