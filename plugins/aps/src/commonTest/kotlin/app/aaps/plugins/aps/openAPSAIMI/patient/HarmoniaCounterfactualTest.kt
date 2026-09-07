package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.basal.BasalChannelSafetyGuards
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Specification locks for [HarmoniaCounterfactual].
 *
 * Two of these are real locks on a **measured** defect, not just a restatement of the code:
 *
 *  - `blockedReductionKeepsTheSameProposal` writes down that 221 of the 629 refusals were refusals
 *    of a `PROTECTIVE_REDUCTION`. An over-dose guard refusing an under-dose cannot be repaired by
 *    telling Harmonia about the guard, and the counterfactual must say so instead of pretending a
 *    gain is available there.
 *  - `verdictMirrorsBasalChannelSafetyGuards` compares the mirror with the real guard call for call
 *    over the whole truth table. It is the only thing that stops the two drifting apart.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `da9bc789ce` (file SHA `f89412ab72`, unchanged on tip
 * `c5db5a0333`). Study source set: [HarmoniaCounterfactual] is commonMain and has no mocks, so the
 * tests live in `commonTest` (`kotlin.test`) — same layout as [app.aaps.plugins.aps.openAPSAIMI.ISF.DynIsfCacheTest],
 * not `androidHostTest`. The name HarmoniaDecisionEngine in the sibling inert lock is the real
 * `internal object` in [HarmoniaDecision]; it is not [HarmoniaHarmonizer].
 */
class HarmoniaCounterfactualTest {

    private val profileBasal = 1.00

    /** Builds a production record with only what the counterfactual reads. */
    private fun production(
        requestedRateUph: Double?,
        runtimeBlocker: String?,
        sourceAction: HarmoniaAction?,
        appliedRateUph: Double? = null,
    ) = HarmoniaProductionDecision(
        timestampMs = 0L,
        mode = if (runtimeBlocker == null) HarmoniaProductionMode.READY else HarmoniaProductionMode.BLOCKED,
        selectedForProduction = runtimeBlocker == null,
        requestedRateUph = requestedRateUph,
        boundedRateUph = requestedRateUph,
        appliedRateUph = appliedRateUph,
        appliedDurationMin = 30,
        runtimeBlocker = runtimeBlocker,
        safetyBlockers = emptyList(),
        sourceAction = sourceAction,
        branch = "TEST",
        reason = "test",
    )

    private val activeVerdict = HarmoniaSafetyVerdict(
        criticalSafetyZeroed = true,
        contextSuppressSmb = false,
        mealModeActive = false,
        guardsEnabled = true,
    )

    /**
     * The measured defect. Harmonia asked for 0.60 U/h under a 1.00 U/h profile — that is less
     * insulin, not more — and the over-dose guard refused it.
     *
     * Two things must be true. Knowing the verdict changes nothing, because Harmonia was already
     * standing down. And the stake is **negative**: the refusal put the pump back on the profile
     * basal, so it added insulin the tick had decided not to give.
     */
    @Test
    fun blockedReductionKeepsTheSameProposal() {
        val outcome = HarmoniaCounterfactual.evaluate(
            simulation = null,
            production = production(
                requestedRateUph = 0.60,
                runtimeBlocker = "smb_zeroed_by_safety",
                sourceAction = HarmoniaAction.PROTECTIVE_REDUCTION,
                appliedRateUph = profileBasal,
            ),
            verdict = activeVerdict,
            profileBasalUph = profileBasal,
            appliedRateUph = profileBasal,
            appliedDurationMin = 30,
        )

        assertEquals(HarmoniaCounterfactualRule.BLOCKED_REDUCTION, outcome.rule)
        assertFalse(outcome.changesProposal)
        assertFalse(outcome.requestWasEscalation)
        assertEquals(HarmoniaAction.PROTECTIVE_REDUCTION, outcome.counterfactualAction)
        assertEquals(0.60, outcome.counterfactualBasalUph!!, 1e-9)
        assertTrue(outcome.blockStakeU!! < 0.0, "refusing an under-dose adds insulin, so the stake is negative")
        assertEquals(-0.20, outcome.blockStakeU!!, 1e-9)
        assertEquals(0.40, outcome.appliedGapUph!!, 1e-9)
    }

