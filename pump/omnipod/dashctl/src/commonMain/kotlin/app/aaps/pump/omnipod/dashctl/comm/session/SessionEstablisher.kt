package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.comm.Ids
import app.aaps.pump.omnipod.dashctl.comm.exceptions.SessionEstablishmentException
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageSendSuccess
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex
import app.aaps.pump.omnipod.dashctl.crypto.AesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.DashMilenage
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce
import app.aaps.pump.omnipod.dashctl.crypto.SecureRandomBytes

/**
 * EAP-AKA session set up with the pod: sends the challenge (AUTN, RAND, our IV), checks the pod
 * answer (RES, pod IV) and sends EAP SUCCESS. The result is the session key CK and the nonce, or a
 * resynchronization when the pod has another EAP SQN.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.session.SessionEstablisher`,
 * same steps and same bytes. [msgSeq] is the outer message counter: it goes up by one before the
 * challenge and by one before SUCCESS, and is given back in the result.
 *
 * Differences from Android:
 * - Milenage is [DashMilenage] (same values as the Android `Milenage`, checked with the Android
 *   vectors), with AES-ECB behind the [AesEcbBlockCipher] seam ([aesEcb]);
 * - the random values come from the [SecureRandomBytes] seam ([randomBytes]), drawn in the
 *   Android order: the EAP identifier, then RAND (16 bytes, in Milenage), then our IV (4 bytes).
 *   Android takes the identifier as the low byte of `SecureRandom().nextInt()`; here it is one
 *   random byte. Both are a uniform random byte;
 * - there is no `Config` parameter. Android uses it only to log Milenage keys (K, CK) in debug
 *   builds; here they are never logged.
 */
