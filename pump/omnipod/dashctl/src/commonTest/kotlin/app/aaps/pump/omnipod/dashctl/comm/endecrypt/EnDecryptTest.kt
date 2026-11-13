package app.aaps.pump.omnipod.dashctl.comm.endecrypt

import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.crypto.DashCryptoException
import app.aaps.pump.omnipod.dashctl.crypto.DashNonce
import app.aaps.pump.omnipod.dashctl.parity.parityAesCcm
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Port of the Android `EnDecryptTest` (pump/omnipod/dash/src/test/.../comm/endecrypt), same
 * vectors, on the AES-CCM seam of each target (SpongyCastle on the JVM, CommonCrypto on iOS).
 */
@Suppress("SpellCheckingInspection")
class EnDecryptTest {

    @Test
    fun decrypt() {
        val received =
            "54,57,11,a1,0c,16,03,00,08,20,2e,a9,08,20,2e,a8,34,7c,b9,7b,38,5d,45,a3,c4,0e,40,4c,55,71,5e,f3,c3,86,50,17,36,7e,62,3c,7d,0b,46,9e,81,cd,fd,9a".replace(
                ",",
                ""
            )
        val decryptedPayload =
            "30,2e,30,3d,00,12,08,20,2e,a9,1c,0a,1d,05,00,16,b0,00,00,00,0b,ff,01,fe".replace(",", "")
        val enDecrypt = EnDecrypt(
            SilentPodLogger,
            DashNonce(
                parityHex("6c,ff,5d,18,b7,61,6c,ae".replace(",", "")),
                22
            ),
            parityHex("55,79,9f,d2,66,64,cb,f6,e4,76,52,5e,2d,ee,52,c6".replace(",", "")),
            parityAesCcm()
        )
        val encryptedMessage = parityHex(received)
        val decrypted = parityHex(decryptedPayload)
        val msg = MessagePacket.parse(encryptedMessage)
        val decryptedMsg = enDecrypt.decrypt(msg)

        assertEquals(decrypted.toParityHex(), decryptedMsg.payload.toParityHex())
    }

    @Test
    fun encrypt() {
        val enDecrypt = EnDecrypt(
            SilentPodLogger,
            DashNonce(
                parityHex("dda23c090a0a0a0a"),
                0
            ),
            parityHex("ba1283744b6de9fab6d9b77d95a71d6e"),
            parityAesCcm()
        )
        val encryptedMessage = parityHex(
            "54571101070003400242000002420001" +
                "e09158bcb0285a81bf30635f3a17ee73f0afbb3286bc524a8a66" +
                "fb1bc5b001e56543"
        )
        val command = parityHex("53302e303d000effffffff00060704ffffffff82b22c47302e30")
        val msg = MessagePacket.parse(encryptedMessage).copy(payload = command) // copy for the headers

        val encrypted = enDecrypt.encrypt(msg)

        assertEquals(encryptedMessage.toParityHex(), encrypted.asByteArray().toParityHex())
    }

    // Not in Android: what happens to the nonce and to a wrong MAC.

    @Test
    fun `a wrong MAC throws and the nonce has still moved on`() {
        val nonce = DashNonce(parityHex("6c,ff,5d,18,b7,61,6c,ae".replace(",", "")), 22)
        val enDecrypt = EnDecrypt(
            SilentPodLogger,
            nonce,
            parityHex("55,79,9f,d2,66,64,cb,f6,e4,76,52,5e,2d,ee,52,c6".replace(",", "")),
            parityAesCcm()
        )
        val bytes = parityHex(
            "5457 11a1 0c16 0300 0820 2ea9 0820 2ea8 347c b97b 385d 45a3 c40e 404c 5571 5ef3 c386 5017 367e 623c 7d0b 469e 81cd fd9b"
                .replace(" ", "")
        ) // the Android capture with the last MAC byte changed (9a -> 9b)

        assertFailsWith<DashCryptoException> { enDecrypt.decrypt(MessagePacket.parse(bytes)) }
        // Android: nonce.increment(false) runs before the cipher, so the counter is 23 after it.
        assertEquals(23L, nonce.sqn)
    }

    @Test
    fun `encrypt uses the pod receiving direction and decrypt the other one`() {
        val prefix = parityHex("0001020304050607")
        val ck = parityHex("00112233445566778899aabbccddeeff")
        val nonce = DashNonce(prefix, 0)
        val enDecrypt = EnDecrypt(SilentPodLogger, nonce, ck, parityAesCcm())
        val msg = MessagePacket.parse(parityHex("54571101070003400242000002420001" + "00".repeat(34)))
            .copy(payload = parityHex("53302e303d000effffffff00060704ffffffff82b22c47302e30"))

        val encrypted = enDecrypt.encrypt(msg)

        // Same call on the seam with the nonce for counter 1, direction bit clear (pod receives).
        val expected = parityAesCcm().encrypt(
            key = ck,
            nonce = prefix + parityHex("0000000001"),
            associatedData = msg.asByteArray(true).copyOfRange(0, 16),
            plaintext = msg.payload,
            macSizeBytes = 8
        )
        assertEquals(expected.toParityHex(), encrypted.payload.toParityHex())
        assertEquals(1L, nonce.sqn)
    }
}
