package app.aaps.pump.omnipod.dashctl.comm.message

import app.aaps.pump.omnipod.dashctl.comm.exceptions.MessageIOException
import app.aaps.pump.omnipod.dashctl.comm.toCommLogHex
import app.aaps.pump.omnipod.dashctl.pod.util.PodByteBuffer

/***
 *  String prefix and length encoding and decoding. Example message:
 *
 * Port of the Android `StringLengthPrefixEncoding`. Only change: `java.nio.ByteBuffer` is replaced
 * by [PodByteBuffer], and `String.toByteArray()` by `encodeToByteArray()` (both UTF-8).
 *
 * Known Android behaviour kept as is in [parseKeys]:
 * - the length is decoded as `(b0 shl 1) or b1`, not `(b0 shl 8) or b1`. It only matters when a
 *   value is 256 bytes or longer;
 * - the check before reading the value is `size >= length`, not `size >= 2 + length`, so a short
 *   payload ends in an `IndexOutOfBoundsException` from `copyOfRange`, not a [MessageIOException].
 */
class StringLengthPrefixEncoding private constructor() {

    companion object {

        private const val LENGTH_BYTES = 2

        fun parseKeys(keys: Array<String>, payload: ByteArray): Array<ByteArray> {
            val ret = Array(keys.size) { ByteArray(0) }
            var remaining = payload
            for ((index, key) in keys.withIndex()) {
                remaining.assertSizeAtLeast(key.length)
                when {
                    remaining.copyOfRange(0, key.length).decodeToString() != key ->
                        throw MessageIOException("Key not found: $key in ${payload.toCommLogHex()}")
                    // last key can be empty, no length
                    index == keys.size - 1 && remaining.size == key.length       ->
                        return ret
                }
                remaining.assertSizeAtLeast(key.length + LENGTH_BYTES)

                remaining = remaining.copyOfRange(key.length, remaining.size)
                val length = (remaining[0].toUnsignedInt() shl 1) or remaining[1].toUnsignedInt()
                remaining.assertSizeAtLeast(length)
                ret[index] = remaining.copyOfRange(LENGTH_BYTES, LENGTH_BYTES + length)
                remaining = remaining.copyOfRange(LENGTH_BYTES + length, remaining.size)
            }
            return ret
        }

        fun formatKeys(keys: Array<String>, payloads: Array<ByteArray>): ByteArray {
            val payloadTotalSize = payloads.fold(0) { acc, i -> acc + i.size }
            val keyTotalSize = keys.fold(0) { acc, i -> acc + i.length }
            val zeros = payloads.fold(0) { acc, i -> acc + if (i.isEmpty()) 1 else 0 }

            val bb = PodByteBuffer.allocate(2 * (keys.size - zeros) + keyTotalSize + payloadTotalSize)
            // PodByteBuffer has no position(), so count the written bytes here.
            var position = 0
            for (idx in keys.indices) {
                val k = keys[idx]
                val payload = payloads[idx]
                val keyBytes = k.encodeToByteArray()
                bb.put(keyBytes)
                position += keyBytes.size
                if (payload.isNotEmpty()) {
                    bb.putShort(payload.size.toShort())
                    bb.put(payload)
                    position += LENGTH_BYTES + payload.size
                }
            }

            // Same as Android: copy bytes [0, position).
            return bb.array().copyOf(position)
        }
    }
}

private fun ByteArray.assertSizeAtLeast(size: Int) {
    if (this.size < size) {
        throw MessageIOException("Payload too short: ${this.toCommLogHex()}")
    }
}
