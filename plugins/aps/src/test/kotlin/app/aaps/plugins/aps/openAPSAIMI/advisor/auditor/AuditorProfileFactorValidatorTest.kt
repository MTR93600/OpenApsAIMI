package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Arrival rules: does one answer from the profile checker survive, once, against the 30-minute
 * window it was shown?
 *
 * The window is the 22:12 tick of the design spec (`AIMI_Decisions_Last24h.jsonl`,
 * 1790021540129): glucose rose from 158.7 to 173.6 while 2.40 U were absorbed, no carbs, meal
 * certainty LOW. That is the one case in the spec's sample that passes every arrival rule.
 */
class AuditorProfileFactorValidatorTest {

    private fun risingWindow(
        bgStart: Double = 158.7,
        bgEnd: Double = 173.6,
        iobStart: Double = 11.192,
        iobEnd: Double = 10.374,
        delivered: Double = 1.5798,
        absorbed: Double? = 2.3978,
        impliedIsf: Double? = -6.214,
        mealCertainty: String? = "LOW",
        mealSupport: Boolean = false,
        carbsG: Double = 0.0,
        mealModeName: String? = null,
        complete: Boolean = true,
    ) = AuditorProfileContext(
        contextBuiltAtMs = 1790021540129L,
        points = List(7) { null },
        isfProfileStaticMgdl = 60.0,
        isfDynamicMgdl = 36.649,
        isfCommandMgdl = 60.0,
        isfCommandPreFloorMgdl = 36.649,
        isfWorkingMgdl = 21.58,
        isfCommandOverProfile = 1.0,
        isfOnProfileFloor = true,
        isfFloorMultiplier = 1.0,
        targetProfileMgdl = 100.0,
        tempTargetActive = false,
        targetWorkingMgdl = 100.0,
        bgStartMgdl = bgStart,
        bgEndMgdl = bgEnd,
        bgMinMgdl = minOf(bgStart, bgEnd),
        bgMaxMgdl = maxOf(bgStart, bgEnd),
        iobStartU = iobStart,
        iobEndU = iobEnd,
        bolusU = 0.2226,
        netBasalU = 1.3572,
        insulinDeliveredU = delivered,
        insulinAbsorbedU = absorbed,
        impliedIsfMgdlPerU = impliedIsf,
        carbsG = carbsG,
        cobNowG = 0.0,
        mealModeName = mealModeName,
        mealCertaintyLevel = mealCertainty,
        mealSupport = mealSupport,
        minBg75mMgdl = 158.7,
        cgmNoise = 0.0,
        completeness = if (complete) 7 else 4,
        complete = complete,
    )

    private fun answer(
        isf: Double = 0.85,
        target: Double = 0.85,
        reasonCode: ProfileFactorReasonCode = ProfileFactorReasonCode.RESISTANCE_UNDER_CORRECTION,
        confidence: Double = 0.80,
        competingHypothesis: String = "none",
        claims: ProfileFactorClaims = ProfileFactorClaims(
            bgStartMgdl = 158.7, bgEndMgdl = 173.6, bgMinMgdl = 158.7,
            iobStartU = 11.19, iobEndU = 10.37, insulinDeliveredU = 1.58,
        ),
        failure: String? = null,
    ) = AuditorProfileFactorLlmOutput(
        isfFactorRaw = isf,
        targetFactorRaw = target,
        reasonCode = reasonCode,
        confidence = confidence,
        competingHypothesis = competingHypothesis,
        claims = claims,
        rationale = "",
        parseIssues = emptyList(),
        failure = failure,
    )

    private fun validate(
        output: AuditorProfileFactorLlmOutput,
        context: AuditorProfileContext,
        minConfidence: Double = 0.75,
    ) = AuditorProfileFactorValidator.validate(
        output = output,
        context = context,
        minConfidence = minConfidence,
        auditId = "evt_1790021540129",
        receivedAtMs = 1790021547000L,
        keyOnAtArrival = true,
        providerName = "GEMINI",
        mainVerdictName = "SOFTEN",
        latencyMs = 6871L,
        contextJson = "{}",
    )

    @Test
    fun `the good answer is kept as is`() {
        val proposal = validate(answer(), risingWindow())
        assertEquals(0.85, proposal.isfFactor, 1e-9)
        assertEquals(0.85, proposal.targetFactor, 1e-9)
        assertEquals(ProfileFactorDirection.RAISE, proposal.direction)
        assertTrue(proposal.refusedBy.isEmpty()) { proposal.refusedBy.toString() }
    }

    @Test
    fun `a factor outside the bounds is clamped and marked`() {
        val proposal = validate(answer(isf = 0.5, target = 0.5), risingWindow())
        assertEquals(0.85, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("isf_clamped"))
        assertTrue(proposal.refusedBy.contains("target_clamped"))

        val protective = validate(
            answer(isf = 2.0, target = 2.0, reasonCode = ProfileFactorReasonCode.SENSITIVITY_OVER_CORRECTION),
            risingWindow(),
        )
        assertEquals(1.15, protective.isfFactor, 1e-9)
        assertTrue(protective.refusedBy.contains("isf_clamped"))
    }

    @Test
    fun `a factor inside the neutral band counts as 1 point 0`() {
        val proposal = validate(answer(isf = 0.995, target = 0.995), risingWindow())
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertEquals(1.0, proposal.targetFactor, 1e-9)
        assertEquals(ProfileFactorDirection.NONE, proposal.direction)
    }

