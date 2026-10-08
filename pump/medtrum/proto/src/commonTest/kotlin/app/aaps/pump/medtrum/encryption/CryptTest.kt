package app.aaps.pump.medtrum.encryption

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Ported from the Android driver (pump/medtrum). Truth assertions replaced by kotlin.test.
 * These are safety-critical crypto vectors — the key derivation must be bit-identical.
 */
class CryptTest {

    @Test
    fun givenSNExpectKey() {
        val crypt = Crypt()

        val input = 2859923929
        val expected = 3364239851L
        val output: Long = crypt.keyGen(input)
        assertEquals(expected, output)
    }

    @Test
    fun givenSNExpectReal() {
        val crypt = Crypt()

        val input = 2859923929
        val expected = 126009121L
        val output: Long = crypt.simpleDecrypt(input)
        assertEquals(expected, output)
    }
}
