package app.aaps.plugins.libre3.crypto

/**
 * The certificate this phone shows to a sensor during a first pairing.
 *
 * Shape, 162 bytes:
 * - 0 to 1: family. Only `03 03` is accepted. Live sensors refuse the `03 00` family.
 * - 2 to 17: a fixed test pattern
 * - 18 to 32: a fixed header
 * - 33: `0x04`, which marks an uncompressed point
 * - 34 to 97: the public point of this phone, X then Y
 * - 98 to 161: the signature
 *
 * The bytes come from the MIT LibreCRKit tree, see `plugins/libre3/NOTICE`. When the file is not
 * in the build, [bundled] returns null and a first pairing simply cannot start. A reconnect to a
 * sensor that is already paired does not need this certificate.
 */
class Libre3PhoneCert private constructor(val raw: ByteArray) {

    /** The public point of this phone, 65 bytes, starting with `0x04`. */
    val staticPublicKey: ByteArray get() = raw.copyOfRange(PUBLIC_KEY_START, PUBLIC_KEY_END)

    /** Only the `03 03` family works on a live sensor. */
    val isAcceptedFamily: Boolean
        get() = raw[0] == ACCEPTED_FAMILY[0] && raw[1] == ACCEPTED_FAMILY[1]

    companion object {

        const val TOTAL_SIZE = 162
        const val PUBLIC_KEY_START = 33
        const val PUBLIC_KEY_END = 98

        /** The family that live sensors accept. */
        val ACCEPTED_FAMILY = byteArrayOf(0x03, 0x03)

        /** The family that live sensors refuse. It exists only so it can be named and rejected. */
        val REFUSED_FAMILY = byteArrayOf(0x03, 0x00)

        /**
         * Reads a certificate and refuses anything that is not usable.
         *
         * @throws Libre3CryptoException on a wrong size, a wrong family, or a point that is not
         *   in the uncompressed form.
         */
        fun parse(raw: ByteArray): Libre3PhoneCert {
            if (raw.size != TOTAL_SIZE) {
                throw Libre3CryptoException("a phone certificate must be 162 bytes, not ${raw.size}")
            }
            if (raw[0] != ACCEPTED_FAMILY[0] || raw[1] != ACCEPTED_FAMILY[1]) {
                throw Libre3CryptoException(
                    "this phone certificate family is refused by live sensors, " +
                        "found ${"%02X %02X".format(raw[0], raw[1])}"
                )
            }
            if (raw[PUBLIC_KEY_START] != 0x04.toByte()) {
                throw Libre3CryptoException("the public point of the certificate is not in the uncompressed form")
            }
            return Libre3PhoneCert(raw)
        }

        /**
         * The certificate that ships with the app, or null when the file is not in this build.
         *
         * Callers must treat null as "a first pairing is not possible", never as a reason to build
         * a certificate of their own. A certificate cannot be made up: it carries a signature that
         * only the sensor maker can produce.
         */
        fun bundled(): Libre3PhoneCert? {
            val raw = Libre3RuntimeTables.load(Libre3RuntimeTables.PHONE_CERT) ?: return null
            return try {
                parse(raw)
            } catch (_: Libre3CryptoException) {
                // A file that is there but wrong is worse than none, so it is refused rather than
                // sent to a sensor.
                null
            }
        }
    }
}
