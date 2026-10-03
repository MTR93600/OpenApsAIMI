package app.aaps.core.objects.crypto

import kotlin.math.min

/**
 * AES-CCM, NIST SP 800-38C. RFC 3610 is the same construction on a packet.
 *
 * The authentication tag is compared with [constantTimeEquals]. A mismatch throws
 * [AesCcmAuthenticationException] after wiping the candidate plaintext. The plaintext is not returned.
 */
internal fun computeAesCcmEncrypt(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    associatedData: ByteArray,
    tagBits: Int,
): ByteArray {
    val tagBytes = ccmTagBytes(nonce, tagBits, plaintext.size, key)
    val mac = cbcMac(key, nonce, plaintext, associatedData, tagBytes)
    val out = ByteArray(plaintext.size + tagBytes)
    ctrXor(key, nonce, plaintext, out, destinationOffset = 0)
    val mask = platformAesEcbBlock().encryptBlock(key, counterBlock(nonce, 0))
    for (i in 0 until tagBytes) {
        out[plaintext.size + i] = (mac[i].toInt() xor mask[i].toInt()).toByte()
    }
    return out
}

internal fun computeAesCcmDecrypt(
    key: ByteArray,
    nonce: ByteArray,
    ciphertextAndTag: ByteArray,
    associatedData: ByteArray,
    tagBits: Int,
): ByteArray {
    requireAesKey(key)
    requireCcmNonce(nonce)
    val tagBytes = requireCcmTagBytes(tagBits)
    if (ciphertextAndTag.size < tagBytes) {
        throw IllegalArgumentException(
            "AES-CCM ciphertext is shorter than the authentication tag of $tagBytes bytes",
        )
    }
    val payloadLength = ciphertextAndTag.size - tagBytes
    requirePayloadFits(nonce, payloadLength)
    val cipher = ciphertextAndTag.copyOfRange(0, payloadLength)
    val encryptedTag = ciphertextAndTag.copyOfRange(payloadLength, ciphertextAndTag.size)
    val plaintext = ByteArray(payloadLength)
    ctrXor(key, nonce, cipher, plaintext, destinationOffset = 0)
    val mask = platformAesEcbBlock().encryptBlock(key, counterBlock(nonce, 0))
    val received = ByteArray(tagBytes)
    for (i in 0 until tagBytes) {
        received[i] = (encryptedTag[i].toInt() xor mask[i].toInt()).toByte()
    }
    val expected = cbcMac(key, nonce, plaintext, associatedData, tagBytes)
    if (!constantTimeEquals(received, expected.copyOf(tagBytes))) {
        plaintext.fill(0)
        throw AesCcmAuthenticationException("AES-CCM authentication tag rejected")
    }
    return plaintext
}

private fun ccmTagBytes(nonce: ByteArray, tagBits: Int, payloadLength: Int, key: ByteArray): Int {
    requireAesKey(key)
    requireCcmNonce(nonce)
    val tagBytes = requireCcmTagBytes(tagBits)
    requirePayloadFits(nonce, payloadLength)
    return tagBytes
}

private fun requireCcmNonce(nonce: ByteArray) {
    require(nonce.size in 7..13) {
        "AES-CCM nonce must be 7 to 13 bytes (L = 15 - nonce length), was ${nonce.size}"
    }
}

private fun requireCcmTagBytes(tagBits: Int): Int {
    val tagBytes = tagBits / 8
    require(tagBits % 8 == 0 && tagBytes in 4..16 && tagBytes % 2 == 0) {
        "AES-CCM tag must be an even length from 4 to 16 bytes, was $tagBits bits"
    }
    return tagBytes
}

/** L = 15 - nonce length. The payload length is an integer of L bytes. */
private fun requirePayloadFits(nonce: ByteArray, payloadLength: Int) {
    val lengthField = 15 - nonce.size
    require(payloadLength >= 0) { "AES-CCM payload length must be non-negative" }
    if (lengthField < 4) {
        val max = (1L shl (8 * lengthField)) - 1L
        require(payloadLength.toLong() <= max) {
            "AES-CCM payload length $payloadLength does not fit in L=$lengthField bytes"
        }
    }
}

