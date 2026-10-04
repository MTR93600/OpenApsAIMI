package app.aaps.plugins.aps.openAPSAIMI.math

import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext
import app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden parity for the pure tick helpers moved out of `DetermineBasalAIMI2`.
 *
 * Rounding follows Java `Math.round` (half toward +∞), including negative halfway values.
 * `kotlin.math.round` / `roundToLong` send those the other way (`-1.5` → `-2`).
 */
class AimiTickPolicyMathTest {

    private fun assertNear(expected: Double, actual: Double, delta: Double = 1e-9) {
        assertTrue(abs(expected - actual) <= delta, "expected $expected ± $delta but was $actual")
    }

    @Test
    fun mathRoundNegativeHalfwayGoesTowardPositiveInfinity() {
        assertEquals(-1L, AimiTickPolicyMath.aimiMathRoundToLong(-1.5))
        assertEquals(-2L, AimiTickPolicyMath.aimiMathRoundToLong(-2.5))
        assertEquals(0L, AimiTickPolicyMath.aimiMathRoundToLong(-0.5))
        assertEquals(1L, AimiTickPolicyMath.aimiMathRoundToLong(0.5))
        assertEquals(2L, AimiTickPolicyMath.aimiMathRoundToLong(1.5))
        assertEquals(3L, AimiTickPolicyMath.aimiMathRoundToLong(2.5))
    }

