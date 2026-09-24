package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Meal-window boost: the numerator must be the PRE-FLOOR commanded sensitivity.
 *
 * The stress ISF floor raises the commanded sensitivity to make doses smaller. This boost divides
 * by the working sensitivity, so feeding it the floored value made the basal larger — the exact
 * opposite of what the floor is for. Numbers come from tick 1790021540129 (2026-09-21 22:12):
 * profile 60, pre-floor 36.6, working 21.6, and the floor had raised the commanded value to 60.
 * `calculateBasalRate` returns the multiplier, and the multiplier is delta x boost, so with delta
 * 3.0 the rate IS 3 x boost.
 *
 * The boost is not behind `BooleanKey.OApsAIMIStressIsfFloor`. A missing pre-floor value gives
 * ratio 1.0.
 *
 * Source: `origin/dev_OAPSAIMI` @ `6a6561caab` `BasalDecisionEngineTest` meal-window cases.
 * Study: commonTest. [BasalPlanner.plan] returns null on this input (BG 145, delta 3, empty basal
 * history), so the lunch window is reached without a mock. Backtick names omit commas.
 */
class MealWindowBasalBoostTest {

    private val engine = BasalDecisionEngine(
        rh = StubText,
        aimiAdaptiveBasal = AIMIAdaptiveBasal(NoOpLogger, StubFormatter),
        basalPlanner = BasalPlanner(AIMIAdaptiveBasal(NoOpLogger, StubFormatter), NoOpLogger),
    )

    @BeforeTest
    fun emptyBasalHistory() {
        BasalHistoryUtils.historyProvider = BasalHistoryUtils.EmptyProvider
    }

    private fun noMealOnsetHelpers() = BasalDecisionEngine.Helpers(
        calculateRate = { _, _, mult, _ -> 1.0 * mult },
        calculateBasalRate = { _, _, mult -> 1.0 * mult },
        detectMealOnset = { _, _, _, _, _ -> false },
        round = { v, _ -> v },
    )

    private fun decideMealWindow(preFloor: Double?, working: Double, commanded: Double): Pair<BasalDecisionEngine.Decision, RT> {
        val input = lunchInput(
            variableSensitivity = working,
            profileSens = commanded,
            preFloorCommandedSens = preFloor,
        )
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        return engine.decide(input, rt, noMealOnsetHelpers()) to rt
    }

    @Test
    fun `meal-window boost divides the pre-floor sensitivity not the floored one`() {
        val (decision, _) = decideMealWindow(preFloor = 36.6, working = 21.6, commanded = 60.0)
        // 36.6 / 21.6 = 1.69 -> rate 5.08. The floored numerator would have given 60 / 21.6 = 2.78 -> 8.34.
        assertEquals(5.08, decision.rate, absoluteTolerance = 0.05)
        assertTrue(decision.rate < 8.0, "the floor must not inflate the meal-window basal")
    }

    @Test
    fun `meal-window boost is unchanged when the floor raises the commanded sensitivity`() {
        val floorInactive = decideMealWindow(preFloor = 36.6, working = 21.6, commanded = 36.6).first
        val floorActive = decideMealWindow(preFloor = 36.6, working = 21.6, commanded = 60.0).first
        assertEquals(floorInactive.rate, floorActive.rate, absoluteTolerance = 0.001)
    }

    @Test
    fun `meal-window boost stands down when the pre-floor sensitivity is missing`() {
        val (decision, rt) = decideMealWindow(preFloor = null, working = 21.6, commanded = 60.0)
        // No boost at all: rate is delta x 1.0.
        assertEquals(3.0, decision.rate, absoluteTolerance = 0.001)
        assertFalse(rt.reason.toString().contains("boost x"), rt.reason.toString())
    }

    @Test
    fun `meal-window boost stands down on a non-finite pre-floor sensitivity`() {
        val (decision, _) = decideMealWindow(preFloor = Double.NaN, working = 21.6, commanded = 60.0)
        assertEquals(3.0, decision.rate, absoluteTolerance = 0.001)
    }

    private fun lunchInput(
        variableSensitivity: Double,
        profileSens: Double,
        preFloorCommandedSens: Double?,
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
        lgsThreshold = 75.0,
        eventualBg = 170.0,
        iob = 1.0,
        maxIob = 10.0,
        allowMealHighIob = false,
        safetyDecision = SafetyDecision(
            stopBasal = false,
            bolusFactor = 1.0,
            reason = "",
            basalLS = false,
        ),
        mealData = app.aaps.core.interfaces.aps.MealData(),
        delta = 3.0,
        shortAvgDelta = 3.0,
        longAvgDelta = 3.0,
        combinedDelta = 3.0,
        bgAcceleration = 0.5,
        slopeFromMaxDeviation = 0.1,
        slopeFromMinDeviation = 0.1,
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
        lunchTime = true,
        lunchRuntimeMin = 45,
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
        currentTemp = CurrentTemp(duration = 0, rate = 0.8, minutesrunning = 0),
        glucoseStatus = null,
        featuresCombinedDelta = 3.0,
        smbToGive = 0.0,
        zeroSinceMin = 0,
        minutesSinceLastChange = 0,
        pumpCaps = PumpCaps(
            basalStep = 0.05,
            bolusStep = 0.05,
            minDurationMin = 30,
            maxBasal = 3.0,
            maxSmb = 1.0,
        ),
    )

    private object StubText : TextResolver {
        override fun gs(ref: TextRef): String = "text"
        override fun gs(ref: TextRef, vararg args: Any?): String = "text"
        override fun gsNotLocalised(ref: TextRef): String = "text"
        override fun shortTextMode(): Boolean = false
    }

    private object StubFormatter : DecimalFormatter {
        override fun to0Decimal(value: Double): String = value.toString()
        override fun to0Decimal(value: Double, unit: String): String = value.toString()
        override fun to1Decimal(value: Double): String = value.toString()
        override fun to1Decimal(value: Double, unit: String): String = value.toString()
        override fun to2Decimal(value: Double): String = value.toString()
        override fun to2Decimal(value: Double, unit: String): String = value.toString()
        override fun to3Decimal(value: Double): String = value.toString()
        override fun to3Decimal(value: Double, unit: String): String = value.toString()
        override fun toPumpSupportedBolus(value: Double, bolusStep: Double): String = value.toString()
        override fun toPumpSupportedBolusWithUnits(value: Double, bolusStep: Double): String = value.toString()
        override fun toPumpSupportedBolusWithUnits(value: PumpInsulin, bolusStep: Double): String = value.cU.toString()
        override fun pumpSupportedBolusFormat(bolusStep: Double): NumberFormat = NumberFormat()
    }

    private object NoOpLogger : AAPSLogger {
        override fun debug(message: String) = Unit
        override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
        override fun debug(tag: LTag, message: String) = Unit
        override fun debug(tag: LTag, accessor: () -> String) = Unit
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun warn(tag: LTag, message: String) = Unit
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun info(tag: LTag, message: String) = Unit
        override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun error(tag: LTag, message: String) = Unit
        override fun error(tag: LTag, message: String, throwable: Throwable) = Unit
        override fun error(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun error(message: String) = Unit
        override fun error(message: String, throwable: Throwable) = Unit
        override fun error(format: String, vararg arguments: Any?) = Unit
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    }
}
