package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tick-by-tick ISF gate: age, the live glucose state, and — since commit 6a6561caab — the
 * stress floor applied to the dose-facing sensitivity itself (`WorkingIsf.finalize`). The two steps
 * are judged separately here, [AuditorProfileFactorGate.evaluateIsf] then
 * [AuditorProfileFactorGate.applyIsfFloor], the same order the tick runs them in.
 */
class AuditorProfileFactorGateIsfTest {

    private val auditedTickMs = 1790021540129L

    private val clearSafety = TickSafety(
        bgMgdl = 173.6,
        deltaMgdl5m = 1.71,
        shortAvgDeltaMgdl5m = 1.0,
        cgmNoise = 0.0,
        hypoThresholdMgdl = 70.0,
        minPredBgMgdl = 80.52,
        minPredThresholdMgdl = 70.0,
        minPredFromPreviousTick = true,
        postHypoActive = false,
        minBg75mMgdl = 158.7,
        exerciseLockout = false,
    )

    private fun proposal(
        isf: Double,
        target: Double = isf,
        direction: ProfileFactorDirection = if (isf < 1.0) ProfileFactorDirection.RAISE else ProfileFactorDirection.PROTECT,
        contextBuiltAtMs: Long = auditedTickMs,
    ) = AuditorProfileProposal(
        auditId = "evt_$contextBuiltAtMs",
        contextBuiltAtMs = contextBuiltAtMs,
        receivedAtMs = contextBuiltAtMs + 7000,
        isfFactor = isf,
        targetFactor = target,
        direction = direction,
        reasonCode = if (isf < 1.0) ProfileFactorReasonCode.RESISTANCE_UNDER_CORRECTION else ProfileFactorReasonCode.SENSITIVITY_OVER_CORRECTION,
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
        requested: Double,
        workingMgdl: Double,
        stressFloorMgdl: Double?,
        keyOn: Boolean = true,
        tickTimestampMs: Long = auditedTickMs,
        safety: TickSafety = clearSafety,
    ): IsfTickDecision {
        val early = AuditorProfileFactorGate.evaluateIsf(
            proposal = proposal(requested, contextBuiltAtMs = auditedTickMs),
            keyOn = keyOn,
            tickTimestampMs = tickTimestampMs,
            safety = safety,
        )
        val raw = AuditorIsfRaw(workingMgdl = workingMgdl, stressFloorMgdl = stressFloorMgdl, profileStaticMgdl = 60.0)
        return AuditorProfileFactorGate.applyIsfFloor(early, raw, keyOn)
    }

    @Test
    fun `the stress floor absorbs a raising factor when the working ISF already sits on it`() {
        // The working ISF is pinned exactly at the stress floor: there is no room below it.
        val decision = decide(requested = 0.85, workingMgdl = 60.0, stressFloorMgdl = 60.0)
        assertEquals(1.0, decision.effective, 1e-9)
        assertFalse(decision.applied)
        assertTrue(decision.refusedBy.contains("profile_floor"))
        assertEquals(60.0, decision.workingAdjustedMgdl!!, 1e-9)
    }

    @Test
    fun `a floor well below the working ISF leaves the factor untouched`() {
        val decision = decide(requested = 0.85, workingMgdl = 37.17, stressFloorMgdl = 30.0)
        assertEquals(0.85, decision.effective, 1e-4)
        assertTrue(decision.applied)
        assertFalse(decision.refusedBy.contains("profile_floor"))
        assertEquals(31.5945, decision.workingAdjustedMgdl!!, 1e-3)
    }

    @Test
    fun `the floor never limits the protective direction`() {
        val decision = decide(requested = 1.10, workingMgdl = 38.018, stressFloorMgdl = 60.0)
        assertEquals(1.10, decision.effective, 1e-6)
        assertTrue(decision.applied)
        assertEquals(41.8198, decision.workingAdjustedMgdl!!, 1e-3)
    }

    @Test
    fun `a protective factor never crosses the engine ceiling`() {
        val decision = decide(requested = 1.15, workingMgdl = 290.0, stressFloorMgdl = 60.0)
        assertEquals(300.0, decision.workingAdjustedMgdl!!, 1e-9)
    }

    @Test
    fun `a raising factor lives 15 minutes, a protective one 30, both from the audited tick`() {
        // Raising: age 10.0 min is still valid, 15.04 min is expired.
        val valid = decide(0.85, 60.0, 60.0, tickTimestampMs = 1790022140737L)
        assertFalse(valid.refusedBy.contains("expired"))
        val expired = decide(0.85, 60.0, 60.0, tickTimestampMs = 1790022442300L)
        assertTrue(expired.refusedBy.contains("expired"))

        // Protective: age 19.99 min is still valid, 30.10 min is expired.
        val protectiveValid = decide(1.10, 60.0, 60.0, tickTimestampMs = 1790022739477L)
        assertFalse(protectiveValid.refusedBy.contains("expired"))
        val protectiveExpired = decide(1.10, 60.0, 60.0, tickTimestampMs = 1790023346375L)
        assertTrue(protectiveExpired.refusedBy.contains("expired"))

        // A negative age (clock oddity) is also expired.
        val negativeAge = decide(0.85, 60.0, 60.0, tickTimestampMs = auditedTickMs - 1)
        assertTrue(negativeAge.refusedBy.contains("expired"))
    }

