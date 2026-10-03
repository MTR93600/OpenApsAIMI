package app.aaps.core.objects.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess

/**
 * CommonCrypto `CCCrypt` with `kCCOptionECBMode` and no PKCS7 flag, so one 16-byte block is the AES
 * permutation. This is the platform cinterop of CommonCrypto (`platform.CoreCrypto`), not a Swift
 * bridge.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun platformAesEcbBlock(): AesEcbBlock = IosAesEcbBlock

@OptIn(ExperimentalForeignApi::class)
private object IosAesEcbBlock : AesEcbBlock {
    override fun encryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        require(block.size == AES_BLOCK_BYTES) { "AES block must be $AES_BLOCK_BYTES bytes, was ${block.size}" }
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES key must be 16, 24 or 32 bytes, was ${key.size}"
        }
        val out = ByteArray(AES_BLOCK_BYTES)
        val status = memScoped {
            val moved = alloc<ULongVar>()
            key.usePinned { keyPin ->
                block.usePinned { inPin ->
                    out.usePinned { outPin ->
                        CCCrypt(
                            kCCEncrypt,
                            kCCAlgorithmAES,
                            kCCOptionECBMode,
                            keyPin.addressOf(0),
                            key.size.convert(),
                            null,
                            inPin.addressOf(0),
                            block.size.convert(),
                            outPin.addressOf(0),
                            out.size.convert(),
                            moved.ptr,
                        )
                    }
                }
            }
        }
        if (status != kCCSuccess) {
            error("CommonCrypto CCCrypt AES-ECB failed with status $status")
        }
        return out
    }
}
