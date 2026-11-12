package app.aaps.pump.omnipod.dashctl.comm.packet

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Crc32] must give the same value as `java.util.zip.CRC32`, which Android uses for the packet CRC.
 * The vectors are the standard CRC-32 check values and the CRC from the Android splitter test.
 */
class Crc32Test {

    @Test fun checkValueOfDigitsOneToNine() {
        assertEquals(0xCBF43926L, "123456789".encodeToByteArray().crc32())
    }

    @Test fun emptyArrayIsZero() {
        assertEquals(0L, ByteArray(0).crc32())
    }

    @Test fun quickBrownFox() {
        assertEquals(0x414FA339L, "The quick brown fox jumps over the lazy dog".encodeToByteArray().crc32())
    }

    @Test fun singleZeroByte() {
        assertEquals(0xD202EF8DL, byteArrayOf(0).crc32())
    }

    @Test fun valueIsNeverNegative() {
        // 0xFFFFFFFF bytes give a CRC with the top bit set; it must stay a positive Long.
        val value = byteArrayOf(-1, -1, -1, -1).crc32()
        assertEquals(0xFFFFFFFFL, value)
    }

    @Test fun androidSplitterVector() {
        // The CRC bytes bc 20 1f f6 in the last packet of the Android PayloadSplitterTest.
        val payload = packetHex("54,57,10,23,03,00,00,c0,ff,ff,ff,fe,08,20,2e,a8,50,30,3d,00,01,a5".replace(",", ""))
        assertEquals(0xBC201FF6L, payload.crc32())
    }

    @Test fun updateInPartsIsTheSameAsOnce() {
        val bytes = "123456789".encodeToByteArray()
        val crc = Crc32()
        crc.update(bytes, 0, 4)
        crc.update(bytes, 4, 5)
        assertEquals(0xCBF43926L, crc.value)
        crc.reset()
        assertEquals(0L, crc.value)
    }
}