    @Test
    fun `every live reason to refuse more insulin is judged, all of them`() {
        val belowAndFalling = decide(
            0.85, 100.0, null,
            safety = clearSafety.copy(bgMgdl = 90.9, deltaMgdl5m = -7.28, shortAvgDeltaMgdl5m = -7.0),
        )
        assertTrue(belowAndFalling.refusedBy.containsAll(listOf("bg_below_120", "bg_falling")))

        val belowAndPostHypo = decide(
            0.85, 100.0, null,
            safety = clearSafety.copy(bgMgdl = 77.7, deltaMgdl5m = 1.79, postHypoActive = true, minBg75mMgdl = 66.1),
        )
        assertTrue(belowAndPostHypo.refusedBy.containsAll(listOf("bg_below_120", "post_hypo")))
        assertFalse(belowAndPostHypo.refusedBy.contains("bg_falling"))

        val predictedLowOnly = decide(
            0.85, 100.0, null,
            safety = clearSafety.copy(bgMgdl = 140.7, deltaMgdl5m = 1.56, minPredBgMgdl = 41.2, minPredThresholdMgdl = 70.0),
        )
        assertEquals(listOf("predicted_low"), predictedLowOnly.refusedBy)

        val noisy = decide(0.85, 100.0, null, safety = clearSafety.copy(cgmNoise = 3.0))
        assertEquals(listOf("cgm_noise"), noisy.refusedBy)

        val exercising = decide(0.85, 100.0, null, safety = clearSafety.copy(exerciseLockout = true))
        assertEquals(listOf("exercise"), exercising.refusedBy)

        val fallingByShortAvg = decide(
            0.85, 100.0, null,
            safety = clearSafety.copy(deltaMgdl5m = -1.0, shortAvgDeltaMgdl5m = -0.5),
        )
        assertEquals(listOf("bg_falling"), fallingByShortAvg.refusedBy)

        val notFalling = decide(
            0.85, 100.0, null,
            safety = clearSafety.copy(deltaMgdl5m = -1.0, shortAvgDeltaMgdl5m = 0.5),
        )
        assertTrue(notFalling.refusedBy.isEmpty()) { notFalling.refusedBy.toString() }
    }

    @Test
    fun `the live rules never block the protective direction`() {
        val decision = decide(
            1.10, 60.0, 60.0,
            safety = clearSafety.copy(bgMgdl = 90.9, deltaMgdl5m = -7.28, shortAvgDeltaMgdl5m = -7.0),
        )
        assertEquals(1.10, decision.effective, 1e-6)
        assertTrue(decision.applied)
    }

    @Test
    fun `key off never applies, but the shadow effective value is what key on would give`() {
        val decision = decide(requested = 0.85, workingMgdl = 37.17, stressFloorMgdl = 30.0, keyOn = false)
        assertFalse(decision.applied)
        assertEquals(0.85, decision.effective, 1e-4)
        assertTrue(decision.refusedBy.contains("shadow"))
    }

    @Test
    fun `a missing prediction refuses more insulin, it does not allow it`() {
        // The ring is empty after the plugin is rebuilt, and the proposal is process-global, so the
        // loop can hold a factor with no previous tick to check it against.
        val noMinPred = decide(0.85, 100.0, null, safety = clearSafety.copy(minPredBgMgdl = null))
        assertEquals(listOf("no_prediction"), noMinPred.refusedBy)
        assertEquals(1.0, noMinPred.effective, 1e-9)
        assertFalse(noMinPred.applied)

        val noThreshold = decide(0.85, 100.0, null, safety = clearSafety.copy(minPredThresholdMgdl = null))
        assertEquals(listOf("no_prediction"), noThreshold.refusedBy)

        val both = decide(
            0.85, 100.0, null,
            safety = clearSafety.copy(minPredBgMgdl = null, minPredThresholdMgdl = null),
        )
        assertEquals(listOf("no_prediction"), both.refusedBy)
    }

    @Test
    fun `a proposal that outlived its ring is refused, not applied`() {
        // The shape of a plugin re-instantiation: the cache still holds the proposal, the ring is
        // empty, so every field the previous tick would have filled is null.
        val emptyRing = AuditorTickRing()
        val previous = emptyRing.latest()
        val safety = clearSafety.copy(
            minPredBgMgdl = previous?.minPredBgMgdl,
            minPredThresholdMgdl = previous?.hypoThresholdMgdl,
        )
        val decision = decide(0.85, 100.0, null, safety = safety)
        assertTrue(decision.refusedBy.contains("no_prediction"))
        assertEquals(1.0, decision.effective, 1e-9)
        assertFalse(decision.applied)
    }