    @Test
    fun mathRoundNonFiniteMatchesJava() {
        assertEquals(0L, AimiTickPolicyMath.aimiMathRoundToLong(Double.NaN))
        assertEquals(Long.MAX_VALUE, AimiTickPolicyMath.aimiMathRoundToLong(Double.POSITIVE_INFINITY))
        assertEquals(Long.MIN_VALUE, AimiTickPolicyMath.aimiMathRoundToLong(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun roundDigitsKeepsNegativeHalfwayTowardPositiveInfinity() {
        // scale 1: -1.5 is a negative half. Java Math.round returns -1. kotlin roundToLong returns -2.
        assertEquals(-1.0, AimiTickPolicyMath.round(-1.5, 0))
        assertEquals(2.0, AimiTickPolicyMath.round(1.5, 0))
        // one decimal: -1.25 * 10 = -12.5 → -12 → -1.2. The other tie rule would yield -1.3.
        assertNear(-1.2, AimiTickPolicyMath.round(-1.25, 1))
        assertNear(1.3, AimiTickPolicyMath.round(1.25, 1))
    }

    @Test
    fun roundDigitsPassesNonFiniteThroughUntouched() {
        assertTrue(AimiTickPolicyMath.round(Double.NaN, 2).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, AimiTickPolicyMath.round(Double.POSITIVE_INFINITY, 2))
        assertEquals(Double.NEGATIVE_INFINITY, AimiTickPolicyMath.round(Double.NEGATIVE_INFINITY, 2))
    }

    @Test
    fun roundBasalClampsNegativesThenRoundsHalfTowardPositiveInfinity() {
        assertEquals(0.0, AimiTickPolicyMath.roundBasal(-1.5))
        assertEquals(0.0, AimiTickPolicyMath.roundBasal(-0.001))
        assertNear(1.25, AimiTickPolicyMath.roundBasal(1.25))
        assertNear(1.24, AimiTickPolicyMath.roundBasal(1.235))
    }

    @Test
    fun tightSpiralThresholdsScaleFromAdultAnchorAndClamp() {
        assertNear(7.0, AimiTickPolicyMath.tightSpiralSmbCapEnergyThresholdU(55.0))
        assertEquals(7.0, AimiTickPolicyMath.tightSpiralSmbCapEnergyThresholdU(Double.NaN))
        assertEquals(7.0, AimiTickPolicyMath.tightSpiralSmbCapEnergyThresholdU(0.0))
        assertEquals(3.0, AimiTickPolicyMath.tightSpiralSmbCapEnergyThresholdU(20.0))
        assertEquals(18.0, AimiTickPolicyMath.tightSpiralSmbCapEnergyThresholdU(200.0))

        assertNear(8.0, AimiTickPolicyMath.tightSpiralSmbCapIobThresholdU(55.0, 75.0))
        assertNear(16.0, AimiTickPolicyMath.tightSpiralSmbCapIobThresholdU(55.0, 150.0))
        assertEquals(8.0, AimiTickPolicyMath.tightSpiralSmbCapIobThresholdU(Double.NaN, Double.NaN))
        assertEquals(20.0, AimiTickPolicyMath.tightSpiralSmbCapIobThresholdU(200.0, 200.0))
    }

    @Test
    fun sharpRiseUsesTheStackingThresholds() {
        assertTrue(AimiTickPolicyMath.sharpRiseEligibleForTrajectorySpiralSoftCap(4.5f, 0f))
        assertTrue(AimiTickPolicyMath.sharpRiseEligibleForTrajectorySpiralSoftCap(0f, 4.5f))
        assertTrue(AimiTickPolicyMath.sharpRiseEligibleForTrajectorySpiralSoftCap(3.2f, 3.0f))
        assertFalse(AimiTickPolicyMath.sharpRiseEligibleForTrajectorySpiralSoftCap(3.1f, 3.0f))
        assertFalse(AimiTickPolicyMath.sharpRiseEligibleForTrajectorySpiralSoftCap(3.2f, 2.9f))
    }

    @Test
    fun adjustDiaForIobUsesFivePercentPerUnitAboveTheDefaultThresholdOfTwo() {
        assertEquals(300f, AimiTickPolicyMath.adjustDIAForIOB(300f, 2f))
        assertEquals(300f, AimiTickPolicyMath.adjustDIAForIOB(300f, 1f))
        assertEquals(330f, AimiTickPolicyMath.adjustDIAForIOB(300f, 4f))
    }

    @Test
    fun predictGlycemiaWithZeroBasalStaysFlatAndOneStepAppliesTriangularActivity() {
        assertEquals(emptyList(), AimiTickPolicyMath.predictGlycemia(100.0, 1.0, 0, 50.0))
        assertEquals(listOf(100.0, 100.0), AimiTickPolicyMath.predictGlycemia(100.0, 0.0, 10, 50.0))
        val oneStep = AimiTickPolicyMath.predictGlycemia(100.0, 1.2, 5, 50.0)
        assertEquals(1, oneStep.size)
        assertNear(99.33333333333333, oneStep[0])
    }

    @Test
    fun adjustBasalForMealHyperBoostsOnlyInsideTheMealWindowAndCaps() {
        assertEquals(1.5, AimiTickPolicyMath.adjustBasalForMealHyper(1.5, 200.0, 100.0, 1.0, 1.0, false, 10, 100.0))
        assertEquals(1.5, AimiTickPolicyMath.adjustBasalForMealHyper(1.5, 200.0, 100.0, 1.0, 1.0, true, 200, 100.0))
        assertEquals(1.0, AimiTickPolicyMath.adjustBasalForMealHyper(1.0, 200.0, 100.0, 0.0, 0.0, true, 10, 100.0))
        assertEquals(5.0, AimiTickPolicyMath.adjustBasalForMealHyper(1.0, 140.0, 100.0, 1.0, 1.0, true, 10, 5.0))
        assertEquals(10.0, AimiTickPolicyMath.adjustBasalForMealHyper(1.0, 200.0, 100.0, 1.0, 1.0, true, 10, 100.0))
    }

    @Test
    fun calculateBasalRateRoundsProfileBasalAndClampsAtZero() {
        assertNear(2.4, AimiTickPolicyMath.calculateBasalRate(0.0, 1.2, 2.0))
        assertNear(2.0, AimiTickPolicyMath.calculateBasalRate(1.0, 9.0, 2.0))
        assertEquals(0.0, AimiTickPolicyMath.calculateBasalRate(1.0, 1.0, -1.0))
    }

    @Test
    fun dynamicMicroBolusIsTwentyOverIsfInsideTheCap() {
        val reason = StringBuilder()
        assertEquals(0.0, AimiTickPolicyMath.calculateDynamicMicroBolus(0.0, reason = reason))
        assertNear(0.4, AimiTickPolicyMath.calculateDynamicMicroBolus(50.0, reason = reason))
        assertNear(0.5, AimiTickPolicyMath.calculateDynamicMicroBolus(10.0, reason = reason))
        assertNear(0.05, AimiTickPolicyMath.calculateDynamicMicroBolus(1000.0, reason = reason))
    }

    @Test
    fun compressionGuardAppendsItsReasonOnlyOnAnImpossibleRise() {
        val quiet = StringBuilder()
        assertFalse(AimiTickPolicyMath.isCompressionProtectionCondition(10f, quiet))
        assertEquals("", quiet.toString())
        val hit = StringBuilder()
        assertTrue(AimiTickPolicyMath.isCompressionProtectionCondition(36f, hit))
        assertTrue(hit.toString().contains("Compression Rebound"))
    }

    @Test
    fun undeclaredMealNeedsTwoOfTheFourSignals() {
        assertFalse(
            AimiTickPolicyMath.isMealLikelyWithoutDeclaration(
                shortAvgDelta = 0f,
                delta = 0f,
                slopeFromMinDeviation = 0.0,
                recentBGs = emptyList(),
                estimatedCarbs = 30.0,
                estimatedCarbsAgeMs = 1_000L,
                localHour = 3,
            ),
        )
        assertTrue(
            AimiTickPolicyMath.isMealLikelyWithoutDeclaration(
                shortAvgDelta = 0f,
                delta = 0f,
                slopeFromMinDeviation = 0.0,
                recentBGs = emptyList(),
                estimatedCarbs = 30.0,
                estimatedCarbsAgeMs = 1_000L,
                localHour = 12,
            ),
        )
    }

    @Test
    fun runtimeHeuristicsTreatLargeNumbersAsSecondsOrMillis() {
        assertEquals(180, AimiTickPolicyMath.runtimeToMinutes(180L))
        assertEquals(3, AimiTickPolicyMath.runtimeToMinutes(181L))
        assertEquals(Int.MAX_VALUE, AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(null))
        assertEquals(10, AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(600_001L))
        assertEquals(3, AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(181L))
        assertEquals(100, AimiTickPolicyMath.mealModeRuntimeToNullableMinutes(100L))
    }

    @Test
    fun fallbackSmbNeedsHighRisingBgAndIobHeadroom() {
        val profile = profile(maxIob = 10.0)
        assertTrue(AimiTickPolicyMath.canFallbackSmbWithoutPrediction(140.0, 2.0, 100.0, 1.0, profile))
        assertFalse(AimiTickPolicyMath.canFallbackSmbWithoutPrediction(120.0, 2.0, 100.0, 1.0, profile))
        assertFalse(AimiTickPolicyMath.canFallbackSmbWithoutPrediction(140.0, 2.0, 100.0, 9.0, profile))
    }

    @Test
    fun dynamicBolusMultiplierIsASigmoidBetweenHalfAndOnePointTwo() {
        assertNear(0.85, AimiTickPolicyMath.computeDynamicBolusMultiplier(5f).toDouble(), 1e-6)
        assertTrue(AimiTickPolicyMath.computeDynamicBolusMultiplier(-100f) < 0.51f)
        assertTrue(AimiTickPolicyMath.computeDynamicBolusMultiplier(100f) > 1.19f)
    }

    @Test
    fun predictedDeltaWeightsTheTailOfTheHistory() {
        assertEquals(0.0, AimiTickPolicyMath.predictedDelta(emptyList()))
        assertNear(7.0 / 3.0, AimiTickPolicyMath.predictedDelta(listOf(1.0, 3.0)))
    }

    @Test
    fun softFloorLiftsOnlyTheArtefactBandWhenApplied() {
        val series = listOf(40, 100)
        val idle = telemetry(applied = false, soft = 80.0)
        assertEquals(series, AimiTickPolicyMath.applySoftFloorToPredSeries(series, idle))
        val lifted = AimiTickPolicyMath.applySoftFloorToPredSeries(series, telemetry(applied = true, soft = 80.0))
        assertEquals(listOf(80, 100), lifted)
    }

    @Test
    fun basalFloorHoldsTheCruiseFloorWhenTheSuggestionIsSafeAndLow() {
        val decision = SafetyDecision(stopBasal = false, bolusFactor = 1.0, reason = "", basalLS = false)
        val rate = AimiTickPolicyMath.applyBasalFloor(
            suggestedRate = 0.1,
            profileBasal = 1.0,
            safetyDecision = decision,
            activityContext = ActivityContext(state = ActivityState.REST),
            bg = 120.0,
            delta = 0.0,
            shortAvgDelta = 0.0,
            predictedBg = 100.0,
            isMealActive = false,
            lgsThreshold = 70.0,
        )
        assertNear(0.55, rate)
        val stopped = AimiTickPolicyMath.applyBasalFloor(
            suggestedRate = 0.0,
            profileBasal = 1.0,
            safetyDecision = decision.copy(stopBasal = true),
            activityContext = ActivityContext(),
            bg = 120.0,
            delta = 0.0,
            shortAvgDelta = 0.0,
            predictedBg = 100.0,
            isMealActive = false,
            lgsThreshold = 70.0,
        )
        assertEquals(0.0, stopped)
    }

    @Test
    fun generalHyperScalesWithDeviationAndCapsAtMaxBasal() {
        assertEquals(1.0, AimiTickPolicyMath.adjustBasalForGeneralHyper(1.0, 100.0, 100.0, 0.0, 0.0, 10.0))
        assertEquals(5.0, AimiTickPolicyMath.adjustBasalForGeneralHyper(1.0, 160.0, 100.0, 1.0, 1.0, 100.0))
        assertEquals(4.0, AimiTickPolicyMath.adjustBasalForGeneralHyper(1.0, 220.0, 100.0, 11.0, 1.0, 4.0))
    }

    @Test
    fun sanitizeForJsonEscapesQuotesAndBackslashes() {
        assertEquals("a\\\"b", AimiTickPolicyMath.sanitizeForJson("a\"b"))
        assertEquals("a\\\\b", AimiTickPolicyMath.sanitizeForJson("a\\b"))
        assertEquals("a\\nb", AimiTickPolicyMath.sanitizeForJson("a\nb"))
    }

    @Test
    fun processNotesLowercasesAndStripsPunctuation() {
        assertEquals("ok", AimiTickPolicyMath.processNotesAndCleanUp("OK"))
        assertEquals("hi ", AimiTickPolicyMath.processNotesAndCleanUp("Hi!"))
    }

    @Test
    fun inferFinalLoopDecisionReadsUnitsThenTemp() {
        assertEquals("smb", AimiTickPolicyMath.inferFinalLoopDecisionFromResult(rt(units = 0.2)))
        assertEquals("suspend", AimiTickPolicyMath.inferFinalLoopDecisionFromResult(rt(duration = 30, rate = 0.0)))
        assertEquals("tbr_up", AimiTickPolicyMath.inferFinalLoopDecisionFromResult(rt(duration = 30, rate = 1.0)))
        assertEquals("none", AimiTickPolicyMath.inferFinalLoopDecisionFromResult(rt()))
    }

    private fun rt(units: Double? = null, duration: Int? = null, rate: Double? = null) =
        RT(runningDynamicIsf = false, units = units, duration = duration, rate = rate)

    private fun telemetry(applied: Boolean, soft: Double?) = PkpdSoftFloorTelemetry(
        rawPathMinMgdl = 39.0,
        softPathMinMgdl = soft,
        hybridTerminalMgdl = 100.0,
        hitNumericFloor = true,
        applied = applied,
        endogenousReversionEnabled = true,
        suppressedByFallingTrend = false,
        reason = "test",
    )

    private fun profile(maxIob: Double) = OapsProfileAimi(
        dia = 5.0,
        min_5m_carbimpact = 0.0,
        max_iob = maxIob,
        max_daily_basal = 1.0,
        max_basal = 1.0,
        min_bg = 90.0,
        max_bg = 150.0,
        target_bg = 100.0,
        carb_ratio = 10.0,
        sens = 50.0,
        autosens_adjust_targets = false,
        max_daily_safety_multiplier = 1.0,
        current_basal_safety_multiplier = 1.0,
        high_temptarget_raises_sensitivity = false,
        low_temptarget_lowers_sensitivity = false,
        sensitivity_raises_target = false,
        resistance_lowers_target = false,
        adv_target_adjustments = false,
        exercise_mode = false,
        half_basal_exercise_target = 160,
        maxCOB = 120,
        skip_neutral_temps = false,
        remainingCarbsCap = 0,
        enableUAM = false,
        A52_risk_enable = false,
        SMBInterval = 3,
        enableSMB_with_COB = false,
        enableSMB_with_temptarget = false,
        allowSMB_with_high_temptarget = false,
        enableSMB_always = false,
        enableSMB_after_carbs = false,
        maxSMBBasalMinutes = 30,
        maxUAMSMBBasalMinutes = 30,
        bolus_increment = 0.1,
        carbsReqThreshold = 1,
        current_basal = 1.0,
        temptargetSet = false,
        autosens_max = 1.2,
        out_units = "mg/dl",
        lgsThreshold = 70,
        variable_sens = 50.0,
        insulinDivisor = 1,
        TDD = 40.0,
        peakTime = 75.0,
        futureActivity = 0.0,
        sensorLagActivity = 0.0,
        historicActivity = 0.0,
        currentActivity = 0.0,
    )
}
