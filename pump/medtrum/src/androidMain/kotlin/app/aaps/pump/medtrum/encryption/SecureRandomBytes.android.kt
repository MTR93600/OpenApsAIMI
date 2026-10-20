package app.aaps.pump.medtrum.encryption

import java.security.SecureRandom

internal actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    SecureRandom().nextBytes(bytes)
    return bytes
}
