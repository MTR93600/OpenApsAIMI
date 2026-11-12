package app.aaps.pump.omnipod.dashctl.comm.packet

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of the Android `PayloadSplitJoinTest`: every size from 0 to 250 bytes is split and joined
 * back. [JavaRandom] gives the same bytes as Android's `java.util.Random(42)`, so the payloads
 * are the same as in the Android test.
 */
class PayloadSplitJoinTest {

    private val random = JavaRandom(42)

    @Test fun testSplitAndJoinBack() {
        for (s in 0..250) {
            val payload = ByteArray(s)
            random.nextBytes(payload)
            val splitter = PayloadSplitter(payload)
            val packets = splitter.splitInPackets()
            val joiner = PayloadJoiner(packets[0].toByteArray())
            for (p in packets.subList(1, packets.size)) {
                joiner.accumulate(p.toByteArray())
            }
            val got = joiner.finalize()
            assertEquals(payload.toPacketHex(), got.toPacketHex())
        }
    }

    @Test fun javaRandomMatchesJavaUtilRandom() {
        // new java.util.Random(42).nextInt() is -1170105035, a value well known from the JDK.
        assertEquals(-1170105035, JavaRandom(42).nextInt())
    }

    /** The linear congruential generator of `java.util.Random`, only what this test needs. */
    private class JavaRandom(seed: Long) {

        private var seed: Long = (seed xor MULTIPLIER) and MASK

        private fun next(bits: Int): Int {
            seed = (seed * MULTIPLIER + ADDEND) and MASK
            return (seed ushr (48 - bits)).toInt()
        }

        fun nextInt(): Int = next(32)

        fun nextBytes(bytes: ByteArray) {
            var i = 0
            while (i < bytes.size) {
                var rnd = nextInt()
                var n = minOf(bytes.size - i, 4)
                while (n-- > 0) {
                    bytes[i++] = rnd.toByte()
                    rnd = rnd shr 8
                }
            }
        }

        private companion object {

            const val MULTIPLIER = 0x5DEECE66DL
            const val ADDEND = 0xBL
            const val MASK = (1L shl 48) - 1
        }
    }
}
