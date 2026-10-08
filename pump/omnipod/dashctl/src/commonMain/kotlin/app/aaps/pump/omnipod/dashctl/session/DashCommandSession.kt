package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.DashEnDecrypt

/** Result of sending a command. */
sealed class DashSendResult {
    /** The encrypted command was sent. */
    object Success : DashSendResult()

    /** Sending failed (encryption error or transport failure). */
    data class Error(val msg: String) : DashSendResult()
}

/** Result of receiving a response. */
sealed class DashReceiveResult {
    /** The response was authenticated and decrypted. */
    data class Success(val payload: ByteArray) : DashReceiveResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = payload.contentHashCode()
    }

    /** Receiving failed (authentication failure or transport failure). */
    data class Error(val msg: String) : DashReceiveResult()
}

/**
 * Encrypted command session with the pod.
 *
 * Ported from the Android `Session` (send/receive parts), with the crypto behind
 * [DashEnDecrypt] (D2):
 * - [sendCommand] advances the message sequence number, encrypts the payload
 *   with the 16-byte header as associated data, and sends it.
 * - [receiveResponse] decrypts with the 16-byte header as associated data and
 *   advances the message sequence number, mirroring the original's increment
 *   on ACK.
 *
 * Anti-replay: the nonce for each message is derived from the shared
 * [app.aaps.pump.omnipod.dashctl.crypto.DashNonce] counter. Once a message is
 * consumed the counter has advanced, so replaying the same bytes derives a
 * different nonce and the AES-CCM authentication fails ([DashReceiveResult.Error]).
 *
 * On the wire a message is the clear 16-byte header (it is the CCM associated
 * data) followed by ciphertext + 8-byte MAC, mirroring the real `MessagePacket`
 * layout. Sending the ACK itself is BLE message layer work (D4/platform); the
 * counter still advances here.
 */
class DashCommandSession(
    private val transport: DashMessageTransport,
    private val stateManager: DashPodStateManager,
    private val enDecrypt: DashEnDecrypt
) {

    /**
     * Encrypts [payload] with [header] as associated data and sends
     * `header || ciphertext || mac`.
     */
    fun sendCommand(header: ByteArray, payload: ByteArray): DashSendResult {
        require(header.size == DashEnDecrypt.HEADER_SIZE) {
            "Header has to be ${DashEnDecrypt.HEADER_SIZE} bytes long"
        }
        stateManager.increaseMessageSequenceNumber()
        val encrypted = try {
            enDecrypt.encrypt(header, payload)
        } catch (e: Exception) {
            return DashSendResult.Error("Encryption failed: ${e.message}")
        }
        return if (transport.send(header + encrypted)) {
            DashSendResult.Success
        } else {
            DashSendResult.Error("Transport send failed")
        }
    }

    /**
     * Decrypts [payloadWithMac] with [header] as associated data.
     * Authentication failures (wrong key, tampered bytes, or a replayed nonce)
     * return [DashReceiveResult.Error].
     */
    fun receiveResponse(header: ByteArray, payloadWithMac: ByteArray): DashReceiveResult {
        require(header.size == DashEnDecrypt.HEADER_SIZE) {
            "Header has to be ${DashEnDecrypt.HEADER_SIZE} bytes long"
        }
        val decrypted = try {
            enDecrypt.decrypt(header, payloadWithMac)
        } catch (e: DashCryptoException) {
            return DashReceiveResult.Error("Authentication failed: ${e.message}")
        } catch (e: Exception) {
            return DashReceiveResult.Error("Decryption failed: ${e.message}")
        }
        // The original increments the message sequence number when ACKing the
        // response; the ACK transmission itself is D4/platform work.
        stateManager.increaseMessageSequenceNumber()
        return DashReceiveResult.Success(decrypted)
    }
}