    @Test
    fun `an unknown glucose history refuses more insulin`() {
        // `minBgInLastMinutes` answers 200 when the bucketed table is empty or only gap-filled. The
        // gate reads the nullable variant instead, so "no history" cannot read as "no low".
        val decision = decide(0.85, 100.0, null, safety = clearSafety.copy(minBg75mMgdl = null))
        assertEquals(listOf("no_glucose_history"), decision.refusedBy)
        assertFalse(decision.applied)
    }

    @Test
    fun `a missing input never blocks the protective direction`() {
        val decision = decide(
            1.10, 38.018, null,
            safety = clearSafety.copy(minPredBgMgdl = null, minPredThresholdMgdl = null, minBg75mMgdl = null),
        )
        assertEquals(1.10, decision.effective, 1e-6)
        assertTrue(decision.applied)
    }

    @Test
    fun `the early decision never claims applied, because the tick may still end before the apply step`() {
        val early = AuditorProfileFactorGate.evaluateIsf(
            proposal = proposal(0.85),
            keyOn = true,
            tickTimestampMs = auditedTickMs,
            safety = clearSafety,
        )
        assertEquals(0.85, early.effective, 1e-9)
        assertFalse(early.applied)
        // Only the apply step, which runs when the sensitivity is final, may say true.
        val applied = AuditorProfileFactorGate.applyIsfFloor(
            early,
            AuditorIsfRaw(workingMgdl = 37.17, stressFloorMgdl = 30.0, profileStaticMgdl = 60.0),
            keyOn = true,
        )
        assertTrue(applied.applied)
    }

    @Test
    fun `half the profile ISF is a floor even when the stress floor key is off`() {
        // No stress floor at all. The profile-relative floor is 0.5 x 60 = 30, so 33 x 0.85 = 28.05
        // is not allowed and the factor is partly absorbed.
        val decision = decide(requested = 0.85, workingMgdl = 33.0, stressFloorMgdl = null)
        assertEquals(30.0, decision.workingAdjustedMgdl!!, 1e-9)
        assertEquals(30.0, decision.lowerBoundMgdl!!, 1e-9)
        assertEquals(30.0 / 33.0, decision.effective, 1e-9)
        assertTrue(decision.refusedBy.contains("profile_floor"))
    }

    @Test
    fun `an unknown profile ISF refuses more insulin, because the floor cannot be computed`() {
        val early = AuditorProfileFactorGate.evaluateIsf(
            proposal = proposal(0.85),
            keyOn = true,
            tickTimestampMs = auditedTickMs,
            safety = clearSafety,
        )
        val decision = AuditorProfileFactorGate.applyIsfFloor(
            early,
            AuditorIsfRaw(workingMgdl = 37.17, stressFloorMgdl = null, profileStaticMgdl = null),
            keyOn = true,
        )
        assertEquals(1.0, decision.effective, 1e-9)
        assertFalse(decision.applied)
        assertTrue(decision.refusedBy.contains("no_profile_static"))
        assertEquals(37.17, decision.workingAdjustedMgdl!!, 1e-9)

        // The protective direction does not need the floor and is still allowed.
        val protectiveEarly = AuditorProfileFactorGate.evaluateIsf(
            proposal = proposal(1.10),
            keyOn = true,
            tickTimestampMs = auditedTickMs,
            safety = clearSafety,
        )
        val protective = AuditorProfileFactorGate.applyIsfFloor(
            protectiveEarly,
            AuditorIsfRaw(workingMgdl = 37.17, stressFloorMgdl = null, profileStaticMgdl = null),
            keyOn = true,
        )
        assertTrue(protective.applied)
    }

    @Test
    fun `a factor of exactly 1 point 0 changes nothing, bit for bit`() {
        val value = 38.01810620968392
        assertEquals(value, AuditorProfileFactorGate.scaleIsf(value, 1.0, 60.0), 0.0)
        assertEquals(value.toRawBits(), AuditorProfileFactorGate.scaleIsf(value, 1.0, 60.0).toRawBits())

        val neutral = AuditorProfileFactorGate.evaluateIsf(
            proposal = null,
            keyOn = true,
            tickTimestampMs = auditedTickMs,
            safety = clearSafety,
        )
        val floored = AuditorProfileFactorGate.applyIsfFloor(
            neutral,
            AuditorIsfRaw(workingMgdl = value, stressFloorMgdl = 60.0, profileStaticMgdl = 60.0),
            keyOn = true,
        )
        assertFalse(floored.applied)
        assertEquals(value, floored.workingAdjustedMgdl!!, 0.0)
    }
}
