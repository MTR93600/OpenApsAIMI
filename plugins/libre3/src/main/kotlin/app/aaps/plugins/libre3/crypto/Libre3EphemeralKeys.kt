package app.aaps.plugins.libre3.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec

/**
 * Short lived key pair of one session, on the P-256 curve.
 *
 * There are two ways to get one, and they must never be swapped:
 *
 * - A **reconnect** to a sensor that is already paired may use an ordinary random key pair from
 *   the phone, see [randomForReconnect].
 * - A **first pairing** must use the key pair that the sensor's own scheme produces, see
 *   [Libre3FirstPairEphemeral]. A random key pair does not pair a fresh sensor, so falling back to
 *   one would look like a working driver while never pairing anything.
 */
class Libre3EphemeralKeyPair internal constructor(
    private val keyPair: KeyPair,
    /** True only for a key pair that came from the first pairing scheme. */
    val isFirstPairMaterial: Boolean,
) {

    /** The public point, 65 bytes, `0x04` then X then Y. */
    val publicKey65: ByteArray
        get() {
            val point = (keyPair.public as ECPublicKey).w
            return byteArrayOf(0x04) + fixedLength(point.affineX.toByteArray()) + fixedLength(point.affineY.toByteArray())
        }

    /** The public point padded to the 72 bytes that the sensor expects on the wire. */
    val publicKeyPadded72: ByteArray get() = publicKey65 + ByteArray(PADDED_PUBLIC_KEY_SIZE - PUBLIC_KEY_SIZE)

    /**
     * The shared secret with the sensor's point.
     *
     * @param sensorPublicKey65 the sensor's point, 65 bytes, starting with `0x04`.
     */
    fun sharedSecret(sensorPublicKey65: ByteArray): ByteArray {
        if (sensorPublicKey65.size != PUBLIC_KEY_SIZE || sensorPublicKey65[0] != 0x04.toByte()) {
            throw Libre3CryptoException("the sensor point must be 65 bytes and start with 0x04")
        }
        val parameters = (keyPair.public as ECPublicKey).params
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair.private)
        agreement.doPhase(publicKeyFrom(sensorPublicKey65, parameters), true)
        return agreement.generateSecret()
    }

    private fun fixedLength(value: ByteArray): ByteArray {
        // A big number can carry a leading zero byte for its sign, or be shorter than the curve.
        val trimmed = if (value.size > COORDINATE_SIZE) value.copyOfRange(value.size - COORDINATE_SIZE, value.size) else value
        return ByteArray(COORDINATE_SIZE - trimmed.size) + trimmed
    }

    private fun publicKeyFrom(point65: ByteArray, parameters: ECParameterSpec): ECPublicKey {
        val x = java.math.BigInteger(1, point65.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = java.math.BigInteger(1, point65.copyOfRange(1 + COORDINATE_SIZE, PUBLIC_KEY_SIZE))
        val spec = ECPublicKeySpec(ECPoint(x, y), parameters)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    companion object {

        const val PUBLIC_KEY_SIZE = 65
        const val PADDED_PUBLIC_KEY_SIZE = 72
        private const val COORDINATE_SIZE = 32

        /**
         * An ordinary random key pair from the phone's own security provider.
         *
         * Allowed for a reconnect only. The name says so, and
         * [Libre3EphemeralKeyPair.isFirstPairMaterial] stays false, so a first pairing can refuse it.
         */
        fun randomForReconnect(): Libre3EphemeralKeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return Libre3EphemeralKeyPair(generator.generateKeyPair(), isFirstPairMaterial = false)
        }
    }
}

/**
 * The key pair that a first pairing needs.
 *
 * A fresh sensor only accepts a key pair built by the sensor maker's own scheme, together with the
 * entropy that produced it, because the same entropy is used again later in the pairing. The
 * upstream project builds this in a large table driven port that is not ported here yet.
 *
 * Until it is ported, [make] refuses instead of returning a random key pair. This is on purpose:
 * a random key pair would let a first pairing start and then fail in a way that is hard to read,
 * and it would hide the fact that this part is not finished.
 */
object Libre3FirstPairEphemeral {

    /**
     * @throws Libre3CryptoException always, while the first pairing scheme is not ported.
     */
    fun make(): Libre3EphemeralKeyPair =
        throw Libre3CryptoException(
            "the first pairing scheme is not ported yet, and a random key pair does not pair a fresh sensor"
        )

    /** True once [make] can really build the first pairing key pair. */
    val isAvailable: Boolean get() = false

    /** Why it cannot, in words a log or a screen can carry. */
    fun unavailableReason(): String = "the first pairing scheme of the sensor maker is not ported yet"
}
