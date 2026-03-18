package app.aaps.plugins.aps.openAPSAIMI.basal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DynamicBasalControllerT3cTest {

    @Test
    fun `test computeT3c below threshold`() {
        val rate = DynamicBasalController.computeT3c(
            bg = 120.0,
            targetBg = 100.0,
            delta = 0.0f,
            shortAvgDelta = 0.0,
            longAvgDelta = 0.0,
            accel = 0.0,
            iob = 0.0,
            maxIob = 3.0,
            profileBasal = 1.0,
            isf = 50.0,
            duraISFminutes = 0.0,
            duraISFaverage = 100.0,
            eventualBg = 120.0,
            activationThreshold = 130.0,
            aggressiveness = 1.0
        )
        // Should be targetBg/profileBasal related if projectedBg >= targetBg, but since projectedBg is 120 and targetBg is 100,
        // projectedError = (120 - 130).coerceAtLeast(0.0) = 0.0
        // baseToDeliver = if (120 < 100) ... else profileBasal = 1.0
        // correctionRate = 0.0
        assertEquals(1.0, rate, 0.01)
    }

    @Test
    fun `test computeT3c above threshold`() {
        val rate = DynamicBasalController.computeT3c(
            bg = 150.0,
            targetBg = 100.0,
            delta = 2.0f,
            shortAvgDelta = 2.0,
            longAvgDelta = 2.0,
            accel = 0.0,
            iob = 0.0,
            maxIob = 3.0,
            profileBasal = 1.0,
            isf = 50.0,
            duraISFminutes = 30.0,
            duraISFaverage = 145.0,
            eventualBg = 162.0,
            activationThreshold = 130.0,
            aggressiveness = 1.0
        )
        // projectedBg = 150 + (2.0 * (30/5)) = 150 + 12 = 162
        // projectedError = 162 - 130 = 32
        // requiredU = 32 / 50 = 0.64
        // deliveryHorizonHours = 0.33 (default)
        // correctionRate = 0.64 / 0.33 = 1.939
        // resistanceFactor = 1.0 (bg 150 < 130+30=160)
        // brakeFactor = 1.0 (velocity 2.0 > 0)
        // rate = (1.0 + 1.939) = 2.939
        assertTrue(rate > 2.0)
    }

    @Test
    fun `test computeT3c aggressiveness scaling`() {
        val rateStandard = DynamicBasalController.computeT3c(
            bg = 150.0,
            targetBg = 100.0,
            delta = 2.0f,
            shortAvgDelta = 2.0,
            longAvgDelta = 2.0,
            accel = 0.0,
            iob = 0.0,
            maxIob = 3.0,
            profileBasal = 1.0,
            isf = 50.0,
            duraISFminutes = 0.0,
            duraISFaverage = 100.0,
            eventualBg = 162.0,
            activationThreshold = 130.0,
            aggressiveness = 1.0
        )
        
        val rateAggressive = DynamicBasalController.computeT3c(
            bg = 150.0,
            targetBg = 100.0,
            delta = 2.0f,
            shortAvgDelta = 2.0,
            longAvgDelta = 2.0,
            accel = 0.0,
            iob = 0.0,
            maxIob = 3.0,
            profileBasal = 1.0,
            isf = 50.0,
            duraISFminutes = 0.0,
            duraISFaverage = 100.0,
            eventualBg = 162.0,
            activationThreshold = 130.0,
            aggressiveness = 2.0
        )
        
        assertTrue(rateAggressive > rateStandard)
    }

    @Test
    fun `test computeT3c braking`() {
        val rate = DynamicBasalController.computeT3c(
            bg = 150.0,
            targetBg = 100.0,
            delta = -4.0f,  // Fast drop
            shortAvgDelta = -4.0,
            longAvgDelta = -4.0,
            accel = 0.0,
            iob = 0.0,
            maxIob = 3.0,
            profileBasal = 1.0,
            isf = 50.0,
            duraISFminutes = 0.0,
            duraISFaverage = 100.0,
            eventualBg = 126.0,
            activationThreshold = 130.0,
            aggressiveness = 1.0
        )
        // projectedBg = 150 + (-4.0 * 6) = 150 - 24 = 126
        // brakeFactor = (1.0 + (-4.0)/2.0) = 1.0 - 2.0 = -1.0 -> coerced to 0.0
        assertEquals(0.0, rate, 0.01)
    }
}
