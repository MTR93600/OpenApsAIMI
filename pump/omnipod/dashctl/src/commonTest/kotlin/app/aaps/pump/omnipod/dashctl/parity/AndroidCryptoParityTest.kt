package app.aaps.pump.omnipod.dashctl.parity

import app.aaps.pump.omnipod.dashctl.crypto.DashEnDecrypt
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.crypto.DashMilenage
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.DecryptResponse
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.EapChallenge
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.EncryptCommand
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.KeyExchangeLtk
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.KeyValueP0
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.Milenage1
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.Milenage2
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.Milenage3
import app.aaps.pump.omnipod.dashctl.parity.AndroidRecordedVectors.MilenageResync
import app.aaps.pump.omnipod.dashctl.session.DashEapCodec
import app.aaps.pump.omnipod.dashctl.session.DashKeyValueCodec
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Crypto layer parity: the bytes the Android driver produced for real pods, fed to the
 * `dashctl` code. These are expected to pass. If one fails, the crypto differs from the
 * proven Android code and that is a first class finding.
 *
 * Every vector is in [AndroidRecordedVectors] with its source test named.
 */
class AndroidCryptoParityTest {

    // -- AES-CCM with the pod nonce (EnDecryptTest) ----------------------------------------------

    /** Android source: `EnDecryptTest.encrypt`. */
    @Test
    fun encryptMatchesAndroidRealCapture() {
        val enDecrypt = DashEnDecrypt(
            aesCcm = parityAesCcm(),
            nonce = DashNonce(parityHex(EncryptCommand.NONCE_PREFIX), EncryptCommand.NONCE_SQN),
            ck = parityHex(EncryptCommand.CK)
        )

        val encrypted = enDecrypt.encrypt(
            header = parityHex(EncryptCommand.HEADER_HEX),
            payload = parityHex(EncryptCommand.WRAPPED_COMMAND_HEX)
        )

        assertEquals(EncryptCommand.CIPHERTEXT_AND_MAC_HEX, encrypted.toParityHex())
    }

    /** Android source: `EnDecryptTest.decrypt`. The nonce counter starts at 22, so the nonce is for 23. */
    @Test
    fun decryptMatchesAndroidRealCapture() {
        val enDecrypt = DashEnDecrypt(
            aesCcm = parityAesCcm(),
            nonce = DashNonce(parityHex(DecryptResponse.NONCE_PREFIX), DecryptResponse.NONCE_SQN),
            ck = parityHex(DecryptResponse.CK)
        )

        val plaintext = enDecrypt.decrypt(
            header = parityHex(DecryptResponse.HEADER_HEX),
            payloadWithMac = parityHex(DecryptResponse.CIPHERTEXT_AND_MAC_HEX)
        )

        assertEquals(DecryptResponse.PLAINTEXT_HEX, plaintext.toParityHex())
    }

    // -- Milenage (MilenageTest) ----------------------------------------------------------------

    private fun milenage(k: String, sqn: String, rand: String) =
        DashMilenage(parityAesEcb(), parityHex(k), parityHex(sqn), randParam = parityHex(rand))

    /** Android source: `MilenageTest.testMilenage`. */
    @Test
    fun milenageSet1MatchesAndroid() {
        val m = milenage(Milenage1.K, Milenage1.SQN, Milenage1.RAND)
        assertEquals(Milenage1.RES, m.res.toParityHex(), "RES")
        assertEquals(Milenage1.CK, m.ck.toParityHex(), "CK")
        assertEquals(Milenage1.AUTN, m.autn.toParityHex(), "AUTN")
    }

    /** Android source: `MilenageTest.testMilenage2`. */
    @Test
    fun milenageSet2MatchesAndroid() {
        val m = milenage(Milenage2.K, Milenage2.SQN, Milenage2.RAND)
        assertEquals(Milenage2.RES, m.res.toParityHex(), "RES")
        assertEquals(Milenage2.CK, m.ck.toParityHex(), "CK")
        assertEquals(Milenage2.AUTN, m.autn.toParityHex(), "AUTN")
    }

    /** Android source: `MilenageTest.testMilenageIncrementedSQN`. */
    @Test
    fun milenageIncrementedSqnMatchesAndroid() {
        val m = milenage(Milenage3.K, Milenage3.SQN, Milenage3.RAND)
        assertEquals(Milenage3.RES, m.res.toParityHex(), "RES")
        assertEquals(Milenage3.CK, m.ck.toParityHex(), "CK")
        assertEquals(Milenage3.AUTN, m.autn.toParityHex(), "AUTN")
    }