private fun cbcMac(
    key: ByteArray,
    nonce: ByteArray,
    plaintext: ByteArray,
    associatedData: ByteArray,
    tagBytes: Int,
): ByteArray {
    val aes = platformAesEcbBlock()
    var state = aes.encryptBlock(
        key,
        xorBlock(ByteArray(AES_BLOCK_BYTES), firstBlock(nonce, plaintext.size, tagBytes, associatedData.isNotEmpty())),
    )
    for (block in associatedDataBlocks(associatedData)) {
        state = aes.encryptBlock(key, xorBlock(state, block))
    }
    var offset = 0
    while (offset < plaintext.size) {
        val block = ByteArray(AES_BLOCK_BYTES)
        val n = min(AES_BLOCK_BYTES, plaintext.size - offset)
        plaintext.copyInto(block, destinationOffset = 0, startIndex = offset, endIndex = offset + n)
        state = aes.encryptBlock(key, xorBlock(state, block))
        offset += n
    }
    return state
}

/** B0: flags || nonce || payload length over L bytes. */
private fun firstBlock(nonce: ByteArray, payloadLength: Int, tagBytes: Int, hasAssociatedData: Boolean): ByteArray {
    val lengthField = 15 - nonce.size
    val flags = ((if (hasAssociatedData) 1 else 0) shl 6) or
        (((tagBytes - 2) / 2) shl 3) or
        (lengthField - 1)
    val block = ByteArray(AES_BLOCK_BYTES)
    block[0] = flags.toByte()
    nonce.copyInto(block, destinationOffset = 1)
    writeLength(block, start = AES_BLOCK_BYTES - lengthField, width = lengthField, value = payloadLength.toLong())
    return block
}

private fun associatedDataBlocks(associatedData: ByteArray): List<ByteArray> {
    if (associatedData.isEmpty()) return emptyList()
    val prefix = when {
        associatedData.size < 65_280 -> byteArrayOf(
            (associatedData.size ushr 8).toByte(),
            associatedData.size.toByte(),
        )
        associatedData.size <= 0xFFFF_FFFF.toInt() -> byteArrayOf(
            0xff.toByte(),
            0xfe.toByte(),
            (associatedData.size ushr 24).toByte(),
            (associatedData.size ushr 16).toByte(),
            (associatedData.size ushr 8).toByte(),
            associatedData.size.toByte(),
        )
        else -> error("AES-CCM associated data longer than 2^32-1 bytes is not encoded here")
    }
    val encoded = ByteArray(prefix.size + associatedData.size)
    prefix.copyInto(encoded)
    associatedData.copyInto(encoded, destinationOffset = prefix.size)
    val padded = (encoded.size + AES_BLOCK_BYTES - 1) / AES_BLOCK_BYTES * AES_BLOCK_BYTES
    val blocks = ArrayList<ByteArray>(padded / AES_BLOCK_BYTES)
    var offset = 0
    while (offset < padded) {
        val block = ByteArray(AES_BLOCK_BYTES)
        val n = min(AES_BLOCK_BYTES, encoded.size - offset)
        if (n > 0) encoded.copyInto(block, destinationOffset = 0, startIndex = offset, endIndex = offset + n)
        blocks.add(block)
        offset += AES_BLOCK_BYTES
    }
    return blocks
}

private fun counterBlock(nonce: ByteArray, counter: Int): ByteArray {
    val lengthField = 15 - nonce.size
    val block = ByteArray(AES_BLOCK_BYTES)
    block[0] = (lengthField - 1).toByte()
    nonce.copyInto(block, destinationOffset = 1)
    writeLength(block, start = AES_BLOCK_BYTES - lengthField, width = lengthField, value = counter.toLong())
    return block
}

private fun ctrXor(key: ByteArray, nonce: ByteArray, input: ByteArray, output: ByteArray, destinationOffset: Int) {
    val aes = platformAesEcbBlock()
    var counter = 1
    var offset = 0
    while (offset < input.size) {
        val stream = aes.encryptBlock(key, counterBlock(nonce, counter))
        val n = min(AES_BLOCK_BYTES, input.size - offset)
        for (i in 0 until n) {
            output[destinationOffset + offset + i] =
                (input[offset + i].toInt() xor stream[i].toInt()).toByte()
        }
        offset += n
        counter++
    }
}

private fun writeLength(block: ByteArray, start: Int, width: Int, value: Long) {
    var rest = value
    for (i in width - 1 downTo 0) {
        block[start + i] = (rest and 0xff).toByte()
        rest = rest ushr 8
    }
    check(rest == 0L) { "AES-CCM length $value does not fit in $width bytes" }
}
