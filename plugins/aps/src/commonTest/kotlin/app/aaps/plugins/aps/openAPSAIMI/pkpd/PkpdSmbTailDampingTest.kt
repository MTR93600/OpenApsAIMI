package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tail damping of SMB in the PKPD band, and the one-shot rewrite of legacy stored values.
 *
 * Port of `PkpdSmbTailDampingTest` from `dev_OAPSAIMI`. The relaxed mockk `Preferences` is the
 * hand written [MapPreferences], which records each double `put`; Truth is replaced by `kotlin.test`.
 * The production code is the same as on dev.
 */
class PkpdSmbTailDampingTest {

    @Test
    fun effectiveStoredValueRemapsLegacyBelowCutoffToNeutral() {
        assertEquals(PkpdSmbTailDamping.DAMPING_NEUTRAL, PkpdSmbTailDamping.effectiveStoredValue(0.20))
        assertEquals(PkpdSmbTailDamping.DAMPING_NEUTRAL, PkpdSmbTailDamping.effectiveStoredValue(0.55))
        assertEquals(0.70, PkpdSmbTailDamping.effectiveStoredValue(0.70))
        assertEquals(0.92, PkpdSmbTailDamping.effectiveStoredValue(0.92))
    }

    @Test
    fun stabilityFamilyLadderStaysInsidePkpdBandAndIsMonotonic() {
        val ladder = PkpdSmbTailDamping.STABILITY_FAMILY_FLOOR_LADDER
        assertEquals(5, ladder.size)
        assertEquals(PkpdSmbTailDamping.DAMPING_STRONG, ladder.first())
        assertEquals(PkpdSmbTailDamping.DAMPING_LIGHT, ladder.last())
        ladder.forEach { floor ->
            assertTrue(floor >= PkpdSmbTailDamping.DAMPING_STRONG)
            assertTrue(floor <= PkpdSmbTailDamping.DAMPING_LIGHT)
            assertTrue(floor > PkpdSmbTailDamping.LEGACY_NEUTRAL_CUTOFF)
            assertEquals(floor, PkpdSmbTailDamping.effectiveStoredValue(floor))
        }
        ladder.zipWithNext().forEach { (a, b) ->
            assertTrue(b > a, "ladder not rising: $a then $b")
        }
    }

    @Test
    fun stabilityFamilyScoreMapsSmootherLeftAndReactiveRight() {
        assertEquals(0f, PkpdSmbTailDamping.stabilityFamilyScore(0.70))
        assertEquals(1f, PkpdSmbTailDamping.stabilityFamilyScore(0.92))
        // Legacy very low stored values must not read as "very smooth" after the remap to neutral.
        val legacyScore = PkpdSmbTailDamping.stabilityFamilyScore(0.20)
        assertTrue(legacyScore > 0.4f, "legacy score $legacyScore")
        assertTrue(legacyScore < 0.8f, "legacy score $legacyScore")
    }

    @Test
    fun migrateLegacyStoredPreferenceRewritesCutoffZoneToNeutral() {
        val preferences = MapPreferences()
        preferences.doubles[DoubleKey.OApsAIMISmbTailDamping] = 0.20

        assertTrue(PkpdSmbTailDamping.migrateLegacyStoredPreference(preferences))
        assertEquals(
            listOf<Pair<Any, Double>>(DoubleKey.OApsAIMISmbTailDamping to PkpdSmbTailDamping.DAMPING_NEUTRAL),
            preferences.doublePuts.toList(),
        )
    }

    @Test
    fun migrateLegacyStoredPreferenceSkipsValuesAlreadyInPkpdBand() {
        val preferences = MapPreferences()
        preferences.doubles[DoubleKey.OApsAIMISmbTailDamping] = 0.81

        assertFalse(PkpdSmbTailDamping.migrateLegacyStoredPreference(preferences))
        assertTrue(preferences.doublePuts.isEmpty(), "unexpected writes ${preferences.doublePuts}")
    }
}
