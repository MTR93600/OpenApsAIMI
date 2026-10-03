package app.aaps.core.objects.crypto

/**
 * iOS actual. Empty on purpose for the red run: the vectors have to fail before any library call.
 */
class IosLinkCrypto : LinkCrypto {

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
