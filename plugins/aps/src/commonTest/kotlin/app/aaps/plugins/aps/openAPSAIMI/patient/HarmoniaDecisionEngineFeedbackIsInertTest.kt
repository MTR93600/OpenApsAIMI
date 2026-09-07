package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The inertia lock.
 *
 * [HarmoniaDecisionEnvironment.priorRuntimeBlocker] and
 * [HarmoniaDecisionEnvironment.priorBlockedStreak] are doors opened for the export and for
 * `HarmoniaCounterfactual`. They must carry information **out** of the decision, never into it.
 *
 * This test is a specification lock, not a bug proof: it was green the moment the two fields were
 * added, because nothing reads them. Its whole value is later — the day someone wires the feedback
 * into `buildBlockers` or `chooseActionWithReason`, this test turns red and says so, instead of the
 * change reaching a pump unannounced.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `da9bc789ce`. The type under test is the real
 * [HarmoniaDecisionEngine] object in [HarmoniaDecision], not [HarmoniaHarmonizer]. Study source set:
 * `commonTest` + `kotlin.test`.
 */
class HarmoniaDecisionEngineFeedbackIsInertTest {

    /** The refusal seen in the field, and the number of times it was seen. */
    private val fieldBlocker = "smb_zeroed_by_safety"
    private val fieldStreak = 629

    /**
     * Fifty varied environments, each crossed with three different trees. For every one of them the
     * decision must be identical apart from the `environment` field itself, which of course carries
     * the two new values.
     */
    @Test
    fun priorBlockerDoesNotChangeAnyOutput() {
        val trees = listOf(treeStable(), treeResistant(), treeHypoRisk())
        var compared = 0
        for (seed in 1L..50L) {
            val env = HarmoniaDecisionEngine.randomizedEnvironment(seed)
            val informed = env.copy(
                priorRuntimeBlocker = fieldBlocker,
                priorBlockedStreak = fieldStreak,
            )
            for (tree in trees) {
                val blind = HarmoniaDecisionEngine.evaluate(tree = tree, environment = env)
                val told = HarmoniaDecisionEngine.evaluate(tree = tree, environment = informed)

                assertNotNull(blind, "seed $seed must produce a decision")
                assertNotNull(told, "seed $seed must produce a decision")
                // Everything but the environment must match, field by field: action, eligibility,
                // target basal, target SMB, both factors, caps, blockers, rationale, summary, basis.
                assertEquals(
                    blind.copy(environment = env),
                    told.copy(environment = env),
                    "the prior blocker changed the decision for seed $seed",
                )
                compared++
            }
        }
        assertEquals(150, compared)
    }

    /** The two fields must really travel, otherwise the test above would compare nothing. */
    @Test
    fun priorBlockerIsCarriedButDefaultsToNothing() {
        val env = HarmoniaDecisionEngine.randomizedEnvironment(1L)
        assertNull(env.priorRuntimeBlocker)
        assertEquals(0, env.priorBlockedStreak)

        val told = HarmoniaDecisionEngine.evaluate(
            tree = treeStable(),
            environment = env.copy(priorRuntimeBlocker = fieldBlocker, priorBlockedStreak = fieldStreak),
        )
        assertEquals(fieldBlocker, told?.environment?.priorRuntimeBlocker)
        assertEquals(fieldStreak, told?.environment?.priorBlockedStreak)
    }

    private fun buildTree(state: PatientStateSnapshot) =
        PhysiologicalTreeBuilder.build(
            enabled = true,
            patientState = state,
            patientModeDecision = PatientModeOrchestrator.evaluate(state),
        )

    private fun treeStable() = buildTree(baseState())

    private fun treeResistant() = buildTree(
        baseState().copy(
            phase = PhysiologicalPhase.DAWN_CORTISOL,
            endogenousGlucoseDrive = 0.82,
            transientResistanceProb = 0.70,
            causalPosterior = CausalStatePosterior(
                dawnEndogenousProb = 0.84,
                stressResistanceProb = 0.62,
                dominant = CausalStateId.DAWN_ENDOGENOUS,
                dominantConfidence = 0.84,
                learningQuality = 0.80,
            ),
        ),
    )

    private fun treeHypoRisk() = buildTree(
        baseState().copy(
            postHypoReboundProb = 0.65,
            eventMemory = PatientEventMemory(
                recentHypoLoad = 0.55,
                correctionFragilityScore = 0.40,
            ),
        ),
    )

    private fun baseState(): PatientStateSnapshot =
        PatientStateSnapshot(
            timestampMs = 1_718_000_000_000L,
            phase = PhysiologicalPhase.OFF,
            phaseConfidence = 0.50,
            mealAbsorptionPhase = MealAbsorptionPhase.NONE,
            mealAbsorptionBelief = 0.0,
            mealProb = 0.08,
            endogenousGlucoseDrive = 0.10,
            transientResistanceProb = 0.08,
            sleepDebtScore = 0.0,
            postHypoReboundProb = 0.0,
            sensorConfidence = 0.88,
            causalPosterior = CausalStatePosterior(
                dominant = CausalStateId.UNKNOWN,
                dominantConfidence = 0.0,
                learningQuality = 0.86,
            ),
        )
}
