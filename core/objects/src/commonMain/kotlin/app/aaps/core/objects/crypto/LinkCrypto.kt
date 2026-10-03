package app.aaps.core.objects.crypto

/**
 * The link algorithms a future pump or CGM driver will need, kept off the export format.
 *
 * [CryptoPrimitives] is the settings-export seam (PBKDF2-HMAC-SHA1, AES-GCM). This one is the
 * other seam: AES-CCM, AES-CMAC, X25519, ECDH P-256 and ECDSA P-256. Nothing here is wired into a
 * driver. `PUMPDRIVERS` stays false, and no pump class calls this.
 *
 * An implementation uses a vetted library. If a platform cannot provide an operation, the actual
 * says so and fails closed. It does not invent a cipher, and it does not copy Abbott tables.
 */
interface LinkCrypto {

    /**
     * AES-CCM. Returns ciphertext with the authentication tag appended, the same layout the JDK
     * uses for `AES/CCM/NoPadding`.
     *
     * @param tagBits authentication tag length in bits (32, 48, 64, 80, 96, 112 or 128).
     */
    fun aesCcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
        tagBits: Int,
    ): ByteArray

    /** The inverse of [aesCcmEncrypt]. Throws when the tag does not verify. */
    fun aesCcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        associatedData: ByteArray,
        tagBits: Int,
    ): ByteArray

    /** AES-CMAC, the full 16-byte tag. */
    fun aesCmac(key: ByteArray, message: ByteArray): ByteArray

    /** X25519 public key (u-coordinate, 32 bytes) for [privateKey]. */
    fun x25519Public(privateKey: ByteArray): ByteArray

    /** X25519 shared secret, 32 bytes. */
    fun x25519Shared(privateKey: ByteArray, peerPublic: ByteArray): ByteArray

    /**
     * ECDH on NIST P-256. [peerPublicUncompressed] is `04 || x || y`.
     * The shared secret is the 32-byte x-coordinate.
     */
    fun ecdhP256Shared(privateKey: ByteArray, peerPublicUncompressed: ByteArray): ByteArray

    /**
     * ECDSA P-256 with SHA-256. [signatureRaw] is `r || s`, 64 bytes (P1363), not DER.
     * A bad signature is `false`. A library failure is an exception, not a swallowed `false`.
     */
    fun ecdsaP256VerifySha256(
        publicUncompressed: ByteArray,
        message: ByteArray,
        signatureRaw: ByteArray,
    ): Boolean
}
