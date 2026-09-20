package app.aaps.plugins.aps.openAPSAIMI.activity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExerciseHyperOverridePolicyTest {

    private fun input(
        bg: Double = 252.0,
        delta: Double = 4.4,
        combined: Double = 4.0,
        thyroidEgp: Double = 1.0,
    ) = ExerciseHyperOverridePolicy.Input(
        bgMgdl = bg,
        targetBgMgdl = 100.0,
        highBgPreferenceMgdl = 140.0,
        deltaMgdlPer5 = delta,
        shortAvgDeltaMgdlPer5 = 3.5,
        combinedDeltaMgdlPer5 = combined,
        thyroidEgpMultiplier = thyroidEgp,
    )

    @Test
    fun hyper_rising_during_walk_triggers_override() {
        assertTrue(ExerciseHyperOverridePolicy.isHyperRisingDuringExercise(input()))
    }

    @Test
    fun mild_walk_near_target_no_override() {
        assertFalse(
            ExerciseHyperOverridePolicy.isHyperRisingDuringExercise(
                input(bg = 118.0, delta = 1.0, combined = 1.2),
            ),
        )
    }

    @Test
    fun thyroid_bias_earlier_trigger() {
        assertTrue(
            ExerciseHyperOverridePolicy.isHyperRisingDuringExercise(
                input(bg = 165.0, delta = 2.0, combined = 2.3, thyroidEgp = 1.15),
            ),
        )
    }

    @Test
    fun basal_factor_boosted_when_override() {
        assertEquals(1.02f, ExerciseHyperOverridePolicy.resolveBasalFactor(0.6f, true, false), 0.01f)
        assertEquals(1.10f, ExerciseHyperOverridePolicy.resolveBasalFactor(0.8f, true, true), 0.01f)
    }

    @Test
    fun basal_factor_unchanged_without_override() {
        assertEquals(0.6f, ExerciseHyperOverridePolicy.resolveBasalFactor(0.6f, false, true))
    }
}
