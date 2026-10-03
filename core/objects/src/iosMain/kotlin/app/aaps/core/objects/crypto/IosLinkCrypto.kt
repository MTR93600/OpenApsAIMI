package app.aaps.core.objects.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH

/**
 * iOS. X25519, ECDH P-256 and ECDSA P-256 go through `cryptography-kotlin` 0.6.0, whose CryptoKit
 * provider reaches Apple's implementation via the library's published Swift interop. This file does
 * not add a Swift bridge of its own.
 *
 * AES-CCM and AES-CMAC stop here. CommonCrypto (the Apple provider) has neither, and CryptoKit has
 * neither. The functions fail closed. They do not fall back to a hand-rolled cipher.
 */
class IosLinkCrypto : LinkCrypto {

    private val provider = CryptographyProvider.Default

    override fun aesCcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
        tagBits: Int,
    ): ByteArray = unavailable("AES-CCM")

    override fun aesCcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        associatedData: ByteArray,
        tagBits: Int,
    ): ByteArray = unavailable("AES-CCM")

    override fun aesCmac(key: ByteArray, message: ByteArray): ByteArray = unavailable("AES-CMAC")

    override fun x25519Public(privateKey: ByteArray): ByteArray =
        x25519Private(privateKey).getPublicKeyBlocking().encodeToByteArrayBlocking(XDH.PublicKey.Format.RAW)

    override fun x25519Shared(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val peer = provider.get(XDH)
            .publicKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArrayBlocking(XDH.PublicKey.Format.RAW, peerPublic)
        return x25519Private(privateKey).sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(peer)
    }

    override fun ecdhP256Shared(privateKey: ByteArray, peerPublicUncompressed: ByteArray): ByteArray {
        val ecdh = provider.get(ECDH)
        val priv = ecdh.privateKeyDecoder(EC.Curve.P256)
            .decodeFromByteArrayBlocking(EC.PrivateKey.Format.RAW, privateKey)
        val peer = ecdh.publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW.Uncompressed, peerPublicUncompressed)
        return priv.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(peer)
    }

    override fun ecdsaP256VerifySha256(
        publicUncompressed: ByteArray,
        message: ByteArray,
        signatureRaw: ByteArray,
    ): Boolean {
        val publicKey = provider.get(ECDSA)
            .publicKeyDecoder(EC.Curve.P256)
            .decodeFromByteArrayBlocking(EC.PublicKey.Format.RAW.Uncompressed, publicUncompressed)
        return publicKey.signatureVerifier(SHA256, ECDSA.SignatureFormat.RAW)
            .tryVerifySignatureBlocking(message, signatureRaw)
    }

    private fun x25519Private(privateKey: ByteArray) =
        provider.get(XDH)
            .privateKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, privateKey)

    private fun unavailable(algorithm: String): Nothing =
        error(
            "$algorithm is not available on iOS with cryptography-kotlin 0.6.0. " +
                "The Apple CommonCrypto provider has neither AES-CCM nor AES-CMAC, and the CryptoKit " +
                "provider has neither. No hand-rolled cipher is substituted. See " +
                "_docs/kmp/crypto-kmp-ios-ecarts.md.",
        )
}
