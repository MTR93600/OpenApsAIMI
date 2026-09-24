package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The target gate and the R3 combined bound: the ISF and the target share one 15 % budget, the ISF
 * spends first, and the target only ever gets what is left. Numbers are the 22:12 and 23:12 ticks of
 * the design spec.
 */
class AuditorProfileFactorGateTargetTest {

    private val auditedTickMs = 1790021540129L

    private val clearSafety = TickSafety(
        bgMgdl = 173.6,
        deltaMgdl5m = 1.71,
        shortAvgDeltaMgdl5m = 1.0,
        cgmNoise = 0.0,
        hypoThresholdMgdl = 70.0,
        minPredBgMgdl = 80.52,
        minPredThresholdMgdl = 70.0,
        minPredFromPreviousTick = false,
        postHypoActive = false,
        minBg75mMgdl = 158.7,
        exerciseLockout = false,
    )

    private fun proposal(isf: Double, target: Double) = AuditorProfileProposal(
        auditId = "evt_$auditedTickMs",
        contextBuiltAtMs = auditedTickMs,
        receivedAtMs = auditedTickMs + 7000,
        isfFactor = isf,
        targetFactor = target,
        direction = if (target < 1.0) ProfileFactorDirection.RAISE else ProfileFactorDirection.PROTECT,
        reasonCode = if (target < 1.0) ProfileFactorReasonCode.RESISTANCE_UNDER_CORRECTION else ProfileFactorReasonCode.SENSITIVITY_OVER_CORRECTION,
        confidence = 0.8,
        refusedBy = emptyList(),
        claimCheck = emptyList(),
        llm = AuditorProfileFactorLlmOutput.failed("unused"),
        keyOnAtArrival = true,
        providerName = "GEMINI",
        mainVerdictName = "SOFTEN",
        latencyMs = 6871L,
        contextJson = "{}",
    )

    private fun decide(
        targetFactor: Double,
        workingTargetRawMgdl: Double,
        isfEffectiveFactor: Double,
        bgMgdl: Double = 173.6,
        safety: TickSafety = clearSafety.copy(bgMgdl = bgMgdl),
    ) = AuditorProfileFactorGate.evaluateTarget(
        proposal = proposal(isf = targetFactor, target = targetFactor),
        keyOn = true,
        tickTimestampMs = auditedTickMs,
        workingTargetRawMgdl = workingTargetRawMgdl,
        isfEffectiveFactor = isfEffectiveFactor,
        safety = safety,
    )

    @Test
    fun `the ISF budget of the shared bound is exact`() {
        assertEquals(
            12.9882,
            AuditorProfileFactorGate.combinedTargetBudgetMgdl(173.6, 100.0, 1.0),
            1e-3,
        )
        assertEquals(0.0, AuditorProfileFactorGate.combinedTargetBudgetMgdl(173.6, 100.0, 0.85), 1e-9)
        assertEquals(
            8.6588,
            AuditorProfileFactorGate.combinedTargetBudgetMgdl(173.6, 100.0, 0.95),
            1e-3,
        )
    }

    @Test
    fun `when the ISF has not moved, the target gets the full 17 point 65 percent budget`() {
        val decision = decide(targetFactor = 0.85, workingTargetRawMgdl = 100.0, isfEffectiveFactor = 1.0)
        assertEquals(87.0118, decision.targetMgdl, 1e-3)
        assertEquals(0.870118, decision.effective, 1e-5)
        assertTrue(decision.refusedBy.contains("combined_bound"))
        assertTrue(decision.applied)
    }

    @Test
    fun `when the ISF already spent the whole budget, the target gets none`() {
        val decision = decide(targetFactor = 0.85, workingTargetRawMgdl = 100.0, isfEffectiveFactor = 0.85)
        assertEquals(100.0, decision.targetMgdl, 1e-9)
        assertEquals(1.0, decision.effective, 1e-9)
        assertFalse(decision.applied)
        assertTrue(decision.refusedBy.contains("combined_bound"))
    }

    @Test
    fun `a partial ISF spend leaves a partial budget`() {
        val decision = decide(targetFactor = 0.85, workingTargetRawMgdl = 100.0, isfEffectiveFactor = 0.95)
        assertEquals(91.3412, decision.targetMgdl, 1e-3)
    }

    @Test
    fun `the target may never be pushed under 80 even when the budget allows it`() {
        val decision = decide(targetFactor = 0.85, workingTargetRawMgdl = 90.0, isfEffectiveFactor = 1.0, bgMgdl = 170.2)
        assertEquals(80.0, decision.targetMgdl, 1e-9)
        assertTrue(decision.refusedBy.contains("target_floor"))
    }

    @Test
    fun `an engine target already under 80 is never pushed lower still`() {
        val decision = decide(targetFactor = 0.90, workingTargetRawMgdl = 70.0, isfEffectiveFactor = 1.0, bgMgdl = 167.6)
        assertEquals(70.0, decision.targetMgdl, 1e-9)
        assertTrue(decision.refusedBy.contains("target_floor"))
    }

