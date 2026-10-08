package app.aaps.pump.omnipod.dashctl.session

/** Malformed key/value payload. */
class DashCodecException(message: String) : Exception(message)

/**
 * Minimal key/value framing for the pairing messages (SP1+SP2, SPS1, SPS2, SP0GP0, P0).
 *
 * This mirrors the wire format of the Android `StringLengthPrefixEncoding` /
 * `PairMessage` in pure Kotlin: each entry is `key bytes || 2-byte big-endian
 * length || payload`, and a trailing key with an empty payload carries no length
 * (used for SP0GP0). The produced bytes are identical to what the Android driver
 * sends for these messages.
 *
 * Out of scope (real BLE message layer, D4/platform): the `MessagePacket`
 * envelope (sequence numbers, source/destination addresses, message type,
 * MTU fragmentation) and the `PairMessage` class itself.
 *
 * One deliberate deviation from the Android original: its `parseKeys` decodes
 * the length with `(b0 shl 1) or b1` instead of `(b0 shl 8) or b1` (almost
 * certainly a typo). This port uses the correct big-endian decode, which is
 * behaviorally identical for every real pairing message (all payloads are
 * shorter than 256 bytes, so the high length byte is always 0).
 */
object DashKeyValueCodec {

    private const val LENGTH_BYTES = 2

    /**
     * Encodes entries as `key || length || payload`, skipping the length for
     * empty payloads (matching the Android `formatKeys`).
     */
    fun formatKeys(keys: Array<String>, payloads: Array<ByteArray>): ByteArray {
        require(keys.size == payloads.size) { "keys and payloads must match" }
        val keyBytes = keys.map { it.encodeToByteArray() }
        val total = keyBytes.sumOf { it.size } +
            payloads.sumOf { if (it.isEmpty()) 0 else LENGTH_BYTES + it.size }
        val out = ByteArray(total)
        var pos = 0
        for (i in keys.indices) {
            val kb = keyBytes[i]
            kb.copyInto(out, pos)
            pos += kb.size
            val p = payloads[i]
            if (p.isNotEmpty()) {
                out[pos++] = ((p.size ushr 8) and 0xff).toByte()
                out[pos++] = (p.size and 0xff).toByte()
                p.copyInto(out, pos)
                pos += p.size
            }
        }
        return out
    }

    /**
     * Parses entries written by [formatKeys]. Throws [DashCodecException] when a
     * key is missing or the payload is truncated.
     */
    fun parseKeys(keys: Array<String>, payload: ByteArray): Array<ByteArray> {
        val ret = Array(keys.size) { ByteArray(0) }
        var pos = 0
        for ((index, key) in keys.withIndex()) {
            val kb = key.encodeToByteArray()
            if (pos + kb.size > payload.size) {
                throw DashCodecException("Payload too short for key $key")
            }
            if (!payload.copyOfRange(pos, pos + kb.size).contentEquals(kb)) {
                throw DashCodecException("Key not found: $key")
            }
            pos += kb.size
            // Last key may be empty with no length (SP0GP0, and P0-style acks).
            if (index == keys.size - 1 && pos == payload.size) {
                return ret
            }
            if (pos + LENGTH_BYTES > payload.size) {
                throw DashCodecException("Payload too short for length of $key")
            }
            val length = ((payload[pos].toInt() and 0xff) shl 8) or (payload[pos + 1].toInt() and 0xff)
            pos += LENGTH_BYTES
            if (pos + length > payload.size) {
                throw DashCodecException("Payload too short for value of $key")
            }
            ret[index] = payload.copyOfRange(pos, pos + length)
            pos += length
        }
        return ret
    }
}
