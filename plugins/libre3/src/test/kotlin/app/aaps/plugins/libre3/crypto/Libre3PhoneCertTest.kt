package app.aaps.plugins.libre3.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The certificate rules. No real certificate is used here, only shapes, because the real bytes are
 * not in this repository yet, see `docs/LIBRE3_NATIVE_LICENCE_MEMO.md`.
 */
class Libre3PhoneCertTest {

    /** Builds a certificate shaped blob whose first two bytes are the given family. */
    private fun certOfFamily(family: ByteArray): ByteArray {
        val raw = ByteArray(Libre3PhoneCert.TOTAL_SIZE) { (it % 251).toByte() }
        family.copyInto(raw, 0)
        raw[Libre3PhoneCert.PUBLIC_KEY_START] = 0x04
        return raw
    }

    @Test
    fun `the accepted family is read`() {
        val cert = Libre3PhoneCert.parse(certOfFamily(Libre3PhoneCert.ACCEPTED_FAMILY))

        assertThat(cert.isAcceptedFamily).isTrue()
        assertThat(cert.staticPublicKey).hasLength(65)
        assertThat(cert.staticPublicKey[0]).isEqualTo(0x04.toByte())
    }

    @Test
    fun `the family that live sensors refuse is refused here too`() {
        assertThrows<Libre3CryptoException> { Libre3PhoneCert.parse(certOfFamily(Libre3PhoneCert.REFUSED_FAMILY)) }
    }

    @Test
    fun `a certificate of the wrong size is refused`() {
        assertThrows<Libre3CryptoException> { Libre3PhoneCert.parse(ByteArray(161)) }
        assertThrows<Libre3CryptoException> { Libre3PhoneCert.parse(ByteArray(163)) }
    }

    @Test
    fun `a certificate whose point is not uncompressed is refused`() {
        val raw = certOfFamily(Libre3PhoneCert.ACCEPTED_FAMILY)
        raw[Libre3PhoneCert.PUBLIC_KEY_START] = 0x02

        assertThrows<Libre3CryptoException> { Libre3PhoneCert.parse(raw) }
    }

    @Test
    fun `the certificate that ships with the app, when it is there, is the accepted family`() {
        val bundled = Libre3PhoneCert.bundled()

        // The file is optional in the build. When it is missing a first pairing is simply not
        // possible, and nothing is ever made up in its place. When it is there it must be right.
        if (bundled == null) {
            assertThat(Libre3RuntimeTables.load(Libre3RuntimeTables.PHONE_CERT)).isNull()
        } else {
            assertThat(bundled.raw).hasLength(162)
            assertThat(bundled.isAcceptedFamily).isTrue()
            assertThat(bundled.staticPublicKey[0]).isEqualTo(0x04.toByte())
        }
    }

    @Test
    fun `a missing table is named, so the reason is never a silent failure`() {
        val missing = Libre3RuntimeTables.missingTables()

        if (missing.isEmpty()) {
            assertThat(Libre3RuntimeTables.pairingTablesPresent()).isTrue()
        } else {
            assertThat(Libre3RuntimeTables.pairingTablesPresent()).isFalse()
            assertThat(missing).contains(Libre3RuntimeTables.PHONE_CERT)
        }
    }
}
