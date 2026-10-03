package app.aaps.core.objects.crypto

/**
 * One AES block under ECB, which is the block permutation.
 *
 * CMAC and CCM are built on this in common code. The platform actual encrypts exactly 16 bytes and
 * does not apply a mode of its own. A longer input is rejected so this cannot be used as a
 * multi-block ECB mode.
 */
interface AesEcbBlock {
    fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray
}

expect fun platformAesEcbBlock(): AesEcbBlock