    @Test
    fun `mixed directions refuse both factors`() {
        val proposal = validate(answer(isf = 0.9, target = 1.1), risingWindow())
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertEquals(1.0, proposal.targetFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("mixed_direction"))
    }

    @Test
    fun `a reason that does not match the direction refuses both factors`() {
        val proposal = validate(answer(isf = 0.9, target = 0.9, reasonCode = ProfileFactorReasonCode.NONE), risingWindow())
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("reason_mismatch"))
    }

    @Test
    fun `confidence below the user minimum refuses both factors`() {
        val proposal = validate(answer(confidence = 0.70), risingWindow(), minConfidence = 0.75)
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("low_confidence"))
    }

    @Test
    fun `an incomplete context refuses both factors`() {
        val proposal = validate(answer(), risingWindow(complete = false))
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("context_incomplete"))
    }

    @Test
    fun `a hallucinated claim refuses both factors, and the claim check row says so`() {
        val claims = ProfileFactorClaims(
            bgStartMgdl = 158.7, bgEndMgdl = 173.6, bgMinMgdl = 158.7,
            iobStartU = 11.19, iobEndU = 2.0, insulinDeliveredU = 1.58,
        )
        val proposal = validate(answer(claims = claims), risingWindow())
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("claim_mismatch:iob_end_u"))
        val row = proposal.claimCheck.first { it.key == "iob_end_u" }
        assertEquals(2.0, row.llmValue!!, 1e-9)
        assertEquals(10.374, row.dataValue!!, 1e-9)
        assertFalse(row.ok)
    }

    @Test
    fun `a missing claim refuses both factors`() {
        val claims = ProfileFactorClaims(
            bgStartMgdl = 158.7, bgEndMgdl = 173.6, bgMinMgdl = 158.7,
            iobStartU = 11.19, iobEndU = 10.37, insulinDeliveredU = null,
        )
        val proposal = validate(answer(claims = claims), risingWindow())
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("claim_missing:insulin_delivered_u"))
    }

    @Test
    fun `a claim just inside tolerance passes, just outside it fails`() {
        // bg_end tolerance = max(5.0, 0.05 x 173.6) = 8.68
        val inside = ProfileFactorClaims(
            bgStartMgdl = 158.7, bgEndMgdl = 178.5, bgMinMgdl = 158.7,
            iobStartU = 11.19, iobEndU = 10.37, insulinDeliveredU = 1.58,
        )
        assertTrue(validate(answer(claims = inside), risingWindow()).refusedBy.none { it.startsWith("claim_mismatch") })

        val outside = inside.copy(bgEndMgdl = 182.4)
        val proposal = validate(answer(claims = outside), risingWindow())
        assertTrue(proposal.refusedBy.contains("claim_mismatch:bg_end_mgdl"))
    }

    @Test
    fun `meal certainty MED or HIGH refuses the raising direction`() {
        val proposal = validate(answer(), risingWindow(mealCertainty = "MED", mealSupport = true))
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("meal_certainty"))
    }

    @Test
    fun `not enough insulin absorbed refuses the raising direction`() {
        val proposal = validate(answer(), risingWindow(absorbed = 0.42, impliedIsf = null))
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("not_enough_insulin"))
    }

    @Test
    fun `a window that fell refuses the raising direction`() {
        val fellClaims = ProfileFactorClaims(
            bgStartMgdl = 138.8, bgEndMgdl = 90.9, bgMinMgdl = 90.9,
            iobStartU = 4.04, iobEndU = 2.78, insulinDeliveredU = 1.5798,
        )
        val proposal = validate(
            answer(claims = fellClaims),
            risingWindow(bgStart = 138.8, bgEnd = 90.9, iobStart = 4.04, iobEnd = 2.78, impliedIsf = 20.0),
        )
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.none { it.startsWith("claim_mismatch") || it.startsWith("claim_missing") })
        assertTrue(proposal.refusedBy.contains("bg_fell_in_window"))
        assertTrue(proposal.refusedBy.contains("bg_below_120"))
    }

    @Test
    fun `the protective direction is accepted on the same window that refused raising`() {
        val proposal = validate(
            answer(
                isf = 1.10, target = 1.10,
                reasonCode = ProfileFactorReasonCode.SENSITIVITY_OVER_CORRECTION,
                claims = ProfileFactorClaims(
                    bgStartMgdl = 138.8, bgEndMgdl = 90.9, bgMinMgdl = 90.9,
                    iobStartU = 4.04, iobEndU = 2.78, insulinDeliveredU = 1.5798,
                ),
            ),
            risingWindow(bgStart = 138.8, bgEnd = 90.9, iobStart = 4.04, iobEnd = 2.78),
        )
        assertEquals(1.10, proposal.isfFactor, 1e-9)
        assertEquals(1.10, proposal.targetFactor, 1e-9)
        assertEquals(ProfileFactorDirection.PROTECT, proposal.direction)
    }

    @Test
    fun `a competing hypothesis blocks the raising direction`() {
        val proposal = validate(answer(competingHypothesis = "undeclared_meal"), risingWindow())
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("competing_hypothesis"))
    }

    @Test
    fun `a failed call refuses both factors and keeps the reason`() {
        val proposal = validate(AuditorProfileFactorLlmOutput.failed("timeout"), risingWindow())
        assertEquals(1.0, proposal.isfFactor, 1e-9)
        assertEquals(1.0, proposal.targetFactor, 1e-9)
        assertTrue(proposal.refusedBy.contains("llm_error:timeout"))
    }
}
