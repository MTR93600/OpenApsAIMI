package app.aaps.pump.omnipod.dashctl.crypto

import java.security.SecureRandom

/** [SecureRandomBytes] on `java.security.SecureRandom`. */
class JvmSecureRandomBytes : SecureRandomBytes {

    private val secureRandom = SecureRandom()

    override fun nextBytes(length: Int): ByteArray =
        ByteArray(length).also(secureRandom::nextBytes)
}
