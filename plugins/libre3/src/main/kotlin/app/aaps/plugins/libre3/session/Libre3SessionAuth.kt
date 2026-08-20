package app.aaps.plugins.libre3.session

import app.aaps.plugins.libre3.crypto.Libre3AesBlock
import app.aaps.plugins.libre3.crypto.Libre3CryptoException
import app.aaps.plugins.libre3.crypto.Libre3Phase5Challenge
import app.aaps.plugins.libre3.crypto.Libre3Phase6Response
import app.aaps.plugins.libre3.crypto.Libre3SessionMaterial

/** The three channels the handshake talks on. */
enum class Libre3HandshakeChannel { CERT, CHALLENGE, COMMAND }

/**
 * Everything the handshake needs from the Bluetooth link, and nothing more.
 *
 * Keeping it this small is what lets the whole command clock be unit tested with a written down
 * script of answers, without a radio and without an Android device.
 */
interface Libre3HandshakeTransport {

    /** Writes one command byte on the command channel. */
    fun writeCommand(command: Byte)

    /** Writes a message on a channel, split into parts by the layer below if needed. */
    fun write(channel: Libre3HandshakeChannel, payload: ByteArray)

    /**
     * Waits for the next message on a channel.
     *
     * @param expectedSize the exact size that is expected, or null when any size is fine.
     */
    fun awaitNotify(channel: Libre3HandshakeChannel, expectedSize: Int? = null): ByteArray
}

/** Raised when the sensor answers something the handshake did not ask for. */
class Libre3HandshakeException(message: String) : Exception(message)

/**
 * The pairing command clock.
 *
 * Ported from LibreCRKit `Pairing/PairingFlow.swift` at pin `a86b92f`, with the reconnect rule of
 * LibreLoop `LibreLoopPairingService.reconnect`.
 *
 * There are two paths and they must never be mixed:
 *
 * - [runCachedReconnect] sends `0x11` and nothing else. It is used whenever a pairing key is
 *   stored. If it fails, the driver asks for a new NFC scan. It must never fall back to the other
 *   path: a running sensor refuses the first pairing command late in the handshake, so the fall
 *   back cannot work and only hides the real cause.
 * - [runFirstPair] is the full clock and starts with `0x01`. It is only for a sensor that this
 *   phone has never paired.
 */
class Libre3SessionAuth(private val transport: Libre3HandshakeTransport) {

    /**
     * Short reconnect. Sends `0x11`, reads the sensor's random part, answers with Phase 5 built
     * from the stored key, then reads Phase 6.
     *
     * @param blePin the four PIN bytes from the NFC step. They are the tail of the Phase 5 message.
     * @param phase5Block the block maker built from the stored pairing key.
     * @param phoneR2 16 fresh random bytes from this phone.
     */
    fun runCachedReconnect(
        blePin: ByteArray,
        phase5Block: Libre3AesBlock,
        phoneR2: ByteArray,
    ): Libre3SessionMaterial {
        require(blePin.size == 4) { "the PIN must be 4 bytes" }
        require(phoneR2.size == 16) { "R2 must be 16 bytes" }

        transport.writeCommand(START_AUTHORIZATION)
        expectCommandAnswer(CHALLENGE_LOAD_DONE, "the sensor did not accept the start of the reconnect")
        val (sensorR1, nonce) = readSensorChallenge()

        return sendPhase5AndReadPhase6(sensorR1, phoneR2, blePin, nonce, phase5Block)
    }

    /**
     * Full first pairing. Only for a sensor with no stored pairing key.
     *
     * @param phoneCert the 162 byte certificate of this phone.
     * @param phoneEphemeralPublicKey72 the public point of this session, padded to 72 bytes.
     * @param sensorCertSize how many bytes the sensor's certificate has.
     */
    fun runFirstPair(
        phoneCert: ByteArray,
        phoneEphemeralPublicKey72: ByteArray,
        sensorCertSize: Int = SENSOR_CERT_SIZE,
    ): Libre3FirstPairPreamble {
        require(phoneCert.size == PHONE_CERT_SIZE) { "the phone certificate must be 162 bytes" }
        require(phoneEphemeralPublicKey72.size == PADDED_PUBLIC_KEY_SIZE) {
            "the public point must be padded to 72 bytes"
        }

        transport.writeCommand(START_AUTHENTICATION)
        transport.writeCommand(LOAD_CERTIFICATE)
        transport.write(Libre3HandshakeChannel.CERT, phoneCert)
        transport.writeCommand(SEND_CERTIFICATE_LOAD_DONE)
        expectCommandAnswer(CERTIFICATE_ACCEPTED, "the sensor did not accept the certificate of this phone")

        transport.writeCommand(GET_CERTIFICATE)
        expectCommandAnswer(CERTIFICATE_READY, "the sensor did not offer its own certificate")
        val sensorCert = transport.awaitNotify(Libre3HandshakeChannel.CERT, sensorCertSize)

        transport.writeCommand(VALIDATE_CERTIFICATE)
        transport.write(Libre3HandshakeChannel.CERT, phoneEphemeralPublicKey72)
        transport.writeCommand(SEND_EPHEMERAL_DONE)
        expectCommandAnswer(EPHEMERAL_READY, "the sensor did not accept the key of this session")
        val sensorEphemeral = transport.awaitNotify(Libre3HandshakeChannel.CERT, PUBLIC_KEY_SIZE)

        transport.writeCommand(START_AUTHORIZATION)
        expectCommandAnswer(CHALLENGE_LOAD_DONE, "the sensor did not start the last pairing step")
        val (sensorR1, nonce) = readSensorChallenge()

        return Libre3FirstPairPreamble(sensorCert, sensorEphemeral, sensorR1, nonce)
    }

