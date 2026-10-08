package app.aaps.pump.omnipod.dashctl.ios.crypto

import app.aaps.core.objects.crypto.platformAesEcbBlock
import app.aaps.core.objects.crypto.platformLinkCrypto
import app.aaps.pump.omnipod.dashctl.crypto.AesCcmCipher
import app.aaps.pump.omnipod.dashctl.crypto.AesCmac
import app.aaps.pump.omnipod.dashctl.crypto.AesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.SecureRandomBytes
import app.aaps.pump.omnipod.dashctl.crypto.X25519Dh
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * iOS implementations of the D2 crypto seams.
 *
 * AES-CCM, AES-CMAC and the AES block cipher go through `core.objects`
 * ([platformLinkCrypto] / [platformAesEcbBlock]), which is CommonCrypto
 * (`CCCrypt` AES-ECB) under the hood on iOS — the same constructions as the
 * Android SpongyCastle implementations, verified by the D2 vectors.
 * X25519 goes through `cryptography-kotlin` (CryptoKit) via the same seam.
 */

/** AES-CCM via [platformLinkCrypto] (CommonCrypto AES on iOS). */
class IosAesCcmCipher : AesCcmCipher {

    private val crypto = platformLinkCrypto()

    override fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
        macSizeBytes: Int
    ): ByteArray = crypto.aesCcmEncrypt(
        key = key,
        nonce = nonce,
        plaintext = plaintext,
        associatedData = associatedData,
        tagBits = macSizeBytes * 8
    )

    override fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        ciphertextWithMac: ByteArray,
        macSizeBytes: Int
    ): ByteArray = try {
        crypto.aesCcmDecrypt(
            key = key,
            nonce = nonce,
            ciphertextAndTag = ciphertextWithMac,
            associatedData = associatedData,
            tagBits = macSizeBytes * 8
        )
    } catch (e: Exception) {
        throw DashCryptoException("AES-CCM authentication failed", e)
    }
}

/** AES-CMAC via [platformLinkCrypto] (CommonCrypto AES on iOS). */
class IosAesCmac : AesCmac {

    private val crypto = platformLinkCrypto()

    override fun compute(key: ByteArray, data: ByteArray): ByteArray =
        crypto.aesCmac(key = key, message = data)
}

/** Single-block AES-ECB via [platformAesEcbBlock] (CommonCrypto on iOS). */
class IosAesEcbBlockCipher : AesEcbBlockCipher {

    private val block = platformAesEcbBlock()

    override fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray =
        this.block.encryptBlock(key = key, block = block)
}

/** X25519 via [platformLinkCrypto] (cryptography-kotlin / CryptoKit on iOS). */
class IosX25519Dh : X25519Dh {

    private val crypto = platformLinkCrypto()

    override fun generatePrivateKey(): ByteArray =
        IosSecureRandomBytes().nextBytes(32)

    override fun publicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        return crypto.x25519Public(privateKey)
    }

    override fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "X25519 private key must be 32 bytes" }
        require(publicKey.size == 32) { "X25519 public key must be 32 bytes" }
        return crypto.x25519Shared(privateKey, publicKey)
    }
}

/** Secure random via `SecRandomCopyBytes` (iOS Security framework). */
@OptIn(ExperimentalForeignApi::class)
class IosSecureRandomBytes : SecureRandomBytes {

    override fun nextBytes(length: Int): ByteArray {
        require(length >= 0) { "length must be non-negative" }
        val out = ByteArray(length)
        if (length == 0) return out
        val status = memScoped {
            out.usePinned { pin ->
                SecRandomCopyBytes(kSecRandomDefault, length.toULong(), pin.addressOf(0))
            }
        }
        if (status != errSecSuccess) {
            throw DashCryptoException("SecRandomCopyBytes failed with status $status")
        }
        return out
    }
}
