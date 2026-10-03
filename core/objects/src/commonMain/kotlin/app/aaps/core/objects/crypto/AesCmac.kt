package app.aaps.core.objects.crypto

/**
 * AES-CMAC, RFC 4493. The only platform call is one AES block encryption.
 */
internal fun computeAesCmac(key: ByteArray, message: ByteArray): ByteArray {
    requireAesKey(key)
    val aes = platformAesEcbBlock()
    val subkeyL = aes.encryptBlock(key, ByteArray(AES_BLOCK_BYTES))
    val k1 = cmacDouble(subkeyL)
    val k2 = cmacDouble(k1)
    val complete = message.isNotEmpty() && message.size % AES_BLOCK_BYTES == 0
    val fullBlocks = if (complete) message.size / AES_BLOCK_BYTES - 1 else message.size / AES_BLOCK_BYTES
    var state = ByteArray(AES_BLOCK_BYTES)
    var offset = 0
    repeat(fullBlocks) {
        val block = message.copyOfRange(offset, offset + AES_BLOCK_BYTES)
        state = aes.encryptBlock(key, xorBlock(state, block))
        offset += AES_BLOCK_BYTES
    }
    val last = ByteArray(AES_BLOCK_BYTES)
    if (complete) {
        message.copyInto(last, destinationOffset = 0, startIndex = offset, endIndex = offset + AES_BLOCK_BYTES)
        for (i in last.indices) last[i] = (last[i].toInt() xor k1[i].toInt()).toByte()
    } else {
        val remaining = message.size - offset
        if (remaining > 0) {
            message.copyInto(last, destinationOffset = 0, startIndex = offset, endIndex = message.size)
        }
        last[remaining] = 0x80.toByte()
        for (i in last.indices) last[i] = (last[i].toInt() xor k2[i].toInt()).toByte()
    }
    return aes.encryptBlock(key, xorBlock(state, last))
}
