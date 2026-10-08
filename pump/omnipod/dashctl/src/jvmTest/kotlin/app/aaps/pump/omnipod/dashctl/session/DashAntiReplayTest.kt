package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.crypto.AesCcmCipher
import app.aaps.pump.omnipod.dashctl.crypto.DashEnDecrypt
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce
import app.aaps.pump.omnipod.dashctl.crypto.SpongyCastleAesCcm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Anti-replay: the CCM nonce for each message is derived from the shared
 * [DashNonce] counter. Once a message is consumed the counter has advanced, so
 * replaying the same bytes derives a different nonce and the AES-CCM
 * authentication fails.
 */
class DashAntiReplayTest {

    private val aesCcm: AesCcmCipher = SpongyCastleAesCcm()

    private val ck = hex("b40ba9a3c58b2a05bbf0d987b21bf8cb")
    private val prefix = hex("a0a1a2a3b0b1b2b3")
    private val header = hex("54573030313233343536373839616263") // 16 bytes

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private class InMemoryStore : DashPodStateStore {
        var saved: DashPodState? = null
        override fun load(): DashPodState? = saved
        override fun save(state: DashPodState) {
            saved = state
        }

        override fun clear() {
            saved = null
        }
    }

    private class ScriptedTransport : DashMessageTransport {
        override fun send(data: ByteArray): Boolean = true
        override fun receive(): ByteArray? = null
    }

    private fun newSession(): DashCommandSession {
        val manager = DashPodStateManager(InMemoryStore())
        val enDecrypt = DashEnDecrypt(aesCcm, DashNonce(prefix, sqn = 0), ck)
        return DashCommandSession(ScriptedTransport(), manager, enDecrypt)
    }

    /** Encrypts like the pod would for its first answer (sqn 1, pod -> PDM). */
    private fun podFirstResponse(plain: ByteArray): ByteArray {
        val nonce = DashNonce(prefix, 0).increment(podReceiving = false)
        return aesCcm.encrypt(ck, nonce, header, plain, DashEnDecrypt.MAC_SIZE_BYTES)
    }

    @Test
    fun replayedResponseFailsAuthentication() {
        val session = newSession()
        val plain = "response one".encodeToByteArray()
        val captured = podFirstResponse(plain)

        val first = session.receiveResponse(header, captured)
        assertIs<DashReceiveResult.Success>(first)
        assertContentEquals(plain, first.payload)

        // Replay the exact same bytes: the counter has advanced, so the derived
        // nonce no longer matches and CCM authentication fails.
        val replay = session.receiveResponse(header, captured)
        assertIs<DashReceiveResult.Error>(replay)
        assertTrue(replay.msg.contains("Authentication"), "Failure must be an authentication failure")
    }

    @Test
    fun sameBytesAuthenticateWithOriginalNonce() {
        // Control: the captured bytes are valid; only the advanced counter
        // makes the replay fail.
        val plain = "response one".encodeToByteArray()
        val captured = podFirstResponse(plain)

        val fresh = DashEnDecrypt(aesCcm, DashNonce(prefix, sqn = 0), ck)
        assertContentEquals(plain, fresh.decrypt(header, captured))
    }

    @Test
    fun tamperedCiphertextFailsAuthentication() {
        val session = newSession()
        val captured = podFirstResponse("response one".encodeToByteArray())
        val tampered = captured.copyOf()
        tampered[3] = (tampered[3].toInt() xor 0xff).toByte()

        val result = session.receiveResponse(header, tampered)
        assertIs<DashReceiveResult.Error>(result)
        assertTrue(result.msg.contains("Authentication"))
    }

    @Test
    fun commandCiphertextReplayedAsResponseFails() {
        // A PDM -> pod command ciphertext replayed on the receive path uses the
        // wrong direction bit and the wrong sequence number.
        val session = newSession()
        val plain = "command".encodeToByteArray()
        val commandNonce = DashNonce(prefix, 0).increment(podReceiving = true)
        val commandCipher = aesCcm.encrypt(ck, commandNonce, header, plain, DashEnDecrypt.MAC_SIZE_BYTES)

        val result = session.receiveResponse(header, commandCipher)
        assertIs<DashReceiveResult.Error>(result)
        assertTrue(result.msg.contains("Authentication"))
    }

    @Test
    fun wrongKeyFailsAuthentication() {
        val manager = DashPodStateManager(InMemoryStore())
        val wrongCk = ByteArray(16) { 0x77 }
        val session = DashCommandSession(
            ScriptedTransport(), manager, DashEnDecrypt(aesCcm, DashNonce(prefix, sqn = 0), wrongCk)
        )
        val captured = podFirstResponse("response one".encodeToByteArray())

        val result = session.receiveResponse(header, captured)
        assertIs<DashReceiveResult.Error>(result)
    }
}
