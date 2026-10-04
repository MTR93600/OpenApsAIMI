package app.aaps.implementation.protection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Last ciphertext byte of an `<sha256>:<alias>:<iv hex>:<ciphertext hex>` envelope.
 *
 * Writing `"ff"` over that byte leaves the string unchanged when the byte is already `0xFF`
 * (1 in 256). XOR `0x01` changes every byte, including `0xFF` → `0xFE`, and stays valid hex.
 */
internal fun tamperSecureEnvelope(encrypted: String): String {
    val hex = "0123456789abcdef"
    val hi = hex.indexOf(encrypted[encrypted.lastIndex - 1])
    val lo = hex.indexOf(encrypted[encrypted.lastIndex])
    val flipped = ((hi shl 4) or lo) xor 0x01
    return encrypted.dropLast(2) + hex[flipped ushr 4] + hex[flipped and 0x0F]
}

class SecureEnvelopeTamperTest {

    @Test
    fun `a ciphertext that already ends in ff still changes`() {
        val encrypted = stem() + "ff"
        val tampered = tamperSecureEnvelope(encrypted)

        assertNotEquals(encrypted, tampered)
        assertEquals("fe", tampered.takeLast(2))
    }

    @Test
    fun `every last ciphertext byte changes`() {
        for (b in 0..255) {
            val encrypted = stem() + byteHex(b)
            val tampered = tamperSecureEnvelope(encrypted)
            assertNotEquals(encrypted, tampered, "last byte ${byteHex(b)} was left unchanged")
        }
    }

    private fun stem(): String = "aa:alias1:" + "00".repeat(12) + ":" + "ab".repeat(8)

    private fun byteHex(b: Int): String {
        val hex = "0123456789abcdef"
        return "${hex[b shr 4]}${hex[b and 0x0F]}"
    }
}
