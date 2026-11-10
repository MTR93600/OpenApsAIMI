package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.RiseConfirmation
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealRiseRefractoryPolicyTest {
    @Test
    fun `confirmed fast first wave raises minor refractory factor`() {
        val result = evaluate()
        assertTrue(result.active)
        assertEquals(0.775, result.factor, 1e-9)
    }

    @Test
    fun `manual bolus sized active insulin keeps normal refractory`() {
        val result = evaluate(iob = 2.0)
        assertFalse(result.active)
        assertEquals(0.35, result.factor, 1e-9)
    }

    @Test
    fun `weak rise keeps normal refractory`() {
        val result = evaluate(rise = RiseConfirmation.Level.WEAK)
        assertFalse(result.active)
        assertEquals(0.35, result.factor, 1e-9)
    }

    @Test
    fun `unsafe prediction keeps normal refractory`() {
        val result = evaluate(minPred = 98.0)
        assertFalse(result.active)
        assertEquals(0.35, result.factor, 1e-9)
    }

    @Test
    fun `plateau phase never relaxes refractory`() {
        val result = evaluate(phase = MealAbsorptionPhase.INTER_WAVE)
        assertFalse(result.active)
        assertEquals(0.35, result.factor, 1e-9)
    }

    private fun evaluate(
        iob: Double = 0.4,
        rise: RiseConfirmation.Level = RiseConfirmation.Level.CONFIRMED,
        minPred: Double? = 132.0,
        phase: MealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
    ) = MealRiseRefractoryPolicy.factor(
        progressiveFactor = 0.35,
        mealPriority = true,
        phase = phase,
        riseLevel = rise,
        bgMgdl = 160.0,
        targetMgdl = 90.0,
        deltaMgdl5m = 16.0,
        shortAvgDeltaMgdl5m = 12.0,
        minPredMgdl = minPred,
        iobU = iob,
        maxIobU = 10.0,
    )
}
