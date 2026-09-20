package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CausalKineticsModulatorTest {

    @Test
    fun postHypoExtendsTailAndBlocksLearnWhenGateFails() {
        val posterior = CausalStatePosterior(
            postHypoRecoveryProb = 0.9,
            dominant = CausalStateId.POST_HYPO_RECOVERY,
            dominantConfidence = 0.8,
            learningQuality = 0.3,
        )
        val mod = CausalKineticsModulator.modulate(posterior)
        assertEquals(CausalStateId.POST_HYPO_RECOVERY, mod.dominant)
        assertFalse(mod.learningAllowed)
        assertTrue(mod.tailScale > 1.0)
    }

    @Test
    fun neutralPosteriorAllowsLearning() {
        val mod = CausalKineticsModulator.modulate(null)
        assertEquals(CausalStateId.UNKNOWN, mod.dominant)
        assertTrue(mod.learningAllowed)
    }

    /**
     * Slow states must now lengthen DIA by a real amount. Before this change the biggest shift
     * was 0.2 h at full confidence, far too small to matter next to the learned DIA.
     */
    @Test
    fun slowStatesLengthenDiaByAlmostOneHour() {
        val stress = CausalKineticsModulator.modulate(
            CausalStatePosterior(
                stressResistanceProb = 0.9,
                dominant = CausalStateId.STRESS_RESISTANCE,
                dominantConfidence = 1.0,
                learningQuality = 0.9,
            )
        )
        assertEquals(1.0, stress.diaShiftHours, 1e-9)

        val afterburn = CausalKineticsModulator.modulate(
            CausalStatePosterior(
                exerciseAfterburnProb = 0.9,
                dominant = CausalStateId.EXERCISE_AFTERBURN,
                dominantConfidence = 1.0,
                learningQuality = 0.9,
            )
        )
        assertTrue(afterburn.diaShiftHours >= 0.8)

        val postHypo = CausalKineticsModulator.modulate(
            CausalStatePosterior(
                postHypoRecoveryProb = 0.9,
                dominant = CausalStateId.POST_HYPO_RECOVERY,
                dominantConfidence = 1.0,
                learningQuality = 0.9,
            )
        )
        assertTrue(postHypo.diaShiftHours >= 0.8)
    }

    /** A meal keeps the opposite sign: the action must be shorter, not longer. */
    @Test
    fun mealShortensDia() {
        val mod = CausalKineticsModulator.modulate(
            CausalStatePosterior(
                fastMealProb = 0.9,
                dominant = CausalStateId.FAST_MEAL,
                dominantConfidence = 1.0,
                learningQuality = 0.9,
            )
        )
        assertEquals(-0.8, mod.diaShiftHours, 1e-9)
    }

    /** The shift is still scaled by confidence, so a weak belief only moves DIA a little. */
    @Test
    fun lowConfidenceKeepsTheShiftSmall() {
        val mod = CausalKineticsModulator.modulate(
            CausalStatePosterior(
                stressResistanceProb = 0.3,
                dominant = CausalStateId.STRESS_RESISTANCE,
                dominantConfidence = 0.1,
                learningQuality = 0.9,
            )
        )
        assertEquals(0.1, mod.diaShiftHours, 1e-9)
    }
}
