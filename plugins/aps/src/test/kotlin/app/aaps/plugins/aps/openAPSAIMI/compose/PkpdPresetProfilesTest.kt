package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.interfaces.Preferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The anchor is the soft target the PK/PD learner is pulled back to. When a preset wrote an
 * anchor below its own DIA floor, the learned DIA was pulled onto that floor and stayed there.
 * These tests keep every preset self-consistent.
 */
class PkpdPresetProfilesTest {

    private val stored = mutableMapOf<DoubleKey, Double>()

    private fun preferences(): Preferences {
        val preferences: Preferences = mockk(relaxed = true)
        every { preferences.put(any<DoubleKey>(), any()) } answers {
            stored[firstArg()] = secondArg()
        }
        every { preferences.get(any<DoubleKey>()) } answers {
            stored[firstArg()] ?: firstArg<DoubleKey>().defaultValue
        }
        return preferences
    }

    private fun apply(preset: PkpdInsulinPreset) {
        stored.clear()
        applyPkpdInsulinPreset(preferences(), preset)
    }

    private fun value(key: DoubleKey): Double = stored.getValue(key)

    /**
     * A running PK/PD engine keeps the learned state in memory. Writing the state keys is not
     * enough: the generation counter must be bumped so the engine re-seeds from prefs.
     */
    @Test
    fun `resetPkpdLearnedStateToInitial bumps the generation`() {
        stored.clear()
        val preferences = preferences()
        resetPkpdLearnedStateToInitial(preferences)
        verify(exactly = 1) { preferences.inc(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) }
    }

    @Test
    fun `reclampPkpdLearnedStateToBounds bumps the generation`() {
        stored.clear()
        val preferences = preferences()
        reclampPkpdLearnedStateToBounds(preferences)
        verify(exactly = 1) { preferences.inc(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) }
    }

    @Test
    fun `applyPkpdInsulinPreset bumps the generation once`() {
        for (preset in listOf(PkpdInsulinPreset.ULTRA_FAST, PkpdInsulinPreset.RAPID, PkpdInsulinPreset.STANDARD)) {
            stored.clear()
            val preferences = preferences()
            applyPkpdInsulinPreset(preferences, preset)
            verify(exactly = 1) { preferences.inc(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) }
        }
        // CUSTOM writes nothing, so there is nothing for the engine to adopt.
        stored.clear()
        val customPreferences = preferences()
        applyPkpdInsulinPreset(customPreferences, PkpdInsulinPreset.CUSTOM)
        verify(exactly = 0) { customPreferences.inc(LongNonKey.OApsAIMIPkpdLearnedStateGeneration) }
    }

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
