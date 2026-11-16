package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TpoTriggerEngineTest {

    @Test
    fun postHypoTrigger_whenReboundGuard() {
        val input = baseInput().copy(
            reboundGuardActive = true,
            minBgLookback75m = 68.0,
            patientModeName = PatientMode.POST_HYPO_RECOVERY.name,
            patientModeConfidence = 0.72,
        )
        val evaluation = TpoTriggerEngine.evaluate(input, TpoEpisodeLedger(), null, emptyMap())
        assertNotNull(evaluation.proposal)
        assertEquals(TpoPackId.POST_HYPO_RECOVERY, evaluation.proposal?.packId)
    }

    @Test
    fun postHypo_blocked_during_meal_rise_when_patient_mode_still_post_hypo() {
        val input = baseInput().copy(
            cobGrams = 12.0,
            deltaMgdl5m = 4.0,
            bgMgdl = 155.0,
            mealProb = 0.72,
            minBgLookback75m = 98.0,
            postHypoReboundProb = 0.82,
            patientModeName = PatientMode.POST_HYPO_RECOVERY.name,
            patientModeConfidence = 0.78,
            causalDominantName = CausalStateId.POST_HYPO_RECOVERY.name,
            causalDominantConfidence = 0.70,
        )
        val ledger = TpoEpisodeLedger(
            episodes = listOf(
                TpoEpisode(
                    type = TpoEpisodeType.HYPO,
                    startedAtMs = input.nowMs - 3L * 60L * 60L * 1000L,
                    peakAtMs = input.nowMs - 3L * 60L * 60L * 1000L,
                    bgExtremeMgdl = 62.0,
                    sequenceIndex = 1,
                ),
            ),
        )
        val evaluation = TpoTriggerEngine.evaluate(input, ledger, null, emptyMap())
        assertNull(evaluation.proposal)
        assertEquals("meal_guard", evaluation.blockedReason)
    }

    @Test
    fun postHypo_not_triggered_when_hypo_context_is_stale() {
        val input = baseInput().copy(
            cobGrams = 0.0,
            deltaMgdl5m = 0.5,
            bgMgdl = 118.0,
            minBgLookback75m = 98.0,
            postHypoReboundProb = 0.80,
            patientModeName = PatientMode.POST_HYPO_RECOVERY.name,
            patientModeConfidence = 0.75,
            causalDominantName = CausalStateId.POST_HYPO_RECOVERY.name,
            causalDominantConfidence = 0.68,
        )
        val ledger = TpoEpisodeLedger(
            episodes = listOf(
                TpoEpisode(
                    type = TpoEpisodeType.HYPO,
                    startedAtMs = input.nowMs - 9L * 60L * 60L * 1000L,
                    peakAtMs = input.nowMs - 9L * 60L * 60L * 1000L,
                    bgExtremeMgdl = 63.0,
                    sequenceIndex = 1,
                ),
            ),
        )
        val evaluation = TpoTriggerEngine.evaluate(input, ledger, null, emptyMap())
        assertNull(evaluation.proposal)
        assertEquals("no_trigger", evaluation.blockedReason)
    }

    @Test
    fun postHypo_triggered_when_recent_hypo_in_ledger_and_not_meal_rise() {
        val input = baseInput().copy(
            cobGrams = 0.0,
            deltaMgdl5m = 1.0,
            bgMgdl = 112.0,
            minBgLookback75m = 98.0,
            postHypoReboundProb = 0.74,
            patientModeName = PatientMode.POST_HYPO_RECOVERY.name,
            patientModeConfidence = 0.72,
        )
        val ledger = TpoEpisodeLedger(
            episodes = listOf(
                TpoEpisode(
                    type = TpoEpisodeType.HYPO,
                    startedAtMs = input.nowMs - 2L * 60L * 60L * 1000L,
                    peakAtMs = input.nowMs - 2L * 60L * 60L * 1000L,
                    bgExtremeMgdl = 64.0,
                    sequenceIndex = 1,
                ),
            ),
        )
        val evaluation = TpoTriggerEngine.evaluate(input, ledger, null, emptyMap())
        assertNotNull(evaluation.proposal)
        assertEquals(TpoPackId.POST_HYPO_RECOVERY, evaluation.proposal?.packId)
    }

    @Test
    fun exhaustedTrigger_requiresLedgerCrash() {
        val input = baseInput().copy(
            eventMemory = PatientEventMemory(
                postHyperExhaustionScore = 0.72,
                correctionFragilityScore = 0.71,
            ),
        )
        val withoutCrash = TpoTriggerEngine.evaluate(input, TpoEpisodeLedger(), null, emptyMap())
        assertNull(withoutCrash.proposal)

        val ledger = TpoEpisodeLedger(
            episodes = listOf(
                TpoEpisode(
                    type = TpoEpisodeType.HYPER_CRASH,
                    startedAtMs = input.nowMs - 60_000L,
                    peakAtMs = input.nowMs - 60_000L,
                    bgExtremeMgdl = 68.0,
                    sequenceIndex = 1,
                ),
            ),
        )
        val withCrash = TpoTriggerEngine.evaluate(input, ledger, null, emptyMap())
        assertEquals(TpoPackId.EXHAUSTED_RECOVERY, withCrash.proposal?.packId)
    }

    /**
     * The 14:14 tick of 2026-11-16, as it reached the TPO: FCL had ended 25 min earlier (COB 0, no
     * carbs entered), glucose 158 and falling after the meal peak, and one tick of rebound guard
     * echoed into all four post-hypo reasons at confidence 1.0.
     */
    private fun tick1414(declaredMealEpisode: Boolean) = baseInput().copy(
        bgMgdl = 158.5,
        deltaMgdl5m = -9.4,
        cobGrams = 0.0,
        minBgLookback75m = 74.3,
        mealProb = 0.35,
        postHypoReboundProb = 0.93,
        patientModeName = PatientMode.POST_HYPO_RECOVERY.name,
        patientModeConfidence = 1.0,
        causalDominantName = CausalStateId.POST_HYPO_RECOVERY.name,
        causalDominantConfidence = 0.98,
        reboundGuardActive = true,
        declaredMealEpisode = declaredMealEpisode,
    )

    @Test
    fun case_20261116_declared_meal_blocks_post_hypo() {
        val evaluation = TpoTriggerEngine.evaluate(tick1414(declaredMealEpisode = true), TpoEpisodeLedger(), null, emptyMap())
        assertNull(evaluation.proposal)
        assertEquals("meal_guard", evaluation.blockedReason)
    }

    @Test
    fun case_20261116_without_meal_is_moderate_not_strong() {
        // 74.3 is under the 75 rebound line but not a real hypo: four echoed reasons do not make STRONG.
        val evaluation = TpoTriggerEngine.evaluate(tick1414(declaredMealEpisode = false), TpoEpisodeLedger(), null, emptyMap())
        assertEquals(TpoPackId.POST_HYPO_RECOVERY, evaluation.proposal?.packId)
        assertEquals(TuningStepTier.MODERATE, evaluation.proposal?.tier)
    }

    @Test
    fun real_hypo_still_gives_strong() {
        val input = tick1414(declaredMealEpisode = false).copy(minBgLookback75m = 66.0)
        val evaluation = TpoTriggerEngine.evaluate(input, TpoEpisodeLedger(), null, emptyMap())
        assertEquals(TuningStepTier.STRONG, evaluation.proposal?.tier)
    }

    @Test
    fun hypo_now_passes_the_meal_guard() {
        val input = tick1414(declaredMealEpisode = true).copy(bgMgdl = 64.0, deltaMgdl5m = -3.0, minBgLookback75m = 64.0)
        val evaluation = TpoTriggerEngine.evaluate(input, TpoEpisodeLedger(), null, emptyMap())
        assertEquals(TpoPackId.POST_HYPO_RECOVERY, evaluation.proposal?.packId)
    }

    @Test
    fun declared_meal_blocks_every_pack() {
        val sleepy = baseInput().copy(
            patientModeName = PatientMode.POOR_SLEEP_DAY.name,
            patientModeConfidence = 0.80,
            sleepDebtScore = 0.80,
            minBgLookback75m = 98.0,
            postHypoReboundProb = 0.0,
        )
        assertEquals(TpoPackId.POOR_SLEEP_WINDOW, TpoTriggerEngine.evaluate(sleepy, TpoEpisodeLedger(), null, emptyMap()).proposal?.packId)
        val duringMeal = TpoTriggerEngine.evaluate(sleepy.copy(declaredMealEpisode = true), TpoEpisodeLedger(), null, emptyMap())
        assertNull(duringMeal.proposal)
        assertEquals("meal_guard", duringMeal.blockedReason)
    }

    private fun baseInput() = TpoTickInput(
        nowMs = 1_700_000_000_000L,
        bgMgdl = 118.0,
        deltaMgdl5m = 5.0,
        cobGrams = 0.0,
        minBgLookback75m = 72.0,
        mealProb = 0.1,
        sleepDebtScore = 0.2,
        thermalRecoveryBurden = 0.1,
        postHypoReboundProb = 0.5,
        patientModeName = PatientMode.STABLE_BASELINE.name,
        patientModeConfidence = 0.5,
        causalDominantName = CausalStateId.UNKNOWN.name,
        causalDominantConfidence = 0.0,
        eventMemory = PatientEventMemory.EMPTY,
        reboundGuardActive = false,
        dawnEndogenousDrive = 0.1,
    )
}
