package app.aaps.plugins.aps.openAPSAIMI

import org.junit.Assert.assertEquals
import org.junit.Test

class NightGrowthResistanceLearnerTest {

    @Test
    fun `test derive`() {
        val learner = NightGrowthResistanceLearner()
        val input = NightGrowthResistanceLearner.Input(
            ageYears = 10,
            autosensRatio = 1.2,
            diaMinutes = 300,
            isfMgdl = 50.0,
            targetBg = 100.0,
            basalRate = 1.0,
            stabilityMinutes = 60.0,
            combinedDelta = 5.0,
            bgNoise = 0.0
        )
        
        val output = learner.derive(input)
        
        // autosensExcess = 0.2
        // ageBonus = -0.4 (<= 12)
        // stabilityBonus = min(1.2, 2.0) = 1.2
        // noisePenalty = 0
        // minRiseSlope = 4.5 - 0.4 - 1.2 + 0 + 0.2*3.2 = 2.9 + 0.64 = 3.54
        
        assertEquals(3.54, output.minRiseSlope, 0.1)
        
        // diaInfluence = 300/60 * 6 = 30
        // persistenceBonus = 5.0
        // minDuration = 30 + 0.2*25 + 5 = 40
        assertEquals(40, output.minDurationMinutes)
    }
}
