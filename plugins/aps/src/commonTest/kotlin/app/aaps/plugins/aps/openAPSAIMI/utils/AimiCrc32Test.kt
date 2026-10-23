package app.aaps.plugins.aps.openAPSAIMI.utils

import kotlin.test.Test
import kotlin.test.assertEquals

class AimiCrc32Test {

    @Test
    fun `check value of the standard matches`() {
        // The published check value of CRC-32/ISO-HDLC, the one ZIP and gzip use.
        assertEquals(0xCBF43926.toInt(), AimiCrc32.of("123456789".encodeToByteArray()))
    }

    @Test
    fun `empty input gives zero`() {
        assertEquals(0, AimiCrc32.of(ByteArray(0)))
    }

    @Test
    fun `feeding in pieces gives the same value as one call`() {
        val data = ByteArray(100_000) { (it * 31 + 7).toByte() }
        val crc = AimiCrc32()
        crc.update(data, 0, 1)
        crc.update(data, 1, 40_000)
        crc.update(data, 40_001, data.size - 40_001)
        assertEquals(AimiCrc32.of(data), crc.value)
    }

    @Test
    fun `reset starts again`() {
        val crc = AimiCrc32()
        crc.update("something else".encodeToByteArray())
        crc.reset()
        crc.update("123456789".encodeToByteArray())
        assertEquals(0xCBF43926.toInt(), crc.value)
    }
}
