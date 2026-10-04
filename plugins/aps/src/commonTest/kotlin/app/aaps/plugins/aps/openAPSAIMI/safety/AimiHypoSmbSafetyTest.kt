package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.activity.ActivityContext
import app.aaps.plugins.aps.openAPSAIMI.activity.ActivityState
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.CriticalInput
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.CriticalLabels
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.DelayFactorInput
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.DynamicPeakInput
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.HypoHysteresisState
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.InsulinEffectInput
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.MealAggressionInput
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.SafetyContext
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.SmbFloorInput
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.SmbIntervalInput
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.SmbIntervals
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.SpecificAdjustmentInput
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.SportSafetyInput
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.TrendInput
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden cases for the hypo / SMB safety block. Numbers are the ref formulas
 * (`dev_OAPSAIMI` @ `3dd0ca64772`), including the member-steps capture in the insulin effect.
 */
class AimiHypoSmbSafetyTest {

    private val echo = AimiHypoSmbSafety.PhraseBook { id, args ->
        id + args.joinToString(prefix = "(", postfix = ")") { it.toString() }
    }

    private fun assertNear(expected: Double, actual: Double, delta: Double = 1e-4) {
        assertTrue(abs(expected - actual) <= delta, "expected $expected ± $delta but was $actual")
    }

    @Test
    fun `a fast drop under 85 stops basal`() {
        val decision = AimiHypoSmbSafety.safetyAdjustment(
            currentBG = 80f,
            predictedBG = 70f,
            bgHistory = listOf(200f, 100f),
            combinedDelta = 0f,
            iob = 1f,
            maxIob = 8f,
            tdd24Hrs = 40f,
            tddPerHour = 1f,
            tirInhypo = 0f,
            targetBG = 100f,
            zeroBasalDurationMinutes = 0,
            delta = -5f,
            honeymoon = false,
            phrase = echo,
        )
        assertTrue(decision.stopBasal)
        assertTrue(decision.isHypoRisk)
        assertEquals(0.0, decision.bolusFactor)
        assertTrue(decision.reason.contains("bg_drop_high_critical"))
    }

    @Test
    fun `a fast drop between 85 and 110 halves the factor and does not stop`() {
        val decision = AimiHypoSmbSafety.safetyAdjustment(
            currentBG = 100f,
            predictedBG = 90f,
            bgHistory = listOf(200f, 100f),
            combinedDelta = 3f,
            iob = 0f,
            maxIob = 8f,
            tdd24Hrs = 48f,
            tddPerHour = 1f,
            tirInhypo = 0f,
            targetBG = 100f,
            zeroBasalDurationMinutes = 0,
            delta = -1f,
            honeymoon = false,
            phrase = echo,
        )
        assertFalse(decision.stopBasal)
        assertEquals(0.5, decision.bolusFactor)
    }

    @Test
    fun `zero basal longer than 60 minutes forces the factor back to 1 when there is no hypo`() {
        val decision = AimiHypoSmbSafety.safetyAdjustment(
            currentBG = 160f,
            predictedBG = 150f,
            bgHistory = listOf(160f, 160f),
            combinedDelta = 0.2f,
            iob = 0f,
            maxIob = 8f,
            tdd24Hrs = 48f,
            tddPerHour = 1f,
            tirInhypo = 0f,
            targetBG = 100f,
            zeroBasalDurationMinutes = 60,
            delta = 1f,
            honeymoon = false,
            phrase = echo,
        )
        assertFalse(decision.stopBasal)
        assertTrue(decision.basalLS)
        assertEquals(1.0, decision.bolusFactor)
    }

    @Test
    fun `morning DIA is 80 percent of the base and stays inside 3 to 12 hours`() {
        val out = AimiHypoSmbSafety.adjustedDiaMinutes(
            baseDIAHours = 9f,
            currentHour = 8,
            pumpAgeDays = 0f,
            iob = 0.0,
            activityContext = ActivityContext(state = ActivityState.REST),
            steps = 0,
            heartRate = 0,
            phrase = echo,
        )
        assertNear(432.0, out.minutes)
        assertTrue(out.reason.contains("morning_adjustment"))
    }

