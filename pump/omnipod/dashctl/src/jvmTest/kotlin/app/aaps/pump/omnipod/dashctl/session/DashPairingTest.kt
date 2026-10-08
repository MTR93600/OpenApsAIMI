package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.crypto.AesCmac
import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.crypto.SpongyCastleAesCmac
import app.aaps.pump.omnipod.dashctl.crypto.TinkX25519Dh
import app.aaps.pump.omnipod.dashctl.crypto.X25519Dh
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Full pairing flow with injected keys/nonces (deterministic).
 *
 * The fake transport replays scripted pod answers. The expected LTK and the
 * confirmation values are derived independently from the documented KDF
 * (X25519 + AES-CMAC), so the test cross-checks the orchestration instead of
 * trusting [DashKeyExchange] twice.
 */
class DashPairingTest {

    private val x25519: X25519Dh = TinkX25519Dh()
    private val aesCmac: AesCmac = SpongyCastleAesCmac()

    // RFC 7748 X25519 vectors, reused from the D2 key exchange test.
    private val pdmPrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val pdmNonce = hex("000102030405060708090a0b0c0d0e0f")
    private val podPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val podNonce = hex("101112131415161718191a1b1c1d1e1f")
    private val podAddress = hex("1f01482b")

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** Independent reimplementation of the KDF documented on [DashKeyExchange]. */
    private inner class ExpectedKeys(x25519: X25519Dh, aesCmac: AesCmac) {
        val podPublic: ByteArray = x25519.publicFromPrivate(podPrivate)
        val pdmPublic: ByteArray = x25519.publicFromPrivate(pdmPrivate)
        private val curveLtk = x25519.computeSharedSecret(pdmPrivate, podPublic)
        private val intermediate: ByteArray

        val ltk: ByteArray
        val pdmConf: ByteArray
        val podConf: ByteArray

        init {
            val firstKey = podPublic.takeLast(4).toByteArray() +
                pdmPublic.takeLast(4).toByteArray() +
                podNonce.takeLast(4).toByteArray() +
                pdmNonce.takeLast(4).toByteArray()
            intermediate = aesCmac.compute(firstKey, curveLtk)

            val ltkData = byteArrayOf(2) + "TWIt".encodeToByteArray() + podNonce + pdmNonce + byteArrayOf(0, 1)
            ltk = aesCmac.compute(intermediate, ltkData)

            val confData = byteArrayOf(1) + "TWIt".encodeToByteArray() + podNonce + pdmNonce + byteArrayOf(0, 1)
            val confKey = aesCmac.compute(intermediate, confData)
            pdmConf = aesCmac.compute(confKey, "KC_2_U".encodeToByteArray() + pdmNonce + podNonce)
            podConf = aesCmac.compute(confKey, "KC_2_V".encodeToByteArray() + podNonce + pdmNonce)
        }
    }

    private class ScriptedTransport : DashMessageTransport {
        val sent = mutableListOf<ByteArray>()
        private val receives = ArrayDeque<ByteArray>()
        var failSend = false

        fun queueReceive(data: ByteArray) = receives.addLast(data)

        override fun send(data: ByteArray): Boolean {
            if (failSend) return false
            sent.add(data)
            return true
        }

        override fun receive(): ByteArray? = receives.removeFirstOrNull()
    }

    private fun pairingWith(transport: ScriptedTransport): DashPairing {
        val keyExchange = DashKeyExchange(x25519, aesCmac, pdmNonce = pdmNonce, pdmPrivate = pdmPrivate)
        return DashPairing(transport, keyExchange, podAddress = podAddress)
    }

    @Test
    fun fullPairingDerivesExpectedLtk() {
        val expected = ExpectedKeys(x25519, aesCmac)
        val transport = ScriptedTransport()
        transport.queueReceive(
            DashKeyValueCodec.formatKeys(arrayOf("SPS1="), arrayOf(expected.podPublic + podNonce))
        )
        transport.queueReceive(
            DashKeyValueCodec.formatKeys(arrayOf("SPS2="), arrayOf(expected.podConf))
        )
        transport.queueReceive(
            DashKeyValueCodec.formatKeys(arrayOf("P0="), arrayOf(byteArrayOf(0xa5.toByte())))
        )

        val result = pairingWith(transport).negotiateLtk()

        assertContentEquals(expected.ltk, result.ltk, "LTK must match the independent KDF")
        assertEquals(4, result.msgSeq)

        // Wire bytes, verified octet by octet.
        assertEquals(4, transport.sent.size)
        assertContentEquals(
            DashKeyValueCodec.formatKeys(
                arrayOf("SP1=", ",SP2="),
                arrayOf(podAddress, DashPairing.GET_POD_STATUS_COMMAND)
            ),
            transport.sent[0],
            "SP1+SP2"
        )
        assertContentEquals(
            DashKeyValueCodec.formatKeys(arrayOf("SPS1="), arrayOf(expected.pdmPublic + pdmNonce)),
            transport.sent[1],
            "SPS1 carries the PDM public key and nonce"
        )
        assertContentEquals(
            DashKeyValueCodec.formatKeys(arrayOf("SPS2="), arrayOf(expected.pdmConf)),
            transport.sent[2],
            "SPS2 carries the PDM confirmation"
        )
        assertContentEquals("SP0,GP0".encodeToByteArray(), transport.sent[3], "SP0GP0")
    }

    @Test
    fun wrongPodConfAbortsPairing() {
        val expected = ExpectedKeys(x25519, aesCmac)
        val transport = ScriptedTransport()
        transport.queueReceive(
            DashKeyValueCodec.formatKeys(arrayOf("SPS1="), arrayOf(expected.podPublic + podNonce))
        )
        val badConf = expected.podConf.copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        transport.queueReceive(DashKeyValueCodec.formatKeys(arrayOf("SPS2="), arrayOf(badConf)))

        assertFailsWith<DashCryptoException> {
            pairingWith(transport).negotiateLtk()
        }
    }

    @Test
    fun missingPodSps1Throws() {
        val transport = ScriptedTransport()
        val error = assertFailsWith<DashSessionException> {
            pairingWith(transport).negotiateLtk()
        }
        assertTrue(error.message!!.contains("SPS1"))
    }

    @Test
    fun sendFailureThrows() {
        val transport = ScriptedTransport().also { it.failSend = true }
        assertFailsWith<DashSessionException> {
            pairingWith(transport).negotiateLtk()
        }
    }

    @Test
    fun pairingWithoutP0AckStillSucceeds() {
        // The original treats a missing P0 as a warning, not a failure.
        val expected = ExpectedKeys(x25519, aesCmac)
        val transport = ScriptedTransport()
        transport.queueReceive(
            DashKeyValueCodec.formatKeys(arrayOf("SPS1="), arrayOf(expected.podPublic + podNonce))
        )
        transport.queueReceive(
            DashKeyValueCodec.formatKeys(arrayOf("SPS2="), arrayOf(expected.podConf))
        )
        // No P0 queued.

        val result = pairingWith(transport).negotiateLtk()

        assertContentEquals(expected.ltk, result.ltk)
    }
}
