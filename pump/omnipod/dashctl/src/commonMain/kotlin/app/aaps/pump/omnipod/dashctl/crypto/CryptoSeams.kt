package app.aaps.pump.omnipod.dashctl.crypto

/**
 * Platform seams for the Dash pod cryptography. All pure protocol logic in commonMain
 * talks to these interfaces; platform source sets provide the implementations.
 *
 * The pod speaks AES-CCM (not GCM) with an 8-byte MAC, AES-CMAC for key derivation,
 * X25519 for the pairing key exchange, single-block AES-ECB for Milenage, and
 * SecureRandom for nonces. None of these are available in pure Kotlin.
 */

/**
 * AES-CCM authenticated encryption, as used by the pod message layer.
 * The MAC size is fixed by the protocol ([DashEnDecrypt.MAC_SIZE_BYTES]).
 */
interface AesCcmCipher {

    /**
     * Encrypts [plaintext] with [key], [nonce] and [associatedData] (AAD).
     * Returns ciphertext + MAC appended.
     */
    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
        macSizeBytes: Int
    ): ByteArray

    /**
     * Decrypts [ciphertextWithMac] with [key], [nonce] and [associatedData].
     * Throws [DashCryptoException] if authentication fails.
     */
    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        ciphertextWithMac: ByteArray,
        macSizeBytes: Int
    ): ByteArray
}

/** AES-CMAC message authentication, as used by the pairing key derivation. */
interface AesCmac {

    /** Computes the 16-byte CMAC of [data] under [key]. */
    fun compute(key: ByteArray, data: ByteArray): ByteArray
}

/** Single-block AES-ECB encryption, as used by Milenage. */
interface AesEcbBlockCipher {

    /** Encrypts one 16-byte [block] under the 16-byte [key]. Returns 16 bytes. */
    fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray
}

/** X25519 Diffie-Hellman, as used by the pairing key exchange. */
interface X25519Dh {

    /** Generates a 32-byte private key. */
    fun generatePrivateKey(): ByteArray

    /** Derives the 32-byte public key from a 32-byte [privateKey]. */
    fun publicFromPrivate(privateKey: ByteArray): ByteArray

    /** Computes the 32-byte shared secret from our [privateKey] and their [publicKey]. */
    fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray
}

/** Cryptographically secure random bytes, as used for nonces. */
interface SecureRandomBytes {

    /** Returns [length] random bytes. */
    fun nextBytes(length: Int): ByteArray
}

/** Thrown when a crypto operation fails (authentication failure, invalid input). */
class DashCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