    @Test
    fun `a very fast rise asks for an SMB every minute`() {
        val out = AimiHypoSmbSafety.smbInterval(quietInterval(delta = 16f))
        assertEquals(1, out.minutes)
    }

    @Test
    fun `bg under 120 but at or above target still takes the 5 minute floor`() {
        val out = AimiHypoSmbSafety.smbInterval(quietInterval(bg = 110f, targetBg = 100f))
        assertEquals(5, out.minutes)
        assertTrue(out.logs.any { it.startsWith("LOW_BG_INTERVAL_BOOST") })
    }

    @Test
    fun `a real sport burst is sport safety and standing still is not`() {
        assertFalse(
            AimiHypoSmbSafety.sportSafety(
                SportSafetyInput(0, 0, 0, 0, 0, sportTime = false, activityActive = false, 70.0, 70.0, 100f),
            ),
        )
        assertTrue(
            AimiHypoSmbSafety.sportSafety(
                SportSafetyInput(400, 800, 0, 0, 0, sportTime = false, activityActive = false, 70.0, 70.0, 100f),
            ),
        )
    }

    @Test
    fun `below target halves the SMB and a zero dose on a rise becomes a tenth`() {
        assertEquals(
            0.5f,
            AimiHypoSmbSafety.specificAdjustment(
                SpecificAdjustmentInput(
                    smbAmount = 1f,
                    ignoreSafetyRestrictions = false,
                    delta = 1f,
                    shortAvgDelta = 1f,
                    longAvgDelta = 1f,
                    bg = 80.0,
                    targetBg = 100f,
                    honeymoon = false,
                    iob = 1f,
                    maxSmb = 1.0,
                    currentHour = 12,
                ),
            ),
        )
        assertEquals(
            0.1f,
            AimiHypoSmbSafety.finalizeSmbFloor(
                SmbFloorInput(smbToGive = 0f, iob = 0.05f, bg = 130.0, delta = 2f, lateFatRise = false),
            ),
        )
    }

    @Test
    fun `meal cob raises the aggression boost to 1_10 at a large overshoot`() {
        val weights = AimiHypoSmbSafety.mealAggression(
            MealAggressionInput(
                mealContextActive = true,
                predictedBg = 200.0,
                targetBg = 100f,
                bg = 180.0,
                hypoThreshold = 70.0,
                mealCob = 12.0,
            ),
        )
        assertTrue(weights.active)
        assertNear(1.10, weights.boostFactor)
        assertTrue(weights.bypassTail)
    }

    @Test
    fun `bg under 60 is a critical condition and updates hypo hysteresis`() {
        val scan = AimiHypoSmbSafety.criticalConditions(
            CriticalInput(
                context = quietContext(bg = 50.0, delta = -1.0, predictedBg = 48.0, eventualBG = 48.0),
                honeymoon = false,
                hyperDropExemptEnabled = false,
                labels = labels(),
                hysteresis = HypoHysteresisState(),
                nowMs = 1_000L,
            ),
        )
        assertTrue(scan.conditions.contains("below-min"))
        assertTrue(scan.conditions.contains("bg90"))
        assertEquals(1_000L, scan.hysteresis.lastHypoBlockAt)
    }

    @Test
    fun `evening delay factor is 1_2 at a normal bg`() {
        val factor = AimiHypoSmbSafety.adjustedDelayFactor(
            DelayFactorInput(
                bg = 110f,
                recentSteps180 = 0,
                averageHr = 70f,
                averageHr10 = 70f,
                currentHour = 20,
                normalBgThreshold = 110f,
            ),
        )
        assertNear(1.2, factor.toDouble())
    }

    @Test
    fun `insulin effect uses the member step count for the delay and the parameter for activity`() {
        val quiet = AimiHypoSmbSafety.insulinEffect(effectInput(parameterSteps = 0, memberSteps = 0, hour = 12, bg = 110f))
        val memberOnly = AimiHypoSmbSafety.insulinEffect(effectInput(parameterSteps = 0, memberSteps = 8000, hour = 12, bg = 110f))
        val parameterOnly = AimiHypoSmbSafety.insulinEffect(effectInput(parameterSteps = 8000, memberSteps = 0, hour = 12, bg = 100f))
        assertNear(2.0, quiet.toDouble())
        assertNear(1.7, memberOnly.toDouble())
        assertNear(1.4, parameterOnly.toDouble())
    }

