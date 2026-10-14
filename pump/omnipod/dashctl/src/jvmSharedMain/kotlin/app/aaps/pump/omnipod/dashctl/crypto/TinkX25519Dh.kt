package app.aaps.pump.omnipod.dashctl.crypto

import com.google.crypto.tink.subtle.X25519

/** [X25519Dh] on Tink, matching the Android `X25519KeyGenerator` exactly. */
class TinkX25519Dh : X25519Dh {

    override fun generatePrivateKey(): ByteArray = X25519.generatePrivateKey()

    override fun publicFromPrivate(privateKey: ByteArray): ByteArray =
        X25519.publicFromPrivate(privateKey)

    override fun computeSharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        X25519.computeSharedSecret(privateKey, publicKey)
}