    /**
     * Last two steps, shared by both paths: send Phase 5, then read and check Phase 6.
     *
     * @param phase5Block the block maker of the pairing plane. It is never the ordinary AES that
     *   the glucose data uses.
     */
    fun sendPhase5AndReadPhase6(
        sensorR1: ByteArray,
        phoneR2: ByteArray,
        blePin: ByteArray,
        nonce: ByteArray,
        phase5Block: Libre3AesBlock,
    ): Libre3SessionMaterial {
        val plaintext = Libre3Phase5Challenge.plaintext(sensorR1, phoneR2, blePin)
        val phase5 = Libre3Phase5Challenge.encrypt(plaintext, nonce, phase5Block)
        transport.write(Libre3HandshakeChannel.CHALLENGE, phase5.wireBytes)

        transport.writeCommand(SEND_CHALLENGE_LOAD_DONE)
        expectCommandAnswer(CHALLENGE_LOAD_DONE, "the sensor did not accept the answer of this phone")

        val phase6Raw = transport.awaitNotify(Libre3HandshakeChannel.CHALLENGE, Libre3Phase6Response.WIRE_SIZE)
        val phase6 = Libre3Phase6Response.decode(phase6Raw)
        return try {
            phase6.decrypt(phase5Block, expectedPhoneR2 = phoneR2, expectedSensorR1 = sensorR1)
        } catch (e: Libre3CryptoException) {
            throw Libre3HandshakeException("the last pairing step failed, ${e.message}")
        }
    }

    /** Reads the 23 byte message: 16 bytes of sensor random part, then the 7 byte nonce. */
    private fun readSensorChallenge(): Pair<ByteArray, ByteArray> {
        val wire = transport.awaitNotify(Libre3HandshakeChannel.CHALLENGE, CHALLENGE_SIZE)
        if (wire.size != CHALLENGE_SIZE) {
            throw Libre3HandshakeException("the sensor challenge must be 23 bytes, not ${wire.size}")
        }
        return wire.copyOfRange(0, 16) to wire.copyOfRange(16, CHALLENGE_SIZE)
    }

    private fun expectCommandAnswer(expectedFirstByte: Byte, whatWentWrong: String) {
        val answer = transport.awaitNotify(Libre3HandshakeChannel.COMMAND)
        if (answer.isEmpty() || answer[0] != expectedFirstByte) {
            val seen = if (answer.isEmpty()) "nothing" else "0x%02X".format(answer[0])
            throw Libre3HandshakeException("$whatWentWrong, expected 0x%02X but got %s".format(expectedFirstByte, seen))
        }
    }

    companion object {

        // Commands this phone sends. The two uses of 0x08 are different things: the phone sends
        // "my answer is loaded", and the sensor answers "the challenge is loaded".
        const val START_AUTHENTICATION: Byte = 0x01
        const val LOAD_CERTIFICATE: Byte = 0x02
        const val SEND_CERTIFICATE_LOAD_DONE: Byte = 0x03
        const val GET_CERTIFICATE: Byte = 0x09
        const val VALIDATE_CERTIFICATE: Byte = 0x0D
        const val SEND_EPHEMERAL_DONE: Byte = 0x0E
        const val START_AUTHORIZATION: Byte = 0x11
        const val SEND_CHALLENGE_LOAD_DONE: Byte = 0x08

        // First bytes the sensor answers with.
        const val CERTIFICATE_ACCEPTED: Byte = 0x04
        const val CERTIFICATE_READY: Byte = 0x0A
        const val EPHEMERAL_READY: Byte = 0x0F
        const val CHALLENGE_LOAD_DONE: Byte = 0x08

        const val PHONE_CERT_SIZE = 162
        const val SENSOR_CERT_SIZE = 140
        const val PUBLIC_KEY_SIZE = 65
        const val PADDED_PUBLIC_KEY_SIZE = 72
        const val CHALLENGE_SIZE = 23
    }
}

/** What the first part of a first pairing produced. */
data class Libre3FirstPairPreamble(
    val sensorCert: ByteArray,
    val sensorEphemeralPublicKey: ByteArray,
    val sensorR1: ByteArray,
    val nonce: ByteArray,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Libre3FirstPairPreamble) return false
        return sensorCert.contentEquals(other.sensorCert) &&
            sensorEphemeralPublicKey.contentEquals(other.sensorEphemeralPublicKey) &&
            sensorR1.contentEquals(other.sensorR1) && nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int {
        var result = sensorCert.contentHashCode()
        result = 31 * result + sensorEphemeralPublicKey.contentHashCode()
        result = 31 * result + sensorR1.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        return result
    }
}
