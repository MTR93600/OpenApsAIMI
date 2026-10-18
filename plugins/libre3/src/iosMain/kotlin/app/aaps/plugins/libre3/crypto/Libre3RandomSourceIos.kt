package app.aaps.plugins.libre3.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.arc4random_buf

/**
 * iOS secure random.
 *
 * `arc4random_buf` is Apple's own cryptographically secure generator. It never fails and needs no
 * seeding, which is why it is used here rather than `SecRandomCopyBytes` with its status code.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun libre3SecureRandom(): Libre3RandomSource = Libre3RandomSource { out ->
    if (out.isEmpty()) return@Libre3RandomSource
    out.usePinned { pinned ->
        arc4random_buf(pinned.addressOf(0), out.size.toULong())
    }
}
