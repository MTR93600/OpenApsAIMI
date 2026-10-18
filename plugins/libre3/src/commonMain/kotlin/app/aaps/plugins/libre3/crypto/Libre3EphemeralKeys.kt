package app.aaps.plugins.libre3.crypto

import app.aaps.plugins.libre3.crypto.firstpair.Libre3FirstPairTables
import app.aaps.plugins.libre3.crypto.firstpair.builder633fa8NullScalarWindowFromEntropySource
import app.aaps.plugins.libre3.crypto.firstpair.builderProcess2P5PublicKey65FromEntropy
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

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
    /** The point the first pairing scheme sends, when it is not the point of the private key. */
    private val publicKeyOverride: ByteArray? = null,
) {

    /** The public point, 65 bytes, `0x04` then X then Y. */
    val publicKey65: ByteArray
        get() {
            publicKeyOverride?.let { return it.copyOf() }
            val point = (keyPair.public as ECPublicKey).w
            return byteArrayOf(0x04) + fixedLength(point.affineX.toByteArray()) + fixedLength(point.affineY.toByteArray())
        }

    /** The public point padded to the 72 bytes that the sensor expects on the wire. */
    val publicKeyPadded72: ByteArray get() = publicKey65 + ByteArray(PADDED_PUBLIC_KEY_SIZE - PUBLIC_KEY_SIZE)

    /**
     * The shared secret with the sensor's point.
     *
     * Only for a key pair whose point really belongs to its private key. A first pairing key pair
     * is refused here: its point on the wire is the scheme's own and does not match its scalar, so
     * an ordinary key agreement would hand back a number that looks fine and means nothing. The
     * first pairing does its own point multiply in `Libre3FirstPairP256`, over the whole seventy
     * byte window, and never comes through here.
     *
     * @param sensorPublicKey65 the sensor's point, 65 bytes, starting with `0x04`.
     * @throws Libre3CryptoException on a wrong point, or on a first pairing key pair.
     */
    fun sharedSecret(sensorPublicKey65: ByteArray): ByteArray {
        if (isFirstPairMaterial) {
            throw Libre3CryptoException(
                "a first pairing key pair has no ordinary shared secret, its point is not the point of its scalar"
            )
        }
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
        val x = BigInteger(1, point65.copyOfRange(1, 1 + COORDINATE_SIZE))
        val y = BigInteger(1, point65.copyOfRange(1 + COORDINATE_SIZE, PUBLIC_KEY_SIZE))
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
         * Nothing in the driver calls this today, and that is the point worth writing down: the
         * short reconnect of section 3.3 sends `0x11` and no key at all, so no session ever needs
         * a random key pair. It is kept because it is the only way to write the test that proves
         * hard ban 6, that a random key pair never reaches a first pairing, and because
         * [Libre3EphemeralKeyPair.isFirstPairMaterial] and [sharedSecret] are what enforce it.
         */
        fun randomForReconnect(): Libre3EphemeralKeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return Libre3EphemeralKeyPair(generator.generateKeyPair(), isFirstPairMaterial = false)
        }

        /**
         * The key pair of a first pairing.
         *
         * @param scalarWindowLE the phone's own scalar, low byte first, at least 32 bytes. The
         *   scheme keeps it in a seventy byte window and only the first 32 bytes are the scalar.
         * @param publicKey65Override the point that goes out on the wire. It is **not** the point
         *   of this scalar: the sensor maker's scheme sends a point of its own, and a sensor
         *   refuses anything else.
         */
        fun fromScalarWindow(scalarWindowLE: ByteArray, publicKey65Override: ByteArray): Libre3EphemeralKeyPair {
            if (publicKey65Override.size != PUBLIC_KEY_SIZE || publicKey65Override[0] != 0x04.toByte()) {
                throw Libre3CryptoException("the first pairing point must be 65 bytes and start with 0x04")
            }
            if (scalarWindowLE.size < COORDINATE_SIZE) {
                throw Libre3CryptoException("the first pairing scalar window must be at least 32 bytes")
            }
            val scalarBE = scalarWindowLE.copyOfRange(0, COORDINATE_SIZE).reversedArray()
            val scalar = BigInteger(1, scalarBE)
            if (scalar.signum() == 0) {
                throw Libre3CryptoException("the first pairing scalar is zero")
            }
            val parameters = curveParameters()
            val privateKey = KeyFactory.getInstance("EC")
                .generatePrivate(ECPrivateKeySpec(scalar, parameters))
            return Libre3EphemeralKeyPair(
                KeyPair(publicKeyFor(publicKey65Override, parameters), privateKey),
                isFirstPairMaterial = true,
                publicKeyOverride = publicKey65Override,
            )
        }

        /** The P-256 numbers, asked of the phone's own provider without making a key. */
        private fun curveParameters(): ECParameterSpec {
            val parameters = AlgorithmParameters.getInstance("EC")
            parameters.init(ECGenParameterSpec("secp256r1"))
            return parameters.getParameterSpec(ECParameterSpec::class.java)
        }

        private fun publicKeyFor(point65: ByteArray, parameters: ECParameterSpec): ECPublicKey {
            val x = BigInteger(1, point65.copyOfRange(1, 1 + COORDINATE_SIZE))
            val y = BigInteger(1, point65.copyOfRange(1 + COORDINATE_SIZE, PUBLIC_KEY_SIZE))
            return KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(ECPoint(x, y), parameters)) as ECPublicKey
        }
    }
}

