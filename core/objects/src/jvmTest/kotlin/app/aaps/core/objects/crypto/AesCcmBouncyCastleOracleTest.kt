package app.aaps.core.objects.crypto

import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * BouncyCastle is an independent AES-CCM implementation, used here only as a test oracle.
 * Production CCM goes through [computeAesCcmEncrypt] and JCE `AES/ECB/NoPadding`, not this provider.
 * The expected bytes are the published NIST SP 800-38C example C.1, so a shared bug cannot hide
 * behind a comparison of this code with itself.
 */
class AesCcmBouncyCastleOracleTest {

    @Test
    fun `BouncyCastle AES-CCM matches NIST SP 800-38C example C1`() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        val key = SecretKeySpec("404142434445464748494a4b4c4d4e4f".fromHexString(), "AES")
        val nonce = "10111213141516".fromHexString()
        val aad = "0001020304050607".fromHexString()
        val plaintext = "20212223".fromHexString()
        val cipher = Cipher.getInstance("AES/CCM/NoPadding", "BC")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(32, nonce))
        cipher.updateAAD(aad)
        val out = cipher.doFinal(plaintext)
        assertEquals("7162015b4dac255d", out.toHexString())
    }
}
