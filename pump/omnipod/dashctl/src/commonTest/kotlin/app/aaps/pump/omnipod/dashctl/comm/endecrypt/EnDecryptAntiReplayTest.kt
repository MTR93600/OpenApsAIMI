package app.aaps.pump.omnipod.dashctl.comm.endecrypt

import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce
import app.aaps.pump.omnipod.dashctl.parity.parityAesCcm
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Anti-replay: the CCM nonce of each message comes from the shared [DashNonce] counter. Once an
 * answer is read the counter has moved on, so the same bytes sent again give another nonce and
 * the AES-CCM check fails. Not in Android; these checks came from the old `DashAntiReplayTest`
 * and now run on [EnDecrypt] and [MessagePacket].
 */
class EnDecryptAntiReplayTest {

    private val ck = parityHex("b40ba9a3c58b2a05bbf0d987b21bf8cb")
    private val prefix = parityHex("a0a1a2a3b0b1b2b3")
    private val plain = "0.0=response one".encodeToByteArray()

    /** A pod answer, as the pod sends it (see `FakeDashPod`). */
    private fun podAnswer(podReceiving: Boolean = false, key: ByteArray = ck): MessagePacket {
        val packet = MessagePacket(
            type = MessageType.ENCRYPTED,
            source = Id(parityHex("02420001")),
            destination = Id(parityHex("00001092")),
            payload = plain,
            sequenceNumber = 0x0c,
            ack = true,
            ackNumber = 0x08,
            eqos = 1,
            lastMessage = true
        )
        val header = packet.asByteArray(true).copyOfRange(0, 16)
        // The first message on the nonce: counter 1. The pod sends with podReceiving = false.
        val nonce = DashNonce(prefix, 0).increment(podReceiving)
        return packet.copy(payload = parityAesCcm().encrypt(key, nonce, header, plain, 8))
    }

    private fun phone() = EnDecrypt(SilentPodLogger, DashNonce(prefix, 0), ck, parityAesCcm())

    @Test
    fun `the answer is read once`() {
        assertContentEquals(plain, phone().decrypt(podAnswer()).payload)
    }

    @Test
    fun `the same answer sent again fails the check`() {
        val phone = phone()
        val answer = podAnswer()
        phone.decrypt(answer)

        assertFailsWith<DashCryptoException> { phone.decrypt(answer) }
    }

    @Test
    fun `a changed byte fails the check`() {
        val answer = podAnswer()
        val tampered = answer.payload.copyOf().also { it[3] = (it[3].toInt() xor 0xff).toByte() }

        assertFailsWith<DashCryptoException> { phone().decrypt(answer.copy(payload = tampered)) }
    }

    @Test
    fun `a message in the phone direction is not taken as an answer`() {
        assertFailsWith<DashCryptoException> { phone().decrypt(podAnswer(podReceiving = true)) }
    }

    @Test
    fun `an answer under another key fails the check`() {
        assertFailsWith<DashCryptoException> { phone().decrypt(podAnswer(key = ByteArray(16) { 0x77 })) }
    }
}
