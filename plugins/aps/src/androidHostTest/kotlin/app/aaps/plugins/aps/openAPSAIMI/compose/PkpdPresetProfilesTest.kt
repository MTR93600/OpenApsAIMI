package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The anchor is the soft target the PK/PD learner is pulled back to. When a preset wrote an
 * anchor below its own DIA floor, the learned DIA was pulled onto that floor and stayed there.
 * These tests keep every preset self-consistent.
 *
 * The three generation-counter cases of the reference file are already in
 * `PkpdPresetProfilesLearnedGenerationTest`, so they are not repeated here. Stubbed [Preferences]
 * needs Mockito, which is JVM only, hence `androidHostTest`.
 */
class PkpdPresetProfilesTest {

    private val stored = mutableMapOf<DoubleKey, Double>()

    private fun preferences(): Preferences {
        val preferences = mock<Preferences>()
        whenever(preferences.put(any<DoubleNonPreferenceKey>(), any())).thenAnswer { invocation ->
            val key = invocation.getArgument<DoubleNonPreferenceKey>(0)
            if (key is DoubleKey) stored[key] = invocation.getArgument(1)
            null
        }
        whenever(preferences.get(any<DoubleKey>())).thenAnswer { invocation ->
            val key = invocation.getArgument<DoubleKey>(0)
            stored[key] ?: key.defaultValue
        }
        return preferences
    }

    private fun apply(preset: PkpdInsulinPreset) {
        stored.clear()
        applyPkpdInsulinPreset(preferences(), preset)
    }

    private fun value(key: DoubleKey): Double = stored.getValue(key)

    @Test
    fun `every preset keeps its anchors inside its own bounds`() {
        for (preset in listOf(PkpdInsulinPreset.ULTRA_FAST, PkpdInsulinPreset.RAPID, PkpdInsulinPreset.STANDARD)) {
            apply(preset)
            val diaMin = value(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)
            val diaMax = value(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)
            val peakMin = value(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin)
            val peakMax = value(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax)
            val anchorDia = value(DoubleKey.OApsAIMIPkpdAnchorDiaH)
            val anchorPeak = value(DoubleKey.OApsAIMIPkpdAnchorPeakMin)
            assertTrue(anchorDia in diaMin..diaMax, "$preset anchor DIA $anchorDia outside [$diaMin, $diaMax]")
            assertTrue(anchorPeak in peakMin..peakMax, "$preset anchor peak $anchorPeak outside [$peakMin, $peakMax]")
        }
    }

    @Test
    fun `every preset anchors on its own initial values`() {
        for (preset in listOf(PkpdInsulinPreset.ULTRA_FAST, PkpdInsulinPreset.RAPID, PkpdInsulinPreset.STANDARD)) {
            apply(preset)
            assertEquals(
                value(DoubleKey.OApsAIMIPkpdInitialDiaH),
                value(DoubleKey.OApsAIMIPkpdAnchorDiaH),
                1e-9,
                "$preset anchor DIA must equal its initial DIA",
            )
            assertEquals(
                value(DoubleKey.OApsAIMIPkpdInitialPeakMin),
                value(DoubleKey.OApsAIMIPkpdAnchorPeakMin),
                1e-9,
                "$preset anchor peak must equal its initial peak",
            )
        }
    }

    @Test
    fun `anchor values are the agreed ones`() {
        apply(PkpdInsulinPreset.ULTRA_FAST)
        assertEquals(6.0, value(DoubleKey.OApsAIMIPkpdAnchorDiaH), 1e-9)
        apply(PkpdInsulinPreset.RAPID)
        assertEquals(6.5, value(DoubleKey.OApsAIMIPkpdAnchorDiaH), 1e-9)
        apply(PkpdInsulinPreset.STANDARD)
        assertEquals(8.0, value(DoubleKey.OApsAIMIPkpdAnchorDiaH), 1e-9)
    }

    @Test
    fun `custom preset writes nothing`() {
        apply(PkpdInsulinPreset.CUSTOM)
        assertTrue(stored.isEmpty(), "CUSTOM must not overwrite the user values")
    }

    /**
     * Picking an insulin type must set the kinetics it is about and nothing else. These keys
     * belong to the user: the learning pace lives on the Advanced tab, and the two prudence
     * sliders live on the Simple tab. A preset that reset them silently took away tuning the
     * user had chosen on purpose, and the tail damping key is a hypo guard.
     */
    @Test
    fun `preset does not touch settings the user owns`() {
        val userOwned = listOf(
            DoubleKey.OApsAIMISmbTailDamping,
            DoubleKey.OApsAIMIIsfFusionMinFactor,
            DoubleKey.OApsAIMIIsfFusionMaxFactor,
            DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH,
            DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin,
        )
        for (preset in PkpdInsulinPreset.entries) {
            apply(preset)
            for (key in userOwned) {
                assertFalse(stored.containsKey(key), "$preset must not write $key")
            }
        }
    }
}
