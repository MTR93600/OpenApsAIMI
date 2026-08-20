package app.aaps.plugins.libre3.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Key pair rules, and the guard that keeps a random key pair out of a first pairing. */
class Libre3EphemeralKeysTest {

    @Test
    fun `a reconnect key pair has a 65 byte point and pads to 72`() {
        val pair = Libre3EphemeralKeyPair.randomForReconnect()

        assertThat(pair.publicKey65).hasLength(65)
        assertThat(pair.publicKey65[0]).isEqualTo(0x04.toByte())
        assertThat(pair.publicKeyPadded72).hasLength(72)
        assertThat(pair.publicKeyPadded72.copyOfRange(65, 72)).isEqualTo(ByteArray(7))
    }

    @Test
    fun `two sides reach the same shared secret`() {
        val phone = Libre3EphemeralKeyPair.randomForReconnect()
        val sensor = Libre3EphemeralKeyPair.randomForReconnect()

        val fromPhone = phone.sharedSecret(sensor.publicKey65)
        val fromSensor = sensor.sharedSecret(phone.publicKey65)

        assertThat(fromPhone).isEqualTo(fromSensor)
    }

    @Test
    fun `a point that is not in the uncompressed form is refused`() {
        val phone = Libre3EphemeralKeyPair.randomForReconnect()

        assertThrows<Libre3CryptoException> { phone.sharedSecret(ByteArray(65)) }
        assertThrows<Libre3CryptoException> { phone.sharedSecret(ByteArray(64) { 0x04 }) }
    }

    @Test
    fun `a random key pair is never marked as first pairing material`() {
        assertThat(Libre3EphemeralKeyPair.randomForReconnect().isFirstPairMaterial).isFalse()
    }

    @Test
    fun `a first pairing refuses to fall back to a random key pair`() {
        // This is the point of the guard. A random key pair does not pair a fresh sensor, so the
        // driver must say so instead of starting a pairing that can only fail.
        assertThat(Libre3FirstPairEphemeral.isAvailable).isFalse()
        assertThrows<Libre3CryptoException> { Libre3FirstPairEphemeral.make() }
    }
}
