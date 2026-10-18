package app.aaps.plugins.libre3.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.posix.size_tVar

/**
 * iOS block maker, Apple's own CommonCrypto.
 *
 * This is a real implementation and not a placeholder: AES is a fixed answer, so the known answer
 * test in `commonTest` proves this gives the same 16 bytes as the Android side. Nothing about the
 * Libre 3 session depends on the platform here, only on the block.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun libre3StandardAesBlock(key: ByteArray): Libre3AesBlock {
    val keyCopy = key.copyOf()
    return Libre3AesBlock { input ->
        require(input.size == AES_BLOCK_SIZE) { "an AES block is 16 bytes" }
        val output = ByteArray(AES_BLOCK_SIZE)
        memScoped {
            val moved = alloc<size_tVar>()
            val status = CCCrypt(
                op = kCCEncrypt,
                alg = kCCAlgorithmAES,
                // No padding is asked for, so CommonCrypto writes exactly one block out.
                options = kCCOptionECBMode,
                key = keyCopy.refTo(0),
                keyLength = keyCopy.size.toULong(),
                iv = null,
                dataIn = input.refTo(0),
                dataInLength = input.size.toULong(),
                dataOut = output.refTo(0),
                dataOutAvailable = output.size.toULong(),
                dataOutMoved = moved.ptr,
            )
            if (status != kCCSuccess) {
                throw Libre3CryptoException("CommonCrypto refused an AES block, status $status")
            }
            if (moved.value.toInt() != AES_BLOCK_SIZE) {
                throw Libre3CryptoException("CommonCrypto wrote ${moved.value} bytes, not $AES_BLOCK_SIZE")
            }
        }
        output
    }
}

/** 16 bytes, the size of one AES block. */
private const val AES_BLOCK_SIZE = 16
