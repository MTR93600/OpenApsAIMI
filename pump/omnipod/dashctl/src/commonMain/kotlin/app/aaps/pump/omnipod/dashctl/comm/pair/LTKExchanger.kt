package app.aaps.pump.omnipod.dashctl.comm.pair

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.comm.Ids
import app.aaps.pump.omnipod.dashctl.comm.exceptions.MessageIOException
import app.aaps.pump.omnipod.dashctl.comm.exceptions.PairingException
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageSendSuccess
import app.aaps.pump.omnipod.dashctl.comm.message.StringLengthPrefixEncoding.Companion.parseKeys
import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex
import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange

/**
 * Pairing with a new pod: SP1/SP2, SPS1, SPS2, then SP0GP0 and P0. The result is the long term
 * key (LTK) and the outer message counter.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.pair.LTKExchanger`, same
 * messages in the same order, same bytes.
 *
 * Differences from Android:
 * - the key exchange is [DashKeyExchange] (same keys as the Android `KeyExchange`, checked with
 *   the Android vector), passed in as [keyExchange]. Android creates its `KeyExchange` itself with
 *   a new X25519 key and a random nonce; here the caller does that, so tests can fix them;
 * - [DashKeyExchange] throws `DashCryptoException` where the Android `KeyExchange` throws
 *   `MessageIOException` (wrong SPS1 size, wrong podConf). This class checks the size itself and
 *   turns a wrong podConf back into [MessageIOException], so the caller sees the Android
 *   exception. The Android warning for a wrong podConf is logged here;
 * - there is no `Config` parameter: Android passes it only to `KeyExchange`, for debug logs.
 */
internal class LTKExchanger(
    private val aapsLogger: AAPSLogger,
    private val msgIO: MessageIO,
    private val ids: Ids,
    private val keyExchange: DashKeyExchange,
) {

    private val podAddress = Ids.notActivated()
    private var seq: Byte = 1

    @Throws(PairingException::class)
    fun negotiateLTK(): PairResult {
        val sp1sp2 = PairMessage(
            sequenceNumber = seq,
            source = ids.myId,
            destination = podAddress,
            keys = arrayOf(SP1, SP2),
            payloads = arrayOf(ids.podId.address, sp2())
        )
        throwOnSendError(sp1sp2.messagePacket, SP1 + SP2)

        seq++
        val sps1 = PairMessage(
            sequenceNumber = seq,
            source = ids.myId,
            destination = podAddress,
            keys = arrayOf(SPS1),
            payloads = arrayOf(keyExchange.pdmPublic + keyExchange.pdmNonce)
        )
        throwOnSendError(sps1.messagePacket, SPS1)

        val podSps1 = msgIO.receiveMessage() ?: throw PairingException("Could not read SPS1")
        processSps1FromPod(podSps1)
        // now we have all the data to generate: confPod, confPdm, ltk and noncePrefix

        seq++
        val sps2 = PairMessage(
            sequenceNumber = seq,
            source = ids.myId,
            destination = podAddress,
            keys = arrayOf(SPS2),
            payloads = arrayOf(keyExchange.pdmConf)
        )
        throwOnSendError(sps2.messagePacket, SPS2)

        val podSps2 = msgIO.receiveMessage() ?: throw PairingException("Could not read SPS2")
        validatePodSps2(podSps2)
        // No exception throwing after this point. It is possible that the pod saved the LTK

        seq++
        // send SP0GP0
        val sp0gp0 = PairMessage(
            sequenceNumber = seq,
            source = ids.myId,
            destination = podAddress,
            keys = arrayOf(SP0GP0),
            payloads = arrayOf(ByteArray(0))
        )
        val result = msgIO.sendMessage(sp0gp0.messagePacket)
        if (result !is MessageSendSuccess) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Error sending SP0GP0: $result")
        }

        msgIO.receiveMessage()
            ?.let { validateP0(it) }
            ?: aapsLogger.warn(LTag.PUMPBTCOMM, "Could not read P0")

        return PairResult(
            ltk = keyExchange.ltk,
            msgSeq = seq
        )
    }

    @Throws(PairingException::class)
    private fun throwOnSendError(msg: MessagePacket, msgType: String) {
        val result = msgIO.sendMessage(msg)
        if (result !is MessageSendSuccess) {
            throw PairingException("Could not send or confirm $msgType: $result")
        }
    }

    private fun processSps1FromPod(msg: MessagePacket) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Received SPS1 from pod: ${msg.payload.toCommLogHex()}")

        val payload = parseKeys(arrayOf(SPS1), msg.payload)[0]
        // The size check of Android KeyExchange.updatePodPublicData, with its exception. Done here
        // because DashKeyExchange throws DashCryptoException for it.
        if (payload.size != DashKeyExchange.PUBLIC_KEY_SIZE + DashKeyExchange.NONCE_SIZE) {
            throw MessageIOException("Invalid payload size")
        }
        keyExchange.updatePodPublicData(payload)
    }

    private fun validatePodSps2(msg: MessagePacket) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Received SPS2 from pod: ${msg.payload.toCommLogHex()}")

        val payload = parseKeys(arrayOf(SPS2), msg.payload)[0]
        aapsLogger.debug(LTag.PUMPBTCOMM, "SPS2 payload from pod: ${payload.toCommLogHex()}")

        if (payload.size != DashKeyExchange.CMAC_SIZE) {
            throw MessageIOException("Invalid payload size")
        }
        try {
            keyExchange.validatePodConf(payload)
        } catch (e: DashCryptoException) {
            // Android KeyExchange.validatePodConf logs this warning, then throws MessageIOException.
            aapsLogger.warn(
                LTag.PUMPBTCOMM,
                "Received invalid podConf. Expected: ${keyExchange.podConf.toCommLogHex()}. Got: ${payload.toCommLogHex()}"
            )
            throw MessageIOException("Invalid podConf value received")
        }
    }

    private fun sp2(): ByteArray {
        // This is GetPodStatus command, with page 0 parameter.
        // We could replace that in the future with the serialized GetPodStatus()
        return GET_POD_STATUS_HEX_COMMAND.hexToBytes()
    }

    private fun validateP0(msg: MessagePacket) {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Received P0 from pod: ${msg.payload.toCommLogHex()}")

        val payload = parseKeys(arrayOf(P0), msg.payload)[0]
        aapsLogger.debug(LTag.PUMPBTCOMM, "P0 payload from pod: ${payload.toCommLogHex()}")
        if (!payload.contentEquals(UNKNOWN_P0_PAYLOAD)) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "Received invalid P0 payload: ${payload.toCommLogHex()}")
        }
    }

    companion object {

        private const val GET_POD_STATUS_HEX_COMMAND =
            "ffc32dbd08030e0100008a"
        // This is the binary representation of "GetPodStatus command"

        private const val SP1 = "SP1="
        private const val SP2 = ",SP2="
        private const val SPS1 = "SPS1="
        private const val SPS2 = "SPS2="
        private const val SP0GP0 = "SP0,GP0"
        private const val P0 = "P0="
        private val UNKNOWN_P0_PAYLOAD = byteArrayOf(0xa5.toByte())

        /** Same bytes as `hexStringToByteArray()` from `:core:utils` for this constant. */
        private fun String.hexToBytes(): ByteArray =
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}