    /**
     * The one case where telling Harmonia would change the proposal: it asked for more than the
     * profile while a safety rule had already zeroed the SMB. Knowing that, it stands down to the
     * profile basal by itself, and the stake is positive because the refusal removed insulin.
     */
    @Test
    fun escalationStandsDownWhenVerdictKnown() {
        val outcome = HarmoniaCounterfactual.evaluate(
            simulation = null,
            production = production(
                requestedRateUph = 1.40,
                runtimeBlocker = "smb_zeroed_by_safety",
                sourceAction = HarmoniaAction.BASAL_FIRST,
                appliedRateUph = profileBasal,
            ),
            verdict = activeVerdict,
            profileBasalUph = profileBasal,
            appliedRateUph = profileBasal,
            appliedDurationMin = 30,
        )

        assertEquals(HarmoniaCounterfactualRule.WOULD_STAND_DOWN, outcome.rule)
        assertTrue(outcome.changesProposal)
        assertTrue(outcome.requestWasEscalation)
        assertEquals(HarmoniaAction.OBSERVE, outcome.counterfactualAction)
        assertEquals(profileBasal, outcome.counterfactualBasalUph!!, 1e-9)
        assertTrue(outcome.blockStakeU!! > 0.0)
        assertEquals(0.20, outcome.blockStakeU!!, 1e-9)
    }

    /**
     * A refusal that did not come from the basal safety guard must not be charged to it. Otherwise
     * the count of "refusals the guard caused" is inflated by every other gate in the tick.
     */
    @Test
    fun nonSafetyBlockerIsNotAttributedToTheGuard() {
        val outcome = HarmoniaCounterfactual.evaluate(
            simulation = null,
            production = production(
                requestedRateUph = 1.40,
                runtimeBlocker = "smb_authority_active",
                sourceAction = HarmoniaAction.BASAL_FIRST,
                appliedRateUph = profileBasal,
            ),
            verdict = activeVerdict,
            profileBasalUph = profileBasal,
            appliedRateUph = profileBasal,
            appliedDurationMin = 30,
        )

        assertEquals(HarmoniaCounterfactualRule.BLOCKED_NON_SAFETY, outcome.rule)
        assertFalse(outcome.changesProposal)
        assertEquals(HarmoniaAction.BASAL_FIRST, outcome.counterfactualAction)
    }

    /** No blocker at all: nothing to explain, the counterfactual is the identity. */
    @Test
    fun noBlockerIsTheIdentity() {
        val outcome = HarmoniaCounterfactual.evaluate(
            simulation = null,
            production = production(
                requestedRateUph = 1.40,
                runtimeBlocker = null,
                sourceAction = HarmoniaAction.BASAL_FIRST,
                appliedRateUph = 1.40,
            ),
            verdict = activeVerdict,
            profileBasalUph = profileBasal,
            appliedRateUph = 1.40,
            appliedDurationMin = 30,
        )

        assertEquals(HarmoniaCounterfactualRule.NO_VERDICT, outcome.rule)
        assertFalse(outcome.changesProposal)
        assertEquals(1.40, outcome.counterfactualBasalUph!!, 1e-9)
    }

    /**
     * Anti-drift lock. The mirror in [HarmoniaSafetyVerdict] and the real guard in
     * [BasalChannelSafetyGuards] must agree on all sixteen input combinations. If someone changes
     * the guard and forgets the mirror, the counterfactual would start explaining refusals with a
     * rule the loop no longer uses, and this test fails first.
     */
    @Test
    fun verdictMirrorsBasalChannelSafetyGuards() {
        val flags = listOf(false, true)
        var checked = 0
        for (guardsEnabled in flags) {
            for (criticalSafetyZeroed in flags) {
                for (contextSuppressSmb in flags) {
                    for (mealModeActive in flags) {
                        val expected = BasalChannelSafetyGuards.shouldBlockBasalFirst(
                            guardsEnabled = guardsEnabled,
                            criticalSafetyZeroed = criticalSafetyZeroed,
                            contextSuppressSmb = contextSuppressSmb,
                            mealModeActive = mealModeActive,
                        )
                        val mirrored = HarmoniaSafetyVerdict(
                            criticalSafetyZeroed = criticalSafetyZeroed,
                            contextSuppressSmb = contextSuppressSmb,
                            mealModeActive = mealModeActive,
                            guardsEnabled = guardsEnabled,
                        ).wouldBlockBasalFirst
                        assertEquals(
                            expected,
                            mirrored,
                            "mirror drifted for guards=$guardsEnabled critical=$criticalSafetyZeroed " +
                                "context=$contextSuppressSmb meal=$mealModeActive",
                        )
                        checked++
                    }
                }
            }
        }
        assertEquals(16, checked)
        assertFalse(HarmoniaSafetyVerdict.UNKNOWN.wouldBlockBasalFirst)
    }
}
