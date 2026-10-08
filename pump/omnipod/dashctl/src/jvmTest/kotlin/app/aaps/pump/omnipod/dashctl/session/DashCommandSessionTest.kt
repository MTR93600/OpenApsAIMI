package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.crypto.AesCcmCipher
import app.aaps.pump.omnipod.dashctl.crypto.AesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.DashEnDecrypt
import app.aaps.pump.omnipod.dashctl.crypto.DashMilenage
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce
import app.aaps.pump.omnipod.dashctl.crypto.JavaxAesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.SpongyCastleAesCcm
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * EAP-AKA session establishment followed by an encrypted bolus command,
 * all with fixed keys/nonces/IVs so the wire bytes are fully deterministic.
 *
 * The fake pod answers the challenge with a RES computed from its own
 * [DashMilenage]; the test cross-checks the challenge AUTN against the same
 * computation.
 */
class DashCommandSessionTest {

    private val aesCcm: AesCcmCipher = SpongyCastleAesCcm()
    private val aesEcb: AesEcbBlockCipher = JavaxAesEcbBlockCipher()

    private val ltk = hex("00112233445566778899aabbccddeeff")
    private val rand = hex("23553cbe9637a89d218ae64dae47bf35")
    private val controllerIv = hex("a0a1a2a3")
    private val nodeIv = hex("b0b1b2b3")
    private val identifier: Byte = 0x42
    private val noncePrefix = controllerIv + nodeIv

    private val header = hex("54573030313233343536373839616263") // 16 bytes
    private val responseHeader = hex("54573130313233343536373839616263") // 16 bytes

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
        val sent = mutableListOf<ByteArray>()
        private val receives = ArrayDeque<ByteArray>()

        fun queueReceive(data: ByteArray) = receives.addLast(data)

        override fun send(data: ByteArray): Boolean {
            sent.add(data)
            return true
        }

