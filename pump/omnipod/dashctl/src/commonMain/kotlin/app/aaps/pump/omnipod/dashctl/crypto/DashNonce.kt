package app.aaps.pump.omnipod.dashctl.crypto

/**
 * Pod message nonce: 8-byte prefix + 5-byte big-endian sequence number.
 * The most significant bit of the first sequence byte marks the direction:
 * clear when the PDM sends, set when the pod sends.
 *
 * Ported from the Android `Nonce` (which used `java.nio.ByteBuffer`); the byte layout
 * is identical: `sqn++`, then bytes 3..7 of the big-endian long, with the direction bit.
 */
class DashNonce(val prefix: ByteArray, var sqn: Long) {

    init {
        require(prefix.size == PREFIX_SIZE) { "Nonce prefix should be $PREFIX_SIZE bytes long" }
    }

    /**
     * Increments the sequence number and returns the 13-byte nonce.
     * @param podReceiving true when the pod is the receiver (PDM sends), false otherwise.
     */
    fun increment(podReceiving: Boolean): ByteArray {
        sqn++
        // Big-endian bytes 3..7 of sqn (5 bytes), matching ByteBuffer.putLong().copyOfRange(3, 8).
        val seq = ByteArray(SEQ_SIZE)
        for (i in 0 until SEQ_SIZE) {
            seq[i] = ((sqn shr (8 * (SEQ_SIZE - 1 - i))) and 0xff).toByte()
        }
        if (podReceiving) {
            seq[0] = (seq[0].toInt() and 127).toByte()
        } else {
            seq[0] = (seq[0].toInt() or 128).toByte()
        }
        return prefix + seq
    }

    companion object {
        const val PREFIX_SIZE = 8
        const val SEQ_SIZE = 5
        const val NONCE_SIZE = PREFIX_SIZE + SEQ_SIZE
    }
}
