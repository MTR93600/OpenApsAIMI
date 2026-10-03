package app.aaps.core.objects.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH

/**
 * JVM and Android. AES-CCM and AES-CMAC are the common constructions over JCE `AES/ECB/NoPadding`.
 * OpenJDK 21's SunJCE has neither `AES/CCM/NoPadding` nor `AESCMAC`, so BouncyCastle is not the
 * production path for them.
 *
 * X25519, ECDH and ECDSA use [CryptographyProvider.Default] (SunEC / SunJCE). Deriving an X25519
 * public key still needs BouncyCastle classes inside `cryptography-kotlin` (`BouncyCastleBridge`).
 * This module does not depend on BouncyCastle: without those classes on the classpath the call
 * fails closed. The unit tests put the jar on the test classpath so the RFC vector still runs.
 *
 * A tag failure is [AesCcmAuthenticationException]. It is not caught.
 */
class JvmLinkCrypto : LinkCrypto {

    private val provider = CryptographyProvider.Default

    override fun aesCcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
        tagBits: Int,
    ): ByteArray = computeAesCcmEncrypt(key, nonce, plaintext, associatedData, tagBits)

    override fun aesCcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        associatedData: ByteArray,
        tagBits: Int,
    ): ByteArray = computeAesCcmDecrypt(key, nonce, ciphertextAndTag, associatedData, tagBits)

    override fun aesCmac(key: ByteArray, message: ByteArray): ByteArray = computeAesCmac(key, message)

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
}
