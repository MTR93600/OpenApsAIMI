/*
 * KEKS J-PAKE / ExtraData authentication for Dexcom G7 / ONE+.
 *
 * Vendored sources from NightscoutFoundation/xDrip
 *   https://github.com/NightscoutFoundation/xDrip
 *   pin commit 1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f (tag 2026.07.15)
 * See plugins/libkeks/NOTICE.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Part of OpenApsAIMI (AGPL-3.0-or-later); these KEKS sources retain their
 * upstream GPL-3.0 terms. Free software, distributed WITHOUT ANY WARRANTY.
 * See https://www.gnu.org/licenses/gpl-3.0.html
 */
package jamorham.keks.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess
import platform.Foundation.NSRecursiveLock
import platform.posix.arc4random_buf
import platform.posix.size_tVar

/**
 * Apple's own cryptographically secure generator. It never fails and needs no seeding, which is
 * why it is used here rather than `SecRandomCopyBytes` with its status code. The same choice was
 * already made for the Libre 3 driver.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun keksRandomBytes(out: ByteArray) {
    if (out.isEmpty()) return
    out.usePinned { pinned ->
        arc4random_buf(pinned.addressOf(0), out.size.toULong())
    }
}

/**
 * CommonCrypto in ECB mode with no padding, which is the same fixed answer Android's
 * `AES/ECB/NoPadding` gives. The known answer test in commonTest proves it.
 *
 * Null on failure, matching the Java `AESwrapper`, so a refusal becomes a failed handshake rather
 * than a crash in the middle of a Bluetooth callback.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun keksAesEcbEncrypt(key: ByteArray, plaintext: ByteArray): ByteArray? {
    if (plaintext.isEmpty() || plaintext.size % AES_BLOCK_SIZE != 0) return null
    if (key.size != 16 && key.size != 24 && key.size != 32) return null
    val output = ByteArray(plaintext.size)
    memScoped {
        val moved = alloc<size_tVar>()
        val status = CCCrypt(
            op = kCCEncrypt,
            alg = kCCAlgorithmAES,
            options = kCCOptionECBMode,
            key = key.refTo(0),
            keyLength = key.size.toULong(),
            iv = null,
            dataIn = plaintext.refTo(0),
            dataInLength = plaintext.size.toULong(),
            dataOut = output.refTo(0),
            dataOutAvailable = output.size.toULong(),
            dataOutMoved = moved.ptr,
        )
        if (status != kCCSuccess) return null
        if (moved.value.toInt() != plaintext.size) return null
    }
    return output
}

private const val AES_BLOCK_SIZE = 16

/**
 * Prints to standard output, which Xcode shows, under the same tag Android uses so the two logs
 * read the same way. The ONE+ driver's own sink does the same.
 */
internal actual fun keksLogWrite(tag: String, message: String) {
    println("$tag: $message")
}

internal actual class KeksLock actual constructor() {
    private val delegate = NSRecursiveLock()
    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}

internal actual class KeksSecurityException actual constructor(message: String) : RuntimeException(message)

internal actual class KeksInvalidParameterException actual constructor(message: String) :
    IllegalArgumentException(message)

internal actual class KeksArrayIndexOutOfBoundsException actual constructor(message: String) :
    IndexOutOfBoundsException(message)
