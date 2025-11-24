package app.aaps.plugins.aps.openAPSAIMI

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AIMIAdaptiveBasalTest {

    @Test
    fun `test pureSuggest profile basal zero`() {
        val input = createInput(profileBasal = 0.0)
        val decision = AIMIAdaptiveBasal.pureSuggest(input)
        assertNull(decision.rateUph)
        assertEquals("profile basal = 0", decision.reason)
    }

    @Test
    fun `test pureSuggest micro resume`() {
        // lastTempIsZero = true, zeroSinceMin = 20 (>= 10 default)
        val input = createInput(
            profileBasal = 1.0,
            lastTempIsZero = true,
            zeroSinceMin = 20,
            minutesSinceLastChange = 40
        )
        val decision = AIMIAdaptiveBasal.pureSuggest(input)
        assertNotNull(decision.rateUph)
        // Rate should be max(0.2, 1.0 * 0.25 + small_adjust) -> approx 0.3
        // Duration min(30, max(10, 20)) -> 20
        assertEquals(20, decision.durationMin)
        assert(decision.reason.startsWith("micro-resume"))
    }

    @Test
    fun `test pureSuggest plateau kicker`() {
        // High BG, flat delta, stable
        val input = createInput(
            bg = 200.0,
            delta = 0.0,
            shortAvgDelta = 0.0,
            longAvgDelta = 0.0,
            r2 = 0.9,
            profileBasal = 1.0,
            minutesSinceLastChange = 10
        )
        val decision = AIMIAdaptiveBasal.pureSuggest(input)
        assertNotNull(decision.rateUph)
        assert(decision.rateUph!! > 1.0) // Should increase basal
        assert(decision.reason.startsWith("plateau kicker"))
    }

    @Test
    fun `test pureSuggest anti stall`() {
        // High BG, glued (high R2), delta slightly negative but not dropping fast enough
        val input = createInput(
            bg = 200.0,
            delta = -0.5,
            shortAvgDelta = -0.5,
            longAvgDelta = -0.5,
            r2 = 0.9,
            profileBasal = 1.0
        )
        // deltaPosRelease default is around 1.0. -0.5 is < 1.0.
        // But we need to ensure it hits the "glued" logic and not plateau kicker (which requires delta ~ 0)
        // Plateau band is around 2.5. So -0.5 is within plateau band.
        // If it hits plateau kicker first, it returns.
        // Plateau kicker requires: abs(delta) <= plateauBand && abs(shortAvgDelta) <= plateauBand.
        // So -0.5 fits plateau kicker conditions if BG > highBg.
        // Wait, plateau kicker logic:
        // val plateau = abs(input.delta) <= settings.plateauBand && abs(input.shortAvgDelta) <= settings.plateauBand
        // val highAndFlat = input.bg > settings.highBg && plateau
        // If highAndFlat is true, it does kicker.
        // Anti-stall is after.
        // To hit anti-stall, we need highAndFlat to be false?
        // No, highAndFlat checks shortAvgDelta. Glued checks longAvgDelta.
        // If I make shortAvgDelta large (e.g. -5) but longAvgDelta small (-0.5), plateau is false.
        
        val inputAntiStall = input.copy(shortAvgDelta = -5.0, longAvgDelta = 0.0, delta = 0.0)
        // Wait, if delta=0, plateau is false only if shortAvgDelta is big.
        
        val decision = AIMIAdaptiveBasal.pureSuggest(inputAntiStall)
        // glued = r2 >= 0.7 && abs(0) <= 2.5 && abs(0) <= 2.5 -> True
        // glued && bg > high && delta < 1.0 -> True
        
        assertNotNull(decision.rateUph)
        assert(decision.reason.startsWith("anti-stall bias"))
    }

    @Test
    fun `test pureSuggest no action`() {
        // Normal BG
        val input = createInput(bg = 100.0)
        val decision = AIMIAdaptiveBasal.pureSuggest(input)
        assertNull(decision.rateUph)
        assertEquals("no AIMI+ action", decision.reason)
    }

    private fun createInput(
        bg: Double = 100.0,
        delta: Double = 0.0,
        shortAvgDelta: Double = 0.0,
        longAvgDelta: Double = 0.0,
        accel: Double = 0.0,
        r2: Double = 0.0,
        parabolaMin: Double = 0.0,
        combinedDelta: Double = 0.0,
        profileBasal: Double = 1.0,
        lastTempIsZero: Boolean = false,
        zeroSinceMin: Int = 0,
        minutesSinceLastChange: Int = 0
    ) = AIMIAdaptiveBasal.Input(
        bg, delta, shortAvgDelta, longAvgDelta, accel, r2, parabolaMin, combinedDelta, profileBasal, lastTempIsZero, zeroSinceMin, minutesSinceLastChange
    )
}
