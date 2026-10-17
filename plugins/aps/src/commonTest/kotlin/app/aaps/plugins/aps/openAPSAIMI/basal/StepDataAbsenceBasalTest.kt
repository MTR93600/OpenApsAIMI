package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Step data and the basal decision. `recentSteps5Minutes == null` means the platform has no step
 * data, and then neither step branch may fire:
 * - steps under 100 raise basal to 1.5 times the profile (calm meal window),
 * - steps over 100 lower basal to 0.5 times the profile (morning activity).
 * With `null` the cascade goes on to the strong-rise trend block. The numbers are made so that the
 * three outcomes differ: profile basal 0.8, so 1.5 times is 1.2, 0.5 times is 0.4, and the trend
 * block gives multiplier 1.3 (the test helper returns the multiplier as the rate).
 *
 * Runs on the JVM and on the iOS simulator. Backtick names omit commas.
 */
class StepDataAbsenceBasalTest {

    private val fixtures = MealWindowBasalBoostTest()

    @BeforeTest
    fun emptyBasalHistory() {
        BasalHistoryUtils.historyProvider = BasalHistoryUtils.EmptyProvider
    }

    private val raise = 1.2
    private val lower = 0.4
    private val trend = 1.3

    /** Midday, low IOB, a slow rise of 5 per 5 minutes (strongest rise 5 gives trend multiplier 1.3). */
    private fun rateWith(steps: Int?, hour: Int = 12): Double {
        val input = fixtures.lunchInput(
            variableSensitivity = 21.6,
            profileSens = 60.0,
            preFloorCommandedSens = null,
        ).copy(
            lunchTime = false,
            lunchRuntimeMin = 0,
            iob = 0.5,
            delta = 5.0,
            shortAvgDelta = 5.0,
            longAvgDelta = 5.0,
            combinedDelta = 5.0,
            featuresCombinedDelta = 5.0,
            slopeFromMinDeviation = 0.35,
            timenow = hour,
            recentSteps5Minutes = steps,
        )
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        return fixtures.engine.decide(input, rt, fixtures.noMealOnsetHelpers()).rate
    }

    @Test
    fun `unknown steps do not raise basal and reach the trend block`() {
        assertEquals(trend, rateWith(steps = null), absoluteTolerance = 0.001)
    }

    @Test
    fun `unknown steps do not lower basal and reach the trend block`() {
        // 15:00 is outside the calm-meal hours, so only the lowering branch could have fired.
        assertEquals(trend, rateWith(steps = null, hour = 15), absoluteTolerance = 0.001)
    }

    @Test
    fun `real low steps still raise basal to 1 point 5 times`() {
        assertEquals(raise, rateWith(steps = 0), absoluteTolerance = 0.001)
        assertEquals(raise, rateWith(steps = 99), absoluteTolerance = 0.001)
    }

    @Test
    fun `exactly 100 steps takes neither step branch`() {
        assertEquals(trend, rateWith(steps = 100), absoluteTolerance = 0.001)
    }

    @Test
    fun `real steps over 100 still lower basal to half`() {
        assertEquals(lower, rateWith(steps = 101), absoluteTolerance = 0.001)
        assertEquals(lower, rateWith(steps = 350), absoluteTolerance = 0.001)
    }
}
