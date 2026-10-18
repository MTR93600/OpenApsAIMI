package app.aaps.plugins.libre3.crypto

/**
 * The iOS half of the curve work is not written yet.
 *
 * Each of these refuses out loud rather than answering with something made up, which is the rule
 * stated on the shared declarations: a wrong point or a wrong secret would not fail, it would pair
 * wrongly. No iOS build can reach them today anyway - the fixed tables of the pairing scheme are
 * packaged as JVM classpath resources, so `Libre3RuntimeTables.pairingTablesPresent` is already
 * false on Apple and the driver stands down before a session starts.
 *
 * What an iOS version needs: P-256 key generation, key agreement and signature checking, which
 * CryptoKit offers as `P256.KeyAgreement` and `P256.Signing`, plus a big whole number type for
 * [libre3P256ScalarMultiply], which CryptoKit does **not** offer. That last one is the real piece
 * of work.
 */

private const val NOT_YET = "the Libre 3 curve work is not written for this platform yet"

internal actual fun libre3P256GenerateKeyPair(): Libre3P256KeyPairBytes =
    throw Libre3CryptoException("$NOT_YET: cannot make a key pair")

internal actual fun libre3P256ValidateKeyMaterial(privateScalarBE: ByteArray, publicPoint65: ByteArray): Unit =
    throw Libre3CryptoException("$NOT_YET: cannot check key material")

internal actual fun libre3P256SharedSecret(privateScalarBE: ByteArray, peerPoint65: ByteArray): ByteArray =
    throw Libre3CryptoException("$NOT_YET: cannot agree a shared secret")

internal actual fun libre3VerifyEcdsaSha256(
    signerPoint65: ByteArray,
    payload: ByteArray,
    derSignature: ByteArray,
): Boolean =
    throw Libre3CryptoException("$NOT_YET: cannot check a signature")

internal actual fun libre3P256ScalarMultiply(
    scalarWindowLE: ByteArray,
    sensorPointXYBE: ByteArray,
): Libre3P256Window70 =
    throw Libre3CryptoException("$NOT_YET: cannot multiply a point")
