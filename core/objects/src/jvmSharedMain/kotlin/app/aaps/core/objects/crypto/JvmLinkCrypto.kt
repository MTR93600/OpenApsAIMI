package app.aaps.core.objects.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.ECDSA
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.providers.jdk.JDK
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * JVM and Android. The calls are the library's; nothing below reimplements a cipher.
 *
 * OpenJDK 21's SunJCE has neither `AES/CCM/NoPadding` nor `AESCMAC`, and its XDH key factory cannot
 * derive a public key. [BouncyCastleProvider] is the provider `cryptography-kotlin` uses for those
 * gaps (see its `BouncyCastleBridge`). It is passed in here and not installed as the process-wide
 * JCA provider, so the rest of the app keeps SunJCE.
 *
 * A tag failure and a bad key come back as the library's exception. They are not caught: a caller
 * that wanted a boolean gets one only from [ecdsaP256VerifySha256], whose contract is verify-or-false.
 */
class JvmLinkCrypto : LinkCrypto {

    private val provider = CryptographyProvider.JDK(BouncyCastleProvider())

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

    private fun notYet(): Nothing = throw NotImplementedError("LinkCrypto AES")

    private fun x25519Private(privateKey: ByteArray) =
        provider.get(XDH)
            .privateKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, privateKey)
}