/**
 * The key pair that a first pairing needs.
 *
 * A fresh sensor only accepts a key pair built by the sensor maker's own scheme, and it also
 * checks the point that is sent on the wire. Both come from one draw of entropy, and the very same
 * draw is needed again later to build the Phase 5 source, which is why [Libre3FirstPairMaterial]
 * carries it.
 *
 * Ported from LibreCRKit `Pairing/SessionKey.makeFirstPairNativeEphemeral` at pin `a86b92f`.
 */
object Libre3FirstPairEphemeral {

    /** How many draws of entropy to try before giving up. The upstream uses the same number. */
    const val MAX_ATTEMPTS = 64

    /**
     * Draws entropy until the scheme accepts it, then builds the key pair of this pairing.
     *
     * @param random where the entropy comes from. A test may pass a fixed source.
     * @throws Libre3CryptoException when this build cannot run the scheme, or when no draw is
     *   accepted.
     */
    fun make(random: SecureRandom = SecureRandom()): Libre3FirstPairMaterial {
        if (!isAvailable) {
            throw Libre3CryptoException(unavailableReason())
        }
        val result = builder633fa8NullScalarWindowFromEntropySource(MAX_ATTEMPTS) { count ->
            ByteArray(count).also { random.nextBytes(it) }
        }
        val publicKey65 = builderProcess2P5PublicKey65FromEntropy(result.entropy11A)
        val keyPair = Libre3EphemeralKeyPair.fromScalarWindow(result.scalarWindow, publicKey65)
        return Libre3FirstPairMaterial(
            keyPair = keyPair,
            entropy11A = result.entropy11A,
            scalarWindow = result.scalarWindow,
            attempts = result.attempts,
        )
    }

    /**
     * True when this build can really pair a sensor it has never seen.
     *
     * That needs two things, and both are properties of the build: every table of the scheme, and
     * the phone certificate that the first step of the clock sends. Asking for both here means the
     * start policy can refuse a first pairing before any link is opened.
     */
    val isAvailable: Boolean
        get() = Libre3FirstPairTables.present() && Libre3RuntimeTables.isPresent(Libre3RuntimeTables.PHONE_CERT)

    /** Why it cannot run, in words a log or a screen can carry. */
    fun unavailableReason(): String {
        val missing = Libre3FirstPairTables.missing()
        return when {
            missing.isNotEmpty()                                              ->
                "this build does not carry ${missing.size} of the tables the first pairing needs"

            !Libre3RuntimeTables.isPresent(Libre3RuntimeTables.PHONE_CERT)    ->
                "this build does not carry the phone certificate that a first pairing sends"

            else                                                              -> "the first pairing scheme can run"
        }
    }
}

/**
 * What one accepted draw of entropy produced.
 *
 * @param entropy11A the accepted draw. The Phase 5 source needs the very same bytes, so it must be
 *   kept for the whole pairing and thrown away after it.
 * @param scalarWindow the phone's own scalar, in the seventy byte window the scheme uses.
 * @param attempts how many draws it took. Useful in a log, nothing more.
 */
class Libre3FirstPairMaterial(
    val keyPair: Libre3EphemeralKeyPair,
    val entropy11A: ByteArray,
    val scalarWindow: ByteArray,
    val attempts: Int,
)
