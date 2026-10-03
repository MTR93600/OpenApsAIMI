package app.aaps.core.objects.crypto

/**
 * JVM and Android actual. The body is intentionally empty until the vectors fail: a green test
 * before an implementation would only prove the test never ran.
 */
class JvmLinkCrypto : LinkCrypto {

    override fun aesCcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
        tagBits: Int,
    ): ByteArray = notYet()

    override fun aesCcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        associatedData: ByteArray,
        tagBits: Int,
    ): ByteArray = notYet()

    override fun aesCmac(key: ByteArray, message: ByteArray): ByteArray = notYet()

    override fun x25519Public(privateKey: ByteArray): ByteArray = notYet()

    override fun x25519Shared(privateKey: ByteArray, peerPublic: ByteArray): ByteArray = notYet()

    override fun ecdhP256Shared(privateKey: ByteArray, peerPublicUncompressed: ByteArray): ByteArray = notYet()

    override fun ecdsaP256VerifySha256(
        publicUncompressed: ByteArray,
        message: ByteArray,
        signatureRaw: ByteArray,
    ): Boolean = notYet()

    private fun notYet(): Nothing = throw NotImplementedError("LinkCrypto")
}
