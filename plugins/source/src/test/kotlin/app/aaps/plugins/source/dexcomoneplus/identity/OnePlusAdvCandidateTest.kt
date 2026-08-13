package app.aaps.plugins.source.dexcomoneplus.identity

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OnePlusAdvCandidateTest {

    @Test
    fun isG7FamilyName_matchesJugglucoPrefixes() {
        assertThat(OnePlusAdvCandidate.isG7FamilyName("DX02aS")).isTrue()
        assertThat(OnePlusAdvCandidate.isG7FamilyName("DX01xx")).isTrue()
        assertThat(OnePlusAdvCandidate.isG7FamilyName("DXCMab")).isTrue()
        assertThat(OnePlusAdvCandidate.isG7FamilyName("DexcomONE")).isFalse()
        assertThat(OnePlusAdvCandidate.isG7FamilyName("Dexcom65")).isFalse()
    }

    @Test
    fun nameMatchesSoft_rejectsG6MarketingNames() {
        assertThat(OnePlusAdvCandidate.nameMatchesSoft("DX02aS")).isTrue()
        assertThat(OnePlusAdvCandidate.nameMatchesSoft("Dexcom65")).isFalse()
        assertThat(OnePlusAdvCandidate.nameMatchesSoft("DexcomONE")).isFalse()
    }

    @Test
    fun isCandidate_rejectsDexcom65UnlessStickyMac() {
        assertThat(
            OnePlusAdvCandidate.isCandidate("Dexcom65", "F2:08:F3:22:6B:77", session = null),
        ).isFalse()
        val sticky = OnePlusStoredSession(
            identity = OnePlusSensorIdentity(pin = "1234"),
            lastMac = "F2:08:F3:22:6B:77",
            lastDeviceName = "Dexcom65",
        )
        // Sticky MAC still matches (reconnect to stored device) — UI should not offer it for ONE+.
        assertThat(
            OnePlusAdvCandidate.isCandidate("Dexcom65", "F2:08:F3:22:6B:77", sticky),
        ).isTrue()
    }

    @Test
    fun stickyMacAndNamePreferred() {
        val session = OnePlusStoredSession(
            identity = OnePlusSensorIdentity(pin = "1234", serial = "SER1"),
            lastMac = "AA:BB:CC:DD:EE:FF",
            lastDeviceName = "DX02aS",
        )
        assertThat(
            OnePlusAdvCandidate.isCandidate("DX02aS", "11:22:33:44:55:66", session),
        ).isTrue()
        assertThat(
            OnePlusAdvCandidate.isCandidate("Other", "AA:BB:CC:DD:EE:FF", session),
        ).isTrue()
        assertThat(
            OnePlusAdvCandidate.rankScore("DX02aS", "AA:BB:CC:DD:EE:FF", -70, session),
        ).isGreaterThan(
            OnePlusAdvCandidate.rankScore("DX02zz", "11:22:33:44:55:66", -40, session),
        )
    }
}
