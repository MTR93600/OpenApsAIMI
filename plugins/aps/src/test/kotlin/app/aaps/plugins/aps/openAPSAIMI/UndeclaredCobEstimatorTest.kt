package app.aaps.plugins.aps.openAPSAIMI

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

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
        assertThat(r.gated).isFalse()
        assertThat(r.grams).isGreaterThan(0.0)
        assertThat(r.grams).isAtMost(r.capGrams)
        assertThat(r.reason).isEqualTo("ra_meal_estimate")
    }

    @Test
    fun falseMealSuppression_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(falseMealSuppression = true))
        assertThat(r.grams).isEqualTo(0.0)
        assertThat(r.reason).isEqualTo("false_meal_suppression")
    }

    @Test
    fun exacerbation_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(cfrdExacerbationActive = true))
        assertThat(r.grams).isEqualTo(0.0)
        assertThat(r.reason).isEqualTo("cfrd_exacerbation")
    }

    @Test
    fun elevatedHrInflammation_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(hrInflammationElevated = true))
        assertThat(r.reason).isEqualTo("hr_inflammation")
    }

    @Test
    fun exerciseOrActivity_mutesEstimate() {
        assertThat(UndeclaredCobEstimator.estimate(baseInput(exerciseLockoutActive = true)).reason)
            .isEqualTo("exercise_activity")
        assertThat(UndeclaredCobEstimator.estimate(baseInput(activityDetected = true)).reason)
            .isEqualTo("exercise_activity")
    }

    @Test
    fun hypoZoneOrPostHypo_mutesEstimate() {
        assertThat(UndeclaredCobEstimator.estimate(baseInput(bgMgdl = 75.0)).reason).isEqualTo("hypo_zone")
        assertThat(UndeclaredCobEstimator.estimate(baseInput(postHypoActive = true)).reason).isEqualTo("post_hypo")
    }

    @Test
    fun lowMealProb_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(mealProb = 0.3))
        assertThat(r.reason).isEqualTo("meal_prob_low")
    }

    @Test
    fun noConfirmedRise_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(deltaMgdl5m = 0.2, slopeFromMinDeviation = 0.1))
        assertThat(r.reason).isEqualTo("no_confirmed_rise")
    }

    @Test
    fun noRaSignal_mutesEstimate() {
        val r = UndeclaredCobEstimator.estimate(baseInput(estimatedRaMgdlPerMin = 0.0))
        assertThat(r.reason).isEqualTo("no_ra_signal")
    }

    @Test
    fun estimateNeverExceedsPreferenceCap() {
        // Huge Ra but small pref cap → clamped to cap.
        val r = UndeclaredCobEstimator.estimate(baseInput(estimatedRaMgdlPerMin = 20.0, maxGramsPref = 8.0))
        assertThat(r.grams).isAtMost(8.0)
    }

    @Test
    fun tddCeilingCanBindBelowPrefCap() {
        // Small TDD → TDD ceiling (tdd*cr*0.4 = 5*10*0.4 = 20) may bind; ensure cap reflects it.
        val r = UndeclaredCobEstimator.estimate(baseInput(estimatedRaMgdlPerMin = 20.0, tdd24hU = 5.0, maxGramsPref = 80.0))
        assertThat(r.capGrams).isAtMost(20.0)
    }

    @Test
    fun invalidCsf_isGatedSafely() {
        val bad = baseInput().copy(carbRatioGPerU = 0.0)
        val r = UndeclaredCobEstimator.estimate(bad)
        assertThat(r.reason).isEqualTo("invalid_csf")
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
        assertThat(result.reason).doesNotContain("hr_inflammation")
    }

    @Test
    fun `an elevated heart rate still gates the estimate on a gentle rise`() {
        val result = UndeclaredCobEstimator.estimate(
            baseInput(
                hrInflammationElevated = true,
                deltaMgdl5m = UndeclaredCobEstimator.HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN - 0.1,
            )
        )
        assertThat(result.gated).isTrue()
        assertThat(result.reason).contains("hr_inflammation")
    }

    @Test
    fun `the other safety gates still stand during a fast rise`() {
        // The rise rule lifts the HEART RATE gate only. Everything more protective keeps its say.
        val fast = UndeclaredCobEstimator.HR_GATE_RISE_SUSPEND_MGDL_PER_5MIN + 5.0
        assertThat(
            UndeclaredCobEstimator.estimate(
                baseInput(falseMealSuppression = true, deltaMgdl5m = fast)
            ).gated
        ).isTrue()
        assertThat(
            UndeclaredCobEstimator.estimate(baseInput(postHypoActive = true, deltaMgdl5m = fast)).gated
        ).isTrue()
        assertThat(
            UndeclaredCobEstimator.estimate(
                baseInput(cfrdExacerbationActive = true, deltaMgdl5m = fast)
            ).gated
        ).isTrue()
        assertThat(
            UndeclaredCobEstimator.estimate(baseInput(activityDetected = true, deltaMgdl5m = fast)).gated
        ).isTrue()
    }

    @Test
    fun `a rise that is not a usable number leaves the heart rate gate in force`() {
        val result = UndeclaredCobEstimator.estimate(
            baseInput(hrInflammationElevated = true, deltaMgdl5m = Double.NaN)
        )
        assertThat(result.gated).isTrue()
        assertThat(result.reason).contains("hr_inflammation")
    }
}
