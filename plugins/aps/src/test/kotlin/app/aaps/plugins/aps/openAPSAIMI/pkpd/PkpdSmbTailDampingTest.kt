package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class PkpdSmbTailDampingTest {

    @Test
    fun effectiveStoredValue_remapsLegacyBelowCutoffToNeutral() {
        assertThat(PkpdSmbTailDamping.effectiveStoredValue(0.20)).isEqualTo(PkpdSmbTailDamping.DAMPING_NEUTRAL)
        assertThat(PkpdSmbTailDamping.effectiveStoredValue(0.55)).isEqualTo(PkpdSmbTailDamping.DAMPING_NEUTRAL)
        assertThat(PkpdSmbTailDamping.effectiveStoredValue(0.70)).isEqualTo(0.70)
        assertThat(PkpdSmbTailDamping.effectiveStoredValue(0.92)).isEqualTo(0.92)
    }

    @Test
    fun stabilityFamilyLadder_staysInsidePkpdBandAndIsMonotonic() {
        val ladder = PkpdSmbTailDamping.STABILITY_FAMILY_FLOOR_LADDER
        assertThat(ladder).hasSize(5)
        assertThat(ladder.first()).isEqualTo(PkpdSmbTailDamping.DAMPING_STRONG)
        assertThat(ladder.last()).isEqualTo(PkpdSmbTailDamping.DAMPING_LIGHT)
        ladder.forEach { floor ->
            assertThat(floor).isAtLeast(PkpdSmbTailDamping.DAMPING_STRONG)
            assertThat(floor).isAtMost(PkpdSmbTailDamping.DAMPING_LIGHT)
            assertThat(floor).isGreaterThan(PkpdSmbTailDamping.LEGACY_NEUTRAL_CUTOFF)
            assertThat(PkpdSmbTailDamping.effectiveStoredValue(floor)).isEqualTo(floor)
        }
        ladder.zipWithNext().forEach { (a, b) ->
            assertThat(b).isGreaterThan(a)
        }
    }

    @Test
    fun stabilityFamilyScore_mapsSmootherLeftAndReactiveRight() {
        assertThat(PkpdSmbTailDamping.stabilityFamilyScore(0.70)).isEqualTo(0f)
        assertThat(PkpdSmbTailDamping.stabilityFamilyScore(0.92)).isEqualTo(1f)
        // Legacy ultra-low stored values must not read as "very smooth" after remap to neutral.
        val legacyScore = PkpdSmbTailDamping.stabilityFamilyScore(0.20)
        assertThat(legacyScore).isGreaterThan(0.4f)
        assertThat(legacyScore).isLessThan(0.8f)
    }

    @Test
    fun migrateLegacyStoredPreference_rewritesCutoffZoneToNeutral() {
        val preferences = mockk<Preferences>(relaxed = true)
        every { preferences.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.20

        assertThat(PkpdSmbTailDamping.migrateLegacyStoredPreference(preferences)).isTrue()
        verify { preferences.put(DoubleKey.OApsAIMISmbTailDamping, PkpdSmbTailDamping.DAMPING_NEUTRAL) }
    }

    @Test
    fun migrateLegacyStoredPreference_skipsValuesAlreadyInPkpdBand() {
        val preferences = mockk<Preferences>(relaxed = true)
        every { preferences.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.81

        assertThat(PkpdSmbTailDamping.migrateLegacyStoredPreference(preferences)).isFalse()
        verify(exactly = 0) { preferences.put(DoubleKey.OApsAIMISmbTailDamping, any<Double>()) }
    }
}