    @Test
    fun `a strong positive trend is plus one`() {
        val trend = AimiHypoSmbSafety.trendIndicator(
            TrendInput(
                delta = 8f,
                shortAvgDelta = 6f,
                longAvgDelta = 4f,
                insulin = effectInput(0, 0, 12, 180f),
                recentSteps5 = 0,
                recentSteps10 = 0,
            ),
        )
        assertEquals(1, trend)
    }

    @Test
    fun `dynamic peak slows under resting tachycardia and stays inside 35 to 120`() {
        val peak = AimiHypoSmbSafety.dynamicPeak(
            DynamicPeakInput(
                currentActivity = 0.0,
                futureActivity = 0.0,
                sensorLagActivity = 0.0,
                historicActivity = 0.0,
                profilePeakTime = 60.0,
                stepCount = 10,
                heartRate = 110,
                bg = 100.0,
                delta = 0.0,
            ),
            echo,
        )
        assertNear(90.0, peak.finalPeak)
        assertNear(90.0, peak.intermediate)
        assertTrue(peak.logs.any { it.contains("STRESS") })
    }

    private fun quietInterval(
        delta: Float = 0f,
        bg: Float = 140f,
        targetBg: Float = 100f,
    ) = SmbIntervalInput(
        delta = delta,
        bg = bg,
        targetBg = targetBg,
        iob = 1.0,
        maxSmb = 1.0,
        honeymoon = false,
        night = false,
        currentHour = 12,
        snackTime = false,
        mealTime = false,
        bfastTime = false,
        lunchTime = false,
        dinnerTime = false,
        sleepTime = false,
        highCarbTime = false,
        lowCarbTime = false,
        intervals = SmbIntervals(3, 3, 3, 3, 3, 3, 3, 3),
        pkpdThrottleIntervalAdd = 0,
        recentSteps5 = 0,
        recentSteps30 = 0,
        recentSteps180 = 0,
        lastSmbTime = 0,
    )

    private fun quietContext(
        bg: Double,
        delta: Double,
        predictedBg: Double,
        eventualBG: Double,
    ) = SafetyContext(
        delta = delta,
        bg = bg,
        iob = 0.2,
        predictedBg = predictedBg,
        eventualBG = eventualBG,
        shortAvgDelta = delta,
        longAvgDelta = delta,
        fastingTime = false,
        iscalibration = false,
        targetBg = 100.0,
        maxSMB = 1.0,
        maxIob = 8.0,
        mealTime = false,
        bfastTime = false,
        lunchTime = false,
        dinnerTime = false,
        highCarbTime = false,
        snackTime = false,
        cob = 0.0,
        hypoThreshold = 70.0,
    )

    private fun labels() = CriticalLabels(
        hypoGuard = "hypo",
        honeysmb = "honey",
        negDelta = "neg",
        nosmb = "nosmb",
        fasting = "fast",
        belowMin = "below-min",
        newCalibration = "cal",
        belowTargetDropping = "drop-target",
        belowTargetStableNoCob = "stable",
        droppingFast = "fast-drop",
        droppingFastAtHigh = "fast-high",
        droppingVeryFast = "very-fast",
        prediction = "pred",
        bg90 = "bg90",
        acceleratingDown = "accel",
    )

    private fun effectInput(parameterSteps: Int, memberSteps: Int, hour: Int, bg: Float) = InsulinEffectInput(
        bg = bg,
        iob = 2f,
        variableSensitivity = 50f,
        cob = 0f,
        normalBgThreshold = 110f,
        recentSteps180Min = parameterSteps,
        memberRecentSteps180 = memberSteps,
        averageHr = 70f,
        averageHr10 = 70f,
        insulinDivisor = 50f,
        currentHour = hour,
        phrase = echo,
    )
}
