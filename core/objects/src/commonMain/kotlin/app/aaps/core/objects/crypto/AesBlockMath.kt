package app.aaps.core.objects.crypto

internal const val AES_BLOCK_BYTES: Int = 16

internal fun requireAesKey(key: ByteArray) {
    require(key.size == 16 || key.size == 24 || key.size == 32) {
        "AES key must be 16, 24 or 32 bytes, was ${key.size}"
    }
}

/** Left shift of a 16-byte big-endian block, then xor 0x87 when the high bit was set. RFC 4493. */
internal fun cmacDouble(block: ByteArray): ByteArray {
    val out = ByteArray(AES_BLOCK_BYTES)
    var carry = 0
    for (i in AES_BLOCK_BYTES - 1 downTo 0) {
        val b = block[i].toInt() and 0xff
        out[i] = ((b shl 1) and 0xff or carry).toByte()
        carry = b ushr 7
    }
    if ((block[0].toInt() and 0x80) != 0) {
        out[AES_BLOCK_BYTES - 1] = (out[AES_BLOCK_BYTES - 1].toInt() xor 0x87).toByte()
    }
    return out
}

internal fun xorBlock(left: ByteArray, right: ByteArray): ByteArray {
    val out = ByteArray(AES_BLOCK_BYTES)
    for (i in 0 until AES_BLOCK_BYTES) {
        out[i] = (left[i].toInt() xor right[i].toInt()).toByte()
    }
    return out
}

/**
 * Compares every byte. A content mismatch does not return early, so the tag check does not stop
 * on the first wrong byte. The lengths are public parameters; a length mismatch is not a secret.
 */
internal fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
    if (left.size != right.size) return false
    var diff = 0
    for (i in left.indices) {
        diff = diff or (left[i].toInt() xor right[i].toInt())
    }
    return diff == 0
}
