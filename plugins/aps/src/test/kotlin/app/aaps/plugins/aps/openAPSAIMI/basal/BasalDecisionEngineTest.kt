package app.aaps.plugins.aps.openAPSAIMI.basal

import android.content.Context
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BasalDecisionEngineTest {

    private val context = mockk<Context>(relaxed = true)
    private val aimiAdaptiveBasal = mockk<AIMIAdaptiveBasal>()
    private val basalPlanner = mockk<BasalPlanner>()
    private val engine = BasalDecisionEngine(context, aimiAdaptiveBasal, basalPlanner)

    @Test
    fun `decide - should delegate to AIMIAdaptiveBasal when GS is available`() {
        // Arrange
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        val gs = mockk<GlucoseStatusAIMI>()
        every { gs.glucose } returns 150.0
        every { gs.delta } returns 2.0
        every { gs.shortAvgDelta } returns 1.5
        every { gs.longAvgDelta } returns 1.0
        every { gs.bgAcceleration } returns 0.2
        every { gs.corrSqu } returns 0.8
        every { gs.parabolaMinutes } returns 20.0

        val input = BasalDecisionEngine.Input(
            bg = 150.0,
            profileCurrentBasal = 1.0,
            basalEstimate = 1.0,
            tdd7P = 50.0,
            tdd7Days = 50.0,
            variableSensitivity = 45.0,
            profileSens = 45.0,
            predictedBg = 160.0,
            targetBg = 100.0,
            minBg = 70.0,
            lgsThreshold = 75.0,
            eventualBg = 170.0,
            iob = 1.0,
            maxIob = 5.0,
            allowMealHighIob = false,
            safetyDecision = mockk<SafetyDecision>(relaxed = true),
            mealData = mockk<MealData>(relaxed = true),
            delta = 2.0,
            shortAvgDelta = 1.5,
            longAvgDelta = 1.0,
            combinedDelta = 2.5,
            bgAcceleration = 0.2,
            slopeFromMaxDeviation = 0.1,
            slopeFromMinDeviation = 0.1,
            forcedBasal = 1.0,
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
            timenow = 12,
            sixAmHour = 6,
            recentSteps5Minutes = 0,
            nightMode = false,
            modesCondition = true,
            autodrive = true,
            currentTemp = mockk<CurrentTemp>(relaxed = true),
            glucoseStatus = gs,
            featuresCombinedDelta = 2.5,
            smbToGive = 0.0,
            zeroSinceMin = 0,
            minutesSinceLastChange = 0,
            pumpCaps = mockk<PumpCaps>(relaxed = true)
        )

        every { basalPlanner.plan(any()) } returns null
        every { aimiAdaptiveBasal.suggest(any()) } returns AIMIAdaptiveBasal.Decision(1.2, 30, "AIMI+ active")

        val helpers = BasalDecisionEngine.Helpers(
            calculateRate = { _, _, mult, _ -> 1.0 * mult },
            calculateBasalRate = { _, _, mult -> 1.0 * mult },
            detectMealOnset = { _, _, _, _, _ -> false },
            round = { v, _ -> v }
        )

        // Act
        val decision = engine.decide(input, rt, helpers)

        // Assert
        assertNotNull(decision)
        assertEquals(1.2, decision.rate, 0.01)
        assertEquals(30, decision.duration)
    }
}
