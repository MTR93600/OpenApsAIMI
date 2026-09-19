package app.aaps.plugins.aps.openAPSAIMI

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Matching tests from `origin/dev_OAPSAIMI` @ `5220fc5e2f` (file unchanged on tip `f61474bb73`).
 * Study source set: [UndeclaredCobEstimator] is commonMain and has no mocks, so the tests live in
 * `commonTest` (`kotlin.test`) — same layout as [app.aaps.plugins.aps.openAPSAIMI.ISF.HeartRateTrendIsfTest],
 * not `androidHostTest`. Assertions are the same locks as the Truth/JUnit reference corpus.
 *
 * The four heart-rate rise-suspend tests at the bottom are the P3.1 behaviour. The rest locks the
 * estimator already on study so the gate change cannot silently rewrite another reason.
 */
class UndeclaredCobEstimatorTest {

    /** A baseline input that would produce a positive estimate; individual tests mutate one field. */
    private fun baseInput(
        estimatedRaMgdlPerMin: Double = 2.0,
        bgMgdl: Double = 150.0,
        deltaMgdl5m: Double = 3.0,
        slopeFromMinDeviation: Double = 2.0,
        mealProb: Double = 0.8,
        falseMealSuppression: Boolean = false,
        exerciseLockoutActive: Boolean = false,
        activityDetected: Boolean = false,
        postHypoActive: Boolean = false,
        cfrdExacerbationActive: Boolean = false,
        hrInflammationElevated: Boolean = false,
        patientWeightKg: Double = 70.0,
        tdd24hU: Double? = 40.0,
        maxGramsPref: Double = 25.0,
    ) = UndeclaredCobEstimator.Input(
        estimatedRaMgdlPerMin = estimatedRaMgdlPerMin,
        isfMgdlPerU = 40.0,
        carbRatioGPerU = 10.0,
        bgMgdl = bgMgdl,
        deltaMgdl5m = deltaMgdl5m,
        slopeFromMinDeviation = slopeFromMinDeviation,
        patientWeightKg = patientWeightKg,
        tdd24hU = tdd24hU,
        stepsLast5m = 0,
        stepsLast15m = 0,
        activityDetected = activityDetected,
        mealProb = mealProb,
        falseMealSuppression = falseMealSuppression,
        exerciseLockoutActive = exerciseLockoutActive,
        postHypoActive = postHypoActive,
        cfrdExacerbationActive = cfrdExacerbationActive,
        hrInflammationElevated = hrInflammationElevated,
        maxGramsPref = maxGramsPref,
    )

