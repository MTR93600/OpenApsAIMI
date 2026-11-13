package app.aaps.pump.omnipod.dashctl.comm.endecrypt

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex
import app.aaps.pump.omnipod.dashctl.crypto.AesCcmCipher
import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce

/**
 * AES-CCM encryption of a [MessagePacket] payload, with the first 16 bytes of the message (the
 * header) as associated data and an 8 byte MAC.
 *
 * Port of the Android `app.aaps.pump.omnipod.common.bledriver.comm.endecrypt.EnDecrypt`. Same
 * steps in the same order: header, then `nonce.increment`, then the cipher.
 *
 * Differences from Android:
 * - the cipher is the [AesCcmCipher] seam (SpongyCastle `CCMBlockCipher(AESEngine())` on the JVM,
 *   CommonCrypto on iOS), passed in as [aesCcm]. Android creates the SpongyCastle cipher itself;
 * - the nonce is [DashNonce]. It gives exactly the bytes of the Android `Nonce` (same 8 byte
 *   prefix, same 5 byte counter, same direction bit), so the Android class is not copied;
 * - a wrong MAC on [decrypt] throws [DashCryptoException]. Android throws the SpongyCastle
 *   `InvalidCipherTextException`. In both cases the nonce counter has already moved on.
 */
class EnDecrypt(
    private val aapsLogger: AAPSLogger,
    private val nonce: DashNonce,
    private val ck: ByteArray,
    private val aesCcm: AesCcmCipher
) {

    fun decrypt(msg: MessagePacket): MessagePacket {
        val payload = msg.payload
        val header = msg.asByteArray().copyOfRange(0, 16)

        val n = nonce.increment(false)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Decrypt header ${header.toCommLogHex()} payload: ${payload.toCommLogHex()}")
        aapsLogger.debug(LTag.PUMPBTCOMM, "Decrypt NONCE ${n.toCommLogHex()}")
        val decryptedPayload = aesCcm.decrypt(
            key = ck,
            nonce = n,
            associatedData = header,
            ciphertextWithMac = payload,
            macSizeBytes = MAC_SIZE
        )
        aapsLogger.debug(LTag.PUMPBTCOMM, "Decrypted payload ${decryptedPayload.toCommLogHex()}")
        return msg.copy(payload = decryptedPayload)
    }

    fun encrypt(headerMessage: MessagePacket): MessagePacket {
        val payload = headerMessage.payload
        val header = headerMessage.asByteArray(true).copyOfRange(0, 16)

        val n = nonce.increment(true)
        aapsLogger.debug(LTag.PUMPBTCOMM, "Encrypt header ${header.toCommLogHex()} payload: ${payload.toCommLogHex()}")
        aapsLogger.debug(LTag.PUMPBTCOMM, "Encrypt NONCE ${n.toCommLogHex()}")
        val encryptedPayload = aesCcm.encrypt(
            key = ck,
            nonce = n,
            associatedData = header,
            plaintext = payload,
            macSizeBytes = MAC_SIZE
        )

        return headerMessage.copy(payload = encryptedPayload)
    }

    companion object {

        private const val MAC_SIZE = 8
    }
}
