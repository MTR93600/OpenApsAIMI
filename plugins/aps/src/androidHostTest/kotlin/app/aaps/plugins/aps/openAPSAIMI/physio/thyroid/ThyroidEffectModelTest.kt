package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ThyroidEffectModelTest {

    @Test
    fun `test euthyroid state applies no multipliers`() {
        val model = ThyroidEffectModel()
        val effects = model.calculateEffects(ThyroidStatus.EUTHYROID, 1.0)

        assertEquals(1.0, effects.diaMultiplier, 0.01)
        assertEquals(1.0, effects.egpMultiplier, 0.01)
        assertEquals(1.0, effects.carbRateMultiplier, 0.01)
        assertEquals(1.0, effects.isfMultiplier, 0.01)
    }

    @Test
    fun `test severe hyperthyroid applies max multipliers`() {
        val model = ThyroidEffectModel()
        val effects = model.calculateEffects(ThyroidStatus.HYPER_SEVERE, 1.0)

        // Verifying the multipliers against expected values described in EffectModel
        assertEquals(0.80, effects.diaMultiplier, 0.01) // 20% reduction in DIA
        assertEquals(1.35, effects.egpMultiplier, 0.01) // 35% increase in EGP
        assertEquals(1.25, effects.carbRateMultiplier, 0.01) // 25% faster carb absorption
        assertEquals(0.90, effects.isfMultiplier, 0.01) // 10% more insulin resistance
    }

    @Test
    fun `test normalizing state slowly reduces multipliers`() {
        val model = ThyroidEffectModel()
        
        // Simulating the user being in NORMALIZING state for a while and seeing how effects constrain
        val effects = model.calculateEffects(ThyroidStatus.NORMALIZING, 1.0)

        // Multipliers should be somewhat close to 1.0 but still conservative
        // Depending on exact formulas in EffectModel
        assertEquals(0.98, effects.diaMultiplier, 0.05)
        assertEquals(1.03, effects.egpMultiplier, 0.05)
    }

    @Test
    fun `test unkown state outputs empty effects`() {
        val model = ThyroidEffectModel()
        val effects = model.calculateEffects(ThyroidStatus.UNKNOWN, 1.0)

        assertEquals(1.0, effects.diaMultiplier, 0.01)
        assertEquals(1.0, effects.egpMultiplier, 0.01)
    }
}