    /** Android source: `MilenageTest.testMileageSynchronization`. */
    @Test
    fun milenageResynchronisationMatchesAndroid() {
        val m = DashMilenage(
            aesEcb = parityAesEcb(),
            k = parityHex(MilenageResync.K),
            sqn = parityHex(MilenageResync.SQN),
            randParam = parityHex(MilenageResync.RAND),
            auts = parityHex(MilenageResync.AUTS),
            amf = parityHex(MilenageResync.AMF)
        )
        assertEquals(m.macS.toParityHex(), m.receivedMacS.toParityHex(), "MAC-S")
        assertEquals(m.sqn.toParityHex(), m.synchronizationSqn.toParityHex(), "SQN from AUTS")
    }

    // -- Pairing key exchange (KeyExchangeTest) -------------------------------------------------

    /** Android source: `KeyExchangeTest.testLTK`. */
    @Test
    fun keyExchangeLtkMatchesAndroid() {
        val ke = DashKeyExchange(
            x25519 = parityX25519(),
            aesCmac = parityAesCmac(),
            pdmNonce = parityHex(KeyExchangeLtk.PDM_NONCE),
            pdmPrivate = parityHex(KeyExchangeLtk.PDM_PRIVATE)
        )

        ke.updatePodPublicData(parityHex(KeyExchangeLtk.POD_PUBLIC + KeyExchangeLtk.POD_NONCE))
        assertEquals(KeyExchangeLtk.PDM_PUBLIC, ke.pdmPublic.toParityHex(), "pdmPublic")
        assertEquals(KeyExchangeLtk.PDM_CONF, ke.pdmConf.toParityHex(), "pdmConf")

        ke.validatePodConf(parityHex(KeyExchangeLtk.POD_CONF))
        assertEquals(KeyExchangeLtk.LTK, ke.ltk.toParityHex(), "ltk")
    }

    // -- Key/value framing and EAP framing --------------------------------------------------------

    /** Android source: `StringLengthPrefixEncodingTest.testFormatKeysP0`. */
    @Test
    fun keyValueFormatP0MatchesAndroid() {
        val encoded = DashKeyValueCodec.formatKeys(
            arrayOf(KeyValueP0.KEY),
            arrayOf(parityHex(KeyValueP0.VALUE_HEX))
        )
        assertEquals(KeyValueP0.ENCODED_HEX, encoded.toParityHex())
    }

    /** Android source: `StringLengthPrefixEncodingTest.testParseKeysP0`. */
    @Test
    fun keyValueParseP0MatchesAndroid() {
        val parsed = DashKeyValueCodec.parseKeys(arrayOf(KeyValueP0.KEY), parityHex(KeyValueP0.ENCODED_HEX))
        assertEquals(1, parsed.size)
        assertEquals(KeyValueP0.VALUE_HEX, parsed[0].toParityHex())
    }

    /**
     * Android source: `EnDecryptTest.encrypt` (its wrapped command).
     * The codec CAN build the `S0.0=..,G0.0` wrapper. The divergence is that the pump path
     * does not call it, see `IosDashPumpAndroidParityTest.commandPayloadIsWrappedInS00`.
     */
    @Test
    fun keyValueCodecBuildsTheCommandWrapperLikeAndroid() {
        val wrapped = DashKeyValueCodec.formatKeys(
            arrayOf("S0.0=", ",G0.0"),
            arrayOf(parityHex(EncryptCommand.INNER_COMMAND_HEX), ByteArray(0))
        )
        assertEquals(EncryptCommand.WRAPPED_COMMAND_HEX, wrapped.toParityHex())
    }

    /** Android source: `EapMessageTest.testParseAndBack` (it only parses and re-serialises). */
    @Test
    fun eapChallengeParsesLikeAndroid() {
        val challenge = DashEapCodec.parseChallenge(parityHex(EapChallenge.PACKET_HEX))
        assertEquals(EapChallenge.IDENTIFIER.toByte(), challenge.identifier)
        assertEquals(EapChallenge.AUTN, challenge.autn.toParityHex())
        assertEquals(EapChallenge.RAND, challenge.rand.toParityHex())
        assertEquals(EapChallenge.IV, challenge.controllerIv.toParityHex())
    }

    /** Android source: `EapMessageTest.testParseAndBack`: parse, then back to the same bytes. */
    @Test
    fun eapChallengeSerialisesBackToTheAndroidBytes() {
        val back = DashEapCodec.challenge(
            identifier = EapChallenge.IDENTIFIER.toByte(),
            autn = parityHex(EapChallenge.AUTN),
            rand = parityHex(EapChallenge.RAND),
            controllerIv = parityHex(EapChallenge.IV)
        )
        assertEquals(EapChallenge.PACKET_HEX, back.toParityHex())
    }
}
