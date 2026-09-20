package app.aaps.plugins.aps.openAPSAIMI.sos

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class EmergencySosManagerTest {

    @Test
    fun `uniquePhoneNumbers drops empties and deduplicates same contact`() {
        val phones = EmergencySosManager.uniquePhoneNumbers(
            "+33 6 12 34 56 78",
            "",
            "0033612345678",
            "+33612345678",
            "  ",
            "+41 79 000 00 00",
        )
        assertThat(phones).hasSize(2)
        assertThat(phones[0]).isEqualTo("+33 6 12 34 56 78")
        assertThat(phones[1]).isEqualTo("+41 79 000 00 00")
    }

    @Test
    fun `uniquePhoneNumbers keeps two distinct contacts`() {
        val phones = EmergencySosManager.uniquePhoneNumbers("+33611111111", "+33622222222")
        assertThat(phones).containsExactly("+33611111111", "+33622222222").inOrder()
    }

    @Test
    fun `phoneFingerprint normalizes plus and 00 prefixes`() {
        assertThat(EmergencySosManager.phoneFingerprint("+33612345678"))
            .isEqualTo(EmergencySosManager.phoneFingerprint("0033612345678"))
    }
}
