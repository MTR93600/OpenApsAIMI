package app.aaps.plugins.aps.openAPSAIMI.basal

import android.content.Context
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Step data and the basal decision. `recentSteps5Minutes == null` means there is no step data, and
 * then neither step branch may fire:
 * - steps under 100 raise basal to 1.5 times the profile (calm meal window, 11-13 h and 18-21 h),
 * - steps over 100 lower basal to 0.5 times the profile (morning activity).
 * With `null` the cascade goes on to the strong-rise trend block. The numbers are made so that the
 * three outcomes differ: profile basal 0.8, so 1.5 times is 1.2, 0.5 times is 0.4, and the trend
 * block gives multiplier 1.3 (the test helper returns the multiplier as the rate).
 */
class StepDataAbsenceBasalTest {

    private val context = mockk<Context>(relaxed = true)
    private val aimiAdaptiveBasal = mockk<AIMIAdaptiveBasal>()
    private val basalPlanner = mockk<BasalPlanner>()
    private val engine = BasalDecisionEngine(context, aimiAdaptiveBasal, basalPlanner)

    private val raise = 1.2
    private val lower = 0.4
    private val trend = 1.3

    @BeforeEach
    fun setUp() {
        BasalHistoryUtils.historyProvider = BasalHistoryUtils.EmptyProvider
        every { basalPlanner.plan(any()) } returns null
    }

    private val helpers = BasalDecisionEngine.Helpers(
        calculateRate = { _, _, mult, _ -> 1.0 * mult },
        calculateBasalRate = { _, _, mult -> 1.0 * mult },
        detectMealOnset = { _, _, _, _, _ -> false },
        round = { v, _ -> v }
    )

    /** Midday, low IOB, a slow rise of 5 per 5 minutes (strongest rise 5 gives trend multiplier 1.3). */
    private fun rateWith(steps: Int?, hour: Int = 12): Double {
        val input = BasalDecisionEngine.Input(
            bg = 145.0,
            profileCurrentBasal = 0.8,
            basalEstimate = 0.8,
            tdd7P = 40.0,
            tdd7Days = 40.0,
            variableSensitivity = 50.0,
            profileSens = 50.0,
            preFloorCommandedSens = null,
            predictedBg = 160.0,
            targetBg = 100.0,
            minBg = 70.0,
            lgsThreshold = 75.0,
            eventualBg = 140.0,
            iob = 0.5,
            maxIob = 10.0,
            allowMealHighIob = false,
            safetyDecision = mockk<SafetyDecision>(relaxed = true),
            mealData = mockk<MealData>(relaxed = true),
            delta = 5.0,
            shortAvgDelta = 5.0,
            longAvgDelta = 5.0,
            combinedDelta = 5.0,
            bgAcceleration = 0.5,
            slopeFromMaxDeviation = 0.1,
            slopeFromMinDeviation = 0.35,
            forcedBasal = 5.0,
            forcedMealActive = false,
            isMealActive = false,
            runtimeMinValue = 0,
            snackTime = false,
            snackRuntimeMin = 0,
            fastingTime = false,
            sportTime = false,
            honeymoon = false,
            pregnancyEnable = false,
            mealTime = false,
            mealRuntimeMin = 0,
            bfastTime = false,
            bfastRuntimeMin = 0,
            lunchTime = false,
            lunchRuntimeMin = 0,
            dinnerTime = false,
            dinnerRuntimeMin = 0,
            highCarbTime = false,
            highCarbRuntimeMin = 0,
            timenow = hour,
            sixAmHour = 6,
            recentSteps5Minutes = steps,
            nightMode = false,
            modesCondition = true,
            autodrive = true,
            currentTemp = mockk<CurrentTemp>(relaxed = true),
            glucoseStatus = null,
            featuresCombinedDelta = 5.0,
            smbToGive = 0.0,
            zeroSinceMin = 0,
            minutesSinceLastChange = 0,
            pumpCaps = mockk<PumpCaps>(relaxed = true)
        )
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        return engine.decide(input, rt, helpers).rate
    }

    @Test
    fun `unknown steps do not raise basal and reach the trend block`() {
        assertEquals(trend, rateWith(steps = null), 0.001)
    }

    @Test
    fun `unknown steps do not lower basal and reach the trend block`() {
        // 15:00 is outside the calm-meal hours, so only the lowering branch could have fired.
        assertEquals(trend, rateWith(steps = null, hour = 15), 0.001)
    }

    @Test
    fun `real low steps still raise basal to 1 point 5 times`() {
        assertEquals(raise, rateWith(steps = 0), 0.001)
        assertEquals(raise, rateWith(steps = 99), 0.001)
    }

    @Test
    fun `exactly 100 steps takes neither step branch`() {
        assertEquals(trend, rateWith(steps = 100), 0.001)
    }

    @Test
    fun `real steps over 100 still lower basal to half`() {
        assertEquals(lower, rateWith(steps = 101), 0.001)
        assertEquals(lower, rateWith(steps = 350), 0.001)
    }
}