    @Test
    fun `at or below target there is nothing to drop`() {
        val decision = decide(targetFactor = 0.85, workingTargetRawMgdl = 130.0, isfEffectiveFactor = 1.0, bgMgdl = 125.0)
        assertEquals(130.0, decision.targetMgdl, 1e-9)
        assertTrue(decision.refusedBy.contains("combined_bound"))
    }

    @Test
    fun `a protective factor may raise the target up to its own ceiling`() {
        val decision = decide(targetFactor = 1.15, workingTargetRawMgdl = 180.0, isfEffectiveFactor = 1.0, bgMgdl = 90.0)
        assertEquals(200.0, decision.targetMgdl, 1e-9)
    }

    @Test
    fun `scaleTargetForDose is the identity at 1 point 0, and floors and ceilings the rest`() {
        val t = 100.0
        assertEquals(t, AuditorProfileFactorGate.scaleTargetForDose(t, 1.0), 0.0)
        assertEquals(t.toRawBits(), AuditorProfileFactorGate.scaleTargetForDose(t, 1.0).toRawBits())

        // A target already lowered by `adv_target_adjustments` still floors at 80.
        assertEquals(80.0, AuditorProfileFactorGate.scaleTargetForDose(87.0, 0.870118), 1e-2)
        assertEquals(200.0, AuditorProfileFactorGate.scaleTargetForDose(180.0, 1.15), 1e-9)
    }

    @Test
    fun `each dose site is bounded against its own target, not against the one the decision saw`() {
        // The step-activity branch moves the loop target member to 130 and leaves the local working
        // target at 100. The decision is taken on 100; the basal engine reads 130.
        val decision = decide(targetFactor = 0.85, workingTargetRawMgdl = 100.0, isfEffectiveFactor = 1.0, bgMgdl = 200.0)
        assertEquals(85.0, decision.targetMgdl, 1e-9)
        assertTrue(decision.applied)

        val basalTarget = AuditorProfileFactorGate.targetForDoseSite(
            targetMgdl = 130.0,
            decision = decision,
            bgMgdl = 200.0,
            isfEffectiveFactor = 1.0,
        )
        // A plain ratio would have given 130 x 0.85 = 110.5, a drop of 19.5 on an error of 70, which
        // is a dose multiplier of 1.279 against the 1.1765 the shared bound exists to enforce.
        assertEquals(117.647, basalTarget, 1e-3)
        val error = 200.0 - 130.0
        val multiplier = 1.0 + (130.0 - basalTarget) / error
        assertTrue(multiplier <= 1.0 / 0.85 + 1e-9) { "multiplier was $multiplier" }
    }

    @Test
    fun `a dose site that holds a lower target than the decision gets a smaller drop`() {
        // `adv_target_adjustments` can lower the SMB site's target between the decision and the dose.
        val decision = decide(targetFactor = 0.85, workingTargetRawMgdl = 100.0, isfEffectiveFactor = 1.0, bgMgdl = 200.0)
        val smbTarget = AuditorProfileFactorGate.targetForDoseSite(
            targetMgdl = 90.0,
            decision = decision,
            bgMgdl = 200.0,
            isfEffectiveFactor = 1.0,
        )
        // 90 x 0.85 = 76.5, but the hard floor of `AuditorProfileFactorLimits.TARGET_MIN_MGDL` (80)
        // is reached first, so the site keeps 80. The drop is smaller than the decision's, which is
        // the property this test exists for; the exact number is the floor's, not the factor's.
        assertEquals(80.0, smbTarget, 1e-3)
        assertTrue(90.0 - smbTarget <= 100.0 - decision.targetMgdl + 1e-9)
    }

    @Test
    fun `a dose site never crosses the hard target floor`() {
        val decision = decide(targetFactor = 0.85, workingTargetRawMgdl = 100.0, isfEffectiveFactor = 1.0, bgMgdl = 200.0)
        val low = AuditorProfileFactorGate.targetForDoseSite(
            targetMgdl = 85.0,
            decision = decision,
            bgMgdl = 200.0,
            isfEffectiveFactor = 1.0,
        )
        assertEquals(80.0, low, 1e-9)
    }

    @Test
    fun `a refused decision leaves every dose site untouched, bit for bit`() {
        val refused = decide(targetFactor = 0.85, workingTargetRawMgdl = 100.0, isfEffectiveFactor = 0.85)
        assertFalse(refused.applied)
        val site = AuditorProfileFactorGate.targetForDoseSite(130.0, refused, 200.0, 0.85)
        assertEquals(130.0.toRawBits(), site.toRawBits())
    }

    @Test
    fun `point B judges this tick's own prediction, not the previous tick's`() {
        val decision = decide(
            targetFactor = 0.85,
            workingTargetRawMgdl = 90.0,
            isfEffectiveFactor = 1.0,
            bgMgdl = 140.7,
            safety = clearSafety.copy(
                bgMgdl = 140.7,
                minPredBgMgdl = 40.09,
                minPredThresholdMgdl = 70.0,
                minPredFromPreviousTick = false,
            ),
        )
        assertFalse(decision.applied)
        assertTrue(decision.refusedBy.contains("predicted_low"))
    }
}
