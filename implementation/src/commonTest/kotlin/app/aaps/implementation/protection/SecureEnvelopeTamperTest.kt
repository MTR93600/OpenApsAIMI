package app.aaps.implementation.protection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Two deterministic mutations of an `<sha256>:<alias>:<iv hex>:<ciphertext hex>` envelope.
 *
 * Writing `"ff"` over the last ciphertext byte leaves the string unchanged when that byte is
 * already `0xFF` (1 in 256). #153 XORs `0x01` into that last byte (`0xFF` → `0xFE`). #237 XORs
 * `0x01` into the middle ciphertext byte, which is a different octet on every envelope longer
 * than one byte. Both stay valid hex. Neither is `dropLast(2) + "ff"`.
 */
internal fun tamperSecureEnvelopeLastByte(encrypted: String): String {
    val hex = "0123456789abcdef"
    val hi = hex.indexOf(encrypted[encrypted.lastIndex - 1])
    val lo = hex.indexOf(encrypted[encrypted.lastIndex])
    val flipped = ((hi shl 4) or lo) xor 0x01
    return encrypted.dropLast(2) + hex[flipped ushr 4] + hex[flipped and 0x0F]
}

internal fun tamperSecureEnvelopeMiddleByte(encrypted: String): String {
    val hex = "0123456789abcdef"
    val cipherStart = encrypted.lastIndexOf(':') + 1
    val cipherLength = encrypted.length - cipherStart
    val midByte = cipherLength / 2 / 2
    val at = cipherStart + midByte * 2
    val hi = hex.indexOf(encrypted[at])
    val lo = hex.indexOf(encrypted[at + 1])
    val flipped = ((hi shl 4) or lo) xor 0x01
    val chars = encrypted.toCharArray()
    chars[at] = hex[(flipped ushr 4) and 0x0F]
    chars[at + 1] = hex[flipped and 0x0F]
    return chars.concatToString()
}

class SecureEnvelopeTamperTest {

    @Test
    fun `a ciphertext that already ends in ff still changes`() {
        val encrypted = stem() + "ff"
        val tampered = tamperSecureEnvelopeLastByte(encrypted)

        assertNotEquals(encrypted, tampered)
        assertEquals("fe", tampered.takeLast(2))
    }

    @Test
    fun `every last ciphertext byte changes`() {
        for (b in 0..255) {
            val encrypted = stem() + byteHex(b)
            val tampered = tamperSecureEnvelopeLastByte(encrypted)
            assertNotEquals(encrypted, tampered, "last byte ${byteHex(b)} was left unchanged")
        }
    }

    @Test
    fun `a ciphertext whose middle byte is ff still changes`() {
        val encrypted = envelopeWithMiddleByte(0xFF)
        val tampered = tamperSecureEnvelopeMiddleByte(encrypted)

        assertNotEquals(encrypted, tampered)
        assertEquals("fe", middleByteHex(tampered))
    }

    @Test
    fun `every middle ciphertext byte changes`() {
        for (b in 0..255) {
            val encrypted = envelopeWithMiddleByte(b)
            val tampered = tamperSecureEnvelopeMiddleByte(encrypted)
            assertNotEquals(encrypted, tampered, "middle byte ${byteHex(b)} was left unchanged")
            assertEquals(byteHex(b xor 0x01), middleByteHex(tampered))
        }
    }

    private fun stem(): String = "aa:alias1:" + "00".repeat(12) + ":" + "ab".repeat(8)

    private fun envelopeWithMiddleByte(b: Int): String {
        val cipher = "ab".repeat(8) + byteHex(b) + "cd".repeat(8)
        return "aa:alias1:" + "00".repeat(12) + ":" + cipher
    }

    private fun middleByteHex(encrypted: String): String {
        val cipher = encrypted.substring(encrypted.lastIndexOf(':') + 1)
        val at = (cipher.length / 2 / 2) * 2
        return cipher.substring(at, at + 2)
    }

    private fun byteHex(b: Int): String {
        val hex = "0123456789abcdef"
        return "${hex[(b ushr 4) and 0x0F]}${hex[b and 0x0F]}"
    }
}