    @Test
    fun confirmedRise_withRa_producesBoundedPositiveEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput())
        assertFalse(r.gated)
        assertTrue(r.grams > 0.0)
        assertTrue(r.grams <= r.capGrams)
        assertEquals("ra_meal_estimate", r.reason)
    }

    @Test
    fun falseMealSuppression_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(falseMealSuppression = true))
        assertEquals(0.0, r.grams)
        assertEquals("false_meal_suppression", r.reason)
    }

    @Test
    fun exacerbation_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(cfrdExacerbationActive = true))
        assertEquals(0.0, r.grams)
        assertEquals("cfrd_exacerbation", r.reason)
    }

    @Test
    fun elevatedHrInflammation_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(hrInflammationElevated = true))
        assertEquals("hr_inflammation", r.reason)
    }

    @Test
    fun exerciseOrActivity_mutesEstimate() {
        assertEquals(
            "exercise_activity",
            UndeclaredCobEstimator.estimate(baseInput(exerciseLockoutActive = true)).reason
        )
        assertEquals(
            "exercise_activity",
            UndeclaredCobEstimator.estimate(baseInput(activityDetected = true)).reason
        )
    }

    @Test
    fun hypoZoneOrPostHypo_mutesEstimate() {
        assertEquals("hypo_zone", UndeclaredCobEstimator.estimate(baseInput(bgMgdl = 75.0)).reason)
        assertEquals("post_hypo", UndeclaredCobEstimator.estimate(baseInput(postHypoActive = true)).reason)
    }

    @Test
    fun lowMealProb_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(mealProb = 0.3))
        assertEquals("meal_prob_low", r.reason)
    }

    @Test
    fun noConfirmedRise_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(deltaMgdl5m = 0.2, slopeFromMinDeviation = 0.1))
        assertEquals("no_confirmed_rise", r.reason)
    }

    @Test
    fun noRaSignal_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(estimatedRaMgdlPerMin = 0.0))
        assertEquals("no_ra_signal", r.reason)
    }

    @Test
    fun estimateNeverExceedsPreferenceCap() {
        // Huge Ra but small pref cap → clamped to cap.
        val r = UndeclaredCobEstimator.estimate(baseInput(estimatedRaMgdlPerMin = 20.0, maxGramsPref = 8.0))
        assertTrue(r.grams <= 8.0)
    }

    @Test
    fun tddCeilingCanBindBelowPrefCap() {
        // Small TDD → TDD ceiling (tdd*cr*0.4 = 5*10*0.4 = 20) may bind; ensure cap reflects it.
        val r = UndeclaredCobEstimator.estimate(baseInput(estimatedRaMgdlPerMin = 20.0, tdd24hU = 5.0, maxGramsPref = 80.0))
        assertTrue(r.capGrams <= 20.0)
    }

    @Test
    fun invalidCsf_isGatedSafely() {
        val bad = baseInput().copy(carbRatioGPerU = 0.0)
        val r = UndeclaredCobEstimator.estimate(bad)
        assertEquals("invalid_csf", r.reason)
    }

    // ---- heart rate has no authority during a fast rise -----------------------------------------

    /**
     * The gate exists only on this path: the whole estimator runs only when declared carbs are zero.
     * So a postprandial heart-rate rise — which is what a meal produces — switches off the estimator
     * written to catch the meal. It is self-defeating, and it is the same mistake as letting a heart
     * rate end the stress insulin-sensitivity floor mid-rise.
     *
     * The rule is the one already applied there: above
     * [UndeclaredCobEstimator.HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN] the rise is too fast to be
     * hormonal, so the heart-rate elevation is a consequence of it and may not gate anything.
     */
    @Test
    fun `an elevated heart rate does not gate the estimate while glucose rises fast`() {
        val result = UndeclaredCobEstimator.estimate(
            baseInput(
                hrInflammationElevated = true,
                deltaMgdl5m = UndeclaredCobEstimator.HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN,
            )
        )
        assertTrue("hr_inflammation" !in result.reason)
    }

    @Test
    fun `an elevated heart rate still gates the estimate on a gentle rise`() {
        val result = UndeclaredCobEstimator.estimate(
            baseInput(
                hrInflammationElevated = true,
                deltaMgdl5m = UndeclaredCobEstimator.HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN - 0.1,
            )
        )
        assertTrue(result.gated)
        assertTrue("hr_inflammation" in result.reason)
    }

    @Test
    fun `the other safety gates still stand during a fast rise`() {
        // The rise rule lifts the HEART RATE gate only. Everything more protective keeps its say.
        val fast = UndeclaredCobEstimator.HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN + 5.0
        assertTrue(
            UndeclaredCobEstimator.estimate(
                baseInput(falseMealSuppression = true, deltaMgdl5m = fast)
            ).gated
        )
        assertTrue(
            UndeclaredCobEstimator.estimate(baseInput(postHypoActive = true, deltaMgdl5m = fast)).gated
        )
        assertTrue(
            UndeclaredCobEstimator.estimate(
                baseInput(cfrdExacerbationActive = true, deltaMgdl5m = fast)
            ).gated
        )
        assertTrue(
            UndeclaredCobEstimator.estimate(baseInput(activityDetected = true, deltaMgdl5m = fast)).gated
        )
    }

    @Test
    fun `a rise that is not a usable number leaves the heart rate gate in force`() {
        val result = UndeclaredCobEstimator.estimate(
            baseInput(hrInflammationElevated = true, deltaMgdl5m = Double.NaN)
        )
        assertTrue(result.gated)
        assertTrue("hr_inflammation" in result.reason)
    }
}