        override fun receive(): ByteArray? = receives.removeFirstOrNull()
    }

    /** Transport decorator that plays the pod side of EAP-AKA. */
    private inner class PodTransport(
        private val eapSqn: ByteArray,
        private val badRes: Boolean = false
    ) : DashMessageTransport {
        val sent = mutableListOf<ByteArray>()

        override fun send(data: ByteArray): Boolean {
            sent.add(data)
            return true
        }

        override fun receive(): ByteArray? {
            val challenge = sent.last()
            val parsed = DashEapCodec.parseChallenge(challenge)
            val podMilenage = DashMilenage(aesEcb, ltk, eapSqn, randParam = parsed.rand)
            // The pod verifies the challenge AUTN against its own SQN.
            assertContentEquals(podMilenage.autn, parsed.autn, "Challenge AUTN must match the pod's SQN")
            val res = if (badRes) {
                podMilenage.res.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
            } else {
                podMilenage.res
            }
            return DashEapCodec.response(parsed.identifier, res, nodeIv)
        }
    }

    private fun pairedManager(): DashPodStateManager {
        val manager = DashPodStateManager(InMemoryStore())
        manager.updateFromPairing(uniqueId = 0x1F01482BL, ltk = ltk)
        return manager
    }

    private fun bolusPayload(): ByteArray =
        ProgramBolusCommand.Builder()
            .setNumberOfUnits(5.0)
            .setProgramReminder(ProgramReminder(false, true, 0.toByte()))
            .setDelayBetweenPulsesInEighthSeconds(16.toByte())
            .setUniqueId(37879809)
            .setSequenceNumber(14.toShort())
            .setNonce(1229869870)
            .build()
            .encoded

    @Test
    fun eapAkaEstablishmentDerivesExpectedSessionKeys() {
        val manager = pairedManager()
        // The manager will increase 1 -> 2 for this session.
        val eapSqn = DashPodStateManager.eapSqnBytes(2)
        val podTransport = PodTransport(eapSqn)
        val establisher = DashSessionEstablisher(
            transport = podTransport,
            stateManager = manager,
            aesEcb = aesEcb,
            controllerIv = controllerIv,
            rand = rand,
            identifier = identifier
        )

        val keys = establisher.negotiateSessionKeys()

        val podMilenage = DashMilenage(aesEcb, ltk, eapSqn, randParam = rand)
        assertContentEquals(podMilenage.ck, keys.ck, "CK must match the pod's Milenage")
        assertContentEquals(noncePrefix, keys.nonce.prefix, "Nonce prefix is controllerIV + nodeIV")
        assertEquals(2, manager.messageSequenceNumber,
            "Two sequence steps: one before the challenge, one before the EAP success")
        assertEquals(2L, manager.eapAkaSequenceNumber)
        // The challenge carried AUTN, RAND and the controller IV.
        val challenge = DashEapCodec.parseChallenge(podTransport.sent[0])
        assertEquals(identifier, challenge.identifier)
        assertContentEquals(rand, challenge.rand)
        assertContentEquals(controllerIv, challenge.controllerIv)
        assertContentEquals(podMilenage.autn, challenge.autn)
        // Then the EAP success went out.
        assertEquals(2, podTransport.sent.size)
        assertContentEquals(DashEapCodec.success(identifier), podTransport.sent[1])
    }

    @Test
    fun eapAkaRejectsWrongRes() {
        val manager = pairedManager()
        val establisher = DashSessionEstablisher(
            transport = PodTransport(DashPodStateManager.eapSqnBytes(2), badRes = true),
            stateManager = manager,
            aesEcb = aesEcb,
            controllerIv = controllerIv,
            rand = rand,
            identifier = identifier
        )

        assertFailsWith<DashSessionException> {
            establisher.negotiateSessionKeys()
        }
    }

    @Test
    fun bolusCommandWireBytesAreDeterministic() {
        val manager = pairedManager()
        val keys = DashSessionKeys(
            ck = hex("b40ba9a3c58b2a05bbf0d987b21bf8cb"),
            nonce = DashNonce(noncePrefix, sqn = 0)
        )
        val transport = ScriptedTransport()
        val session = DashCommandSession(transport, manager, DashEnDecrypt(aesCcm, keys.nonce, keys.ck))
        val bolus = bolusPayload()

        val result = session.sendCommand(header, bolus)

        assertIs<DashSendResult.Success>(result)
        // Independent expectation: header in clear + CCM ciphertext + 8-byte MAC.
        val expectedNonce = DashNonce(noncePrefix, 0).increment(podReceiving = true)
        val expected = header + aesCcm.encrypt(keys.ck, expectedNonce, header, bolus, 8)
        assertContentEquals(expected, transport.sent.single(), "Wire bytes must be deterministic")
        assertEquals(1, manager.messageSequenceNumber)
    }

    @Test
    fun repeatedRunProducesIdenticalWireBytes() {
        fun runOnce(): ByteArray {
            val manager = pairedManager()
            val keys = DashSessionKeys(
                ck = hex("b40ba9a3c58b2a05bbf0d987b21bf8cb"),
                nonce = DashNonce(noncePrefix, sqn = 0)
            )
            val transport = ScriptedTransport()
            val session = DashCommandSession(transport, manager, DashEnDecrypt(aesCcm, keys.nonce, keys.ck))
            val result = session.sendCommand(header, bolusPayload())
            assertIs<DashSendResult.Success>(result)
            return transport.sent.single()
        }

        assertContentEquals(runOnce(), runOnce(), "Same vectors must give the same bytes")
    }

    @Test
    fun responseDecryptsWithPodNonce() {
        val manager = pairedManager()
        val ck = hex("b40ba9a3c58b2a05bbf0d987b21bf8cb")
        val transport = ScriptedTransport()
        val session = DashCommandSession(transport, manager, DashEnDecrypt(aesCcm, DashNonce(noncePrefix, 0), ck))

        val plain = hex("0102030405060708090a")
        // The pod's answer uses the next shared nonce (sqn 1), pod -> PDM direction.
        val podNonce = DashNonce(noncePrefix, 0).increment(podReceiving = false)
        val encrypted = aesCcm.encrypt(ck, podNonce, responseHeader, plain, 8)

        val result = session.receiveResponse(responseHeader, encrypted)

        assertIs<DashReceiveResult.Success>(result)
        assertContentEquals(plain, result.payload)
        assertEquals(1, manager.messageSequenceNumber)
    }

    @Test
    fun sendCommandRejectsBadHeaderSize() {
        val manager = pairedManager()
        val session = DashCommandSession(
            ScriptedTransport(), manager,
            DashEnDecrypt(aesCcm, DashNonce(noncePrefix, 0), hex("b40ba9a3c58b2a05bbf0d987b21bf8cb"))
        )
        assertFailsWith<IllegalArgumentException> {
            session.sendCommand(ByteArray(8), byteArrayOf(1, 2, 3))
        }
    }
}
