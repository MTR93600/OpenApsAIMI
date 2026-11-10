package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientEventMemory
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        assertEquals(null, evaluation.proposal)
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
        assertEquals(null, evaluation.proposal)
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
        assertEquals(null, withoutCrash.proposal)

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

    @Test
    fun deltaBuilder_postHypoModerate_movesMaxSmbDownOneLadderRung() {
        val preferences = mockk<Preferences>()
        every { preferences.get(DoubleKey.OApsAIMIMaxSMB) } returns 1.30
        every { preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB) } returns 1.60
        every { preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor) } returns 1.20
        every { preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU) } returns 2.0
        every { preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor) } returns 0.75
        every { preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold) } returns 0.75
        every { preferences.get(DoubleKey.OApsAIMISmbTailDamping) } returns 0.85
        every { preferences.get(DoubleKey.OApsAIMISmbExerciseDamping) } returns 0.60
        every { preferences.get(DoubleKey.OApsAIMISmbLateFatDamping) } returns 0.70
        every { preferences.get(DoubleKey.OApsAIMILunchFactor) } returns 1.0
        every { preferences.get(DoubleKey.OApsAIMIDinnerFactor) } returns 1.0
        every { preferences.get(app.aaps.core.keys.BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled) } returns true
        every { preferences.get(app.aaps.core.keys.BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled) } returns false
        every { preferences.get(DoubleKey.AimiTubeAggressiveness) } returns 1.0
        every { preferences.get(DoubleKey.AimiTubeHypoFloorMgdl) } returns 80.0

        val plan = TpoDeltaBuilder.buildPlan(
            proposal = TpoProposal(
                packId = TpoPackId.POST_HYPO_RECOVERY,
                tier = TuningStepTier.MODERATE,
                algoConfidence = 0.8,
                reasonCodes = listOf("TEST"),
            ),
            preferences = preferences,
            hypoLoad = 0.2,
            t3cBrittle = false,
        )
        val maxSmbChange = plan.changes.first { it.key == DoubleKey.OApsAIMIMaxSMB }
        assertEquals(1.30, maxSmbChange.oldValue)
        assertEquals(1.00, maxSmbChange.newValue)
        assertTrue(plan.changes.isNotEmpty())
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