class SessionEstablisher(
    private val aapsLogger: AAPSLogger,
    private val msgIO: MessageIO,
    private val ltk: ByteArray,
    private val eapSqn: ByteArray,
    private val ids: Ids,
    private var msgSeq: Byte,
    private val aesEcb: AesEcbBlockCipher,
    randomBytes: SecureRandomBytes
) {

    private val controllerIV = ByteArray(IV_SIZE)
    private var nodeIV = ByteArray(IV_SIZE)
    private val identifier = randomBytes.nextBytes(1)[0]
    private val milenage = DashMilenage(aesEcb, ltk, eapSqn, randomBytes = randomBytes)

    init {
        require(eapSqn.size == 6) { "EAP-SQN has to be 6 bytes long" }
        require(ltk.size == 16) { "LTK has to be 16 bytes long" }

        aapsLogger.debug(LTag.PUMPBTCOMM, "Starting EAP-AKA")
        randomBytes.nextBytes(IV_SIZE).copyInto(controllerIV)
    }

    fun negotiateSessionKeys(): SessionNegotiationResponse {
        msgSeq++
        val challenge = eapAkaChallenge()
        val sendResult = msgIO.sendMessage(challenge)
        if (sendResult !is MessageSendSuccess) {
            throw SessionEstablishmentException("Could not send the EAP AKA challenge: $sendResult")
        }
        val challengeResponse = msgIO.receiveMessage()
            ?: throw SessionEstablishmentException("Could not establish session")

        val newSqn = processChallengeResponse(challengeResponse)
        if (newSqn != null) {
            return SessionNegotiationResynchronization(
                synchronizedEapSqn = newSqn,
                msgSequenceNumber = msgSeq
            )
        }

        msgSeq++
        val success = eapSuccess()
        msgIO.sendMessage(success)

        return SessionKeys(
            ck = milenage.ck,
            nonce = DashNonce(
                prefix = controllerIV + nodeIV,
                sqn = 0
            ),
            msgSequenceNumber = msgSeq
        )
    }

    private fun eapAkaChallenge(): MessagePacket {
        val attributes = arrayOf<EapAkaAttribute>(
            EapAkaAttributeAutn(milenage.autn),
            EapAkaAttributeRand(milenage.rand),
            EapAkaAttributeCustomIV(controllerIV)
        )

        val eapMsg = EapMessage(
            code = EapCode.REQUEST,
            identifier = identifier,
            attributes = attributes
        )
        return MessagePacket(
            type = MessageType.SESSION_ESTABLISHMENT,
            sequenceNumber = msgSeq,
            source = ids.myId,
            destination = ids.podId,
            payload = eapMsg.toByteArray()
        )
    }

    private fun assertIdentifier(msg: EapMessage) {
        if (msg.identifier != identifier) {
            aapsLogger.debug(
                LTag.PUMPBTCOMM,
                "EAP-AKA: got incorrect identifier ${msg.identifier} expected: $identifier"
            )
            throw SessionEstablishmentException("Received incorrect EAP identifier: ${msg.identifier}")
        }
    }

    private fun processChallengeResponse(challengeResponse: MessagePacket): EapSqn? {
        val eapMsg = EapMessage.parse(aapsLogger, challengeResponse.payload)

        assertIdentifier(eapMsg)

        val eapSqn = isResynchronization(eapMsg)
        if (eapSqn != null) {
            return eapSqn
        }

        assertValidAkaMessage(eapMsg)

        for (attr in eapMsg.attributes) {
            when (attr) {
                is EapAkaAttributeRes      ->
                    if (!milenage.res.contentEquals(attr.payload)) {
                        throw SessionEstablishmentException(
                            "RES mismatch." +
                                "Expected: ${milenage.res.toCommLogHex()}." +
                                "Actual: ${attr.payload.toCommLogHex()}."
                        )
                    }

                is EapAkaAttributeCustomIV ->
                    nodeIV = attr.payload.copyOfRange(0, IV_SIZE)

                else                       ->
                    throw SessionEstablishmentException("Unknown attribute received: $attr")
            }
        }
        return null
    }

    private fun assertValidAkaMessage(eapMsg: EapMessage) {
        if (eapMsg.attributes.size != 2) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "EAP-AKA: got incorrect: $eapMsg")
            if (eapMsg.attributes.size == 1 && eapMsg.attributes[0] is EapAkaAttributeClientErrorCode) {
                throw SessionEstablishmentException(
                    "Received CLIENT_ERROR_CODE for EAP-AKA challenge: ${
                        eapMsg.attributes[0].toByteArray().toCommLogHex()
                    }"
                )
            }
            throw SessionEstablishmentException("Expecting two attributes, got: ${eapMsg.attributes.size}")
        }
    }

    private fun isResynchronization(eapMsg: EapMessage): EapSqn? {
        if (eapMsg.subType != EapMessage.SUBTYPE_SYNCHRONIZATION_FAILURE ||
            eapMsg.attributes.size != 1 ||
            eapMsg.attributes[0] !is EapAkaAttributeAuts
        )
            return null

        val auts = eapMsg.attributes[0] as EapAkaAttributeAuts
        val autsMilenage = DashMilenage(
            aesEcb = aesEcb,
            k = ltk,
            sqn = eapSqn,
            randParam = milenage.rand,
            auts = auts.payload
        )

        val newSqnMilenage = DashMilenage(
            aesEcb = aesEcb,
            k = ltk,
            sqn = autsMilenage.synchronizationSqn,
            randParam = milenage.rand,
            auts = auts.payload,
            amf = DashMilenage.RESYNC_AMF,
        )

        if (!newSqnMilenage.macS.contentEquals(newSqnMilenage.receivedMacS)) {
            throw SessionEstablishmentException(
                "MacS mismatch. " +
                    "Expected: ${newSqnMilenage.macS.toCommLogHex()}. " +
                    "Received: ${newSqnMilenage.receivedMacS.toCommLogHex()}"
            )
        }
        return EapSqn(autsMilenage.synchronizationSqn)
    }

    private fun eapSuccess(): MessagePacket {
        val eapMsg = EapMessage(
            code = EapCode.SUCCESS,
            attributes = arrayOf(),
            identifier = identifier
        )

        return MessagePacket(
            type = MessageType.SESSION_ESTABLISHMENT,
            sequenceNumber = msgSeq,
            source = ids.myId,
            destination = ids.podId,
            payload = eapMsg.toByteArray()
        )
    }

    companion object {

        private const val IV_SIZE = 4
    }
}
