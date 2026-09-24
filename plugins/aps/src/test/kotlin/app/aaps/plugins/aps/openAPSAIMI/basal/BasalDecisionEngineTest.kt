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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    // ---- Early-meal forced TBR guards (report ②) -------------------------------------------------
    // glucoseStatus = null so the AIMIAdaptiveBasal delegation (guarded by glucoseStatus?.let) is
    // skipped and chosenRate enters the forced-TBR branch as null; detectMealOnset is forced true.

    private fun forcedTbrInput(
        iob: Double = 1.0,
        maxIob: Double = 10.0,
        eventualBg: Double = 170.0,
        lgsThreshold: Double = 75.0,
        delta: Double = 3.0,
        nightMode: Boolean = false,
        forcedBasal: Double = 5.0,
        variableSensitivity: Double = 50.0,
        profileSens: Double = 50.0,
        preFloorCommandedSens: Double? = null,
        lunchTime: Boolean = false,
        lunchRuntimeMin: Int = 0,
    ) = BasalDecisionEngine.Input(
        bg = 145.0,
        profileCurrentBasal = 0.8,
        basalEstimate = 0.8,
        tdd7P = 40.0,
        tdd7Days = 40.0,
        variableSensitivity = variableSensitivity,
        profileSens = profileSens,
        preFloorCommandedSens = preFloorCommandedSens,
        predictedBg = 160.0,
        targetBg = 100.0,
        minBg = 70.0,
        lgsThreshold = lgsThreshold,
        eventualBg = eventualBg,
        iob = iob,
        maxIob = maxIob,
        allowMealHighIob = false,
        safetyDecision = mockk<SafetyDecision>(relaxed = true),
        mealData = mockk<MealData>(relaxed = true),
        delta = delta,
        shortAvgDelta = delta,
        longAvgDelta = delta,
        combinedDelta = delta,
        bgAcceleration = 0.5,
        slopeFromMaxDeviation = 0.1,
        slopeFromMinDeviation = 0.1,
        forcedBasal = forcedBasal,
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
        lunchTime = lunchTime,
        lunchRuntimeMin = lunchRuntimeMin,
        dinnerTime = false,
        dinnerRuntimeMin = 0,
        highCarbTime = false,
        highCarbRuntimeMin = 0,
        timenow = 12,
        sixAmHour = 6,
        recentSteps5Minutes = 0,
        nightMode = nightMode,
        modesCondition = true,
        autodrive = true,
        currentTemp = mockk<CurrentTemp>(relaxed = true),
        glucoseStatus = null,
        featuresCombinedDelta = delta,
        smbToGive = 0.0,
        zeroSinceMin = 0,
        minutesSinceLastChange = 0,
        pumpCaps = mockk<PumpCaps>(relaxed = true)
    )

    // ---- Meal-window boost: the numerator must be the PRE-FLOOR commanded sensitivity ------------
    // The stress ISF floor raises the commanded sensitivity to make doses smaller. This boost divides
    // by the working sensitivity, so feeding it the floored value made the basal larger - the exact
    // opposite of what the floor is for. Numbers come from tick 1790021540129 (2026-09-21 22:12):
    // profile 60, pre-floor 36.6, working 21.6, and the floor had raised the commanded value to 60.
    // `calculateBasalRate` is stubbed to return the multiplier, and the multiplier is delta x boost,
    // so with delta 3.0 the rate IS 3 x boost.

    private fun noMealOnsetHelpers() = BasalDecisionEngine.Helpers(
        calculateRate = { _, _, mult, _ -> 1.0 * mult },
        calculateBasalRate = { _, _, mult -> 1.0 * mult },
        detectMealOnset = { _, _, _, _, _ -> false },
        round = { v, _ -> v }
    )

    private fun decideMealWindow(preFloor: Double?, working: Double, commanded: Double): Pair<BasalDecisionEngine.Decision, RT> {
        every { basalPlanner.plan(any()) } returns null
        val input = forcedTbrInput(
            variableSensitivity = working,
            profileSens = commanded,
            preFloorCommandedSens = preFloor,
            lunchTime = true,
            lunchRuntimeMin = 45,
        )
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        return engine.decide(input, rt, noMealOnsetHelpers()) to rt
    }

    @Test
    fun `meal-window boost divides the pre-floor sensitivity, not the floored one`() {
        val (decision, _) = decideMealWindow(preFloor = 36.6, working = 21.6, commanded = 60.0)
        // 36.6 / 21.6 = 1.69 -> rate 5.08. The floored numerator would have given 60 / 21.6 = 2.78 -> 8.34.
        assertEquals(5.08, decision.rate, 0.05)
        assertTrue(decision.rate < 8.0) { "the floor must not inflate the meal-window basal" }
    }

    @Test
    fun `meal-window boost is unchanged when the floor raises the commanded sensitivity`() {
        val floorInactive = decideMealWindow(preFloor = 36.6, working = 21.6, commanded = 36.6).first
        val floorActive = decideMealWindow(preFloor = 36.6, working = 21.6, commanded = 60.0).first
        assertEquals(floorInactive.rate, floorActive.rate, 0.001)
    }

    @Test
    fun `meal-window boost stands down when the pre-floor sensitivity is missing`() {
        val (decision, rt) = decideMealWindow(preFloor = null, working = 21.6, commanded = 60.0)
        // No boost at all: rate is delta x 1.0.
        assertEquals(3.0, decision.rate, 0.001)
        assertFalse(rt.reason.toString().contains("boost x")) { rt.reason.toString() }
    }

    @Test
    fun `meal-window boost stands down on a non-finite pre-floor sensitivity`() {
        val (decision, _) = decideMealWindow(preFloor = Double.NaN, working = 21.6, commanded = 60.0)
        assertEquals(3.0, decision.rate, 0.001)
    }

    private fun mealOnsetHelpers() = BasalDecisionEngine.Helpers(
        calculateRate = { _, _, mult, _ -> 1.0 * mult },
        calculateBasalRate = { _, _, mult -> 1.0 * mult },
        detectMealOnset = { _, _, _, _, _ -> true },
        round = { v, _ -> v }
    )

    private fun decideForcedTbr(input: BasalDecisionEngine.Input): Pair<BasalDecisionEngine.Decision, RT> {
        every { basalPlanner.plan(any()) } returns null
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        val decision = engine.decide(input, rt, mealOnsetHelpers())
        return decision to rt
    }

    @Test
    fun `forced early-meal TBR fires when all guards are clear`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput())
        assertTrue(decision.overrideSafety) { "override should be granted when guards clear" }
        assertTrue(rt.reason.toString().contains("AD_EARLY_TBR_TRIGGER")) { rt.reason.toString() }
    }

    @Test
    fun `forced TBR suppressed when IOB above absolute cap`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput(iob = 5.0))
        assertFalse(decision.overrideSafety) { "forced override must not fire while stacking" }
        val reason = rt.reason.toString()
        assertTrue(reason.contains("AD_EARLY_TBR_BLOCKED")) { reason }
        assertTrue(reason.contains("iob=")) { reason }
    }

    @Test
    fun `forced TBR suppressed at night without a steep rise`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput(nightMode = true, delta = 3.0))
        assertFalse(decision.overrideSafety)
        val reason = rt.reason.toString()
        assertTrue(reason.contains("AD_EARLY_TBR_BLOCKED")) { reason }
        assertTrue(reason.contains("nightDelta=")) { reason }
    }

    @Test
    fun `forced TBR allowed at night on a genuinely steep rise`() {
        val (decision, rt) = decideForcedTbr(forcedTbrInput(nightMode = true, delta = 9.0))
        assertTrue(decision.overrideSafety)
        assertTrue(rt.reason.toString().contains("AD_EARLY_TBR_TRIGGER")) { rt.reason.toString() }
    }

    @Test
    fun `forced TBR suppressed when prediction heads toward the LGS threshold`() {
        // eventualBg 85 < lgsThreshold 75 + 15 margin → dropping toward a low, do not force a high TBR.
        val (decision, rt) = decideForcedTbr(forcedTbrInput(eventualBg = 85.0))
        assertFalse(decision.overrideSafety)
        val reason = rt.reason.toString()
        assertTrue(reason.contains("AD_EARLY_TBR_BLOCKED")) { reason }
        assertTrue(reason.contains("evBG=")) { reason }
    }
}
