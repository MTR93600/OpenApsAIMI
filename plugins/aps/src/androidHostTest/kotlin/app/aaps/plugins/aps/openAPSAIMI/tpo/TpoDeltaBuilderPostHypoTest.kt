package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The delta-builder case of the reference `TpoTriggerEngineTest` (`dev_OAPSAIMI`).
 * It needs a stubbed [Preferences], and Mockito is JVM only, so it lives in `androidHostTest`.
 * The trigger-engine cases of the same reference file are in `commonTest`.
 */
class TpoDeltaBuilderPostHypoTest {

    @Test
    fun deltaBuilder_postHypoModerate_movesMaxSmbDownOneLadderRung() {
        val preferences = mock<Preferences>()
        whenever(preferences.get(DoubleKey.OApsAIMIMaxSMB)).thenReturn(1.30)
        whenever(preferences.get(DoubleKey.OApsAIMIHighBGMaxSMB)).thenReturn(1.60)
        whenever(preferences.get(DoubleKey.OApsAIMIPriorityMaxIobFactor)).thenReturn(1.20)
        whenever(preferences.get(DoubleKey.OApsAIMIPriorityMaxIobExtraU)).thenReturn(2.0)
        whenever(preferences.get(DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor)).thenReturn(0.75)
        whenever(preferences.get(DoubleKey.OApsAIMIRedCarpetRestoreThreshold)).thenReturn(0.75)
        whenever(preferences.get(DoubleKey.OApsAIMISmbTailDamping)).thenReturn(0.85)
        whenever(preferences.get(DoubleKey.OApsAIMISmbExerciseDamping)).thenReturn(0.60)
        whenever(preferences.get(DoubleKey.OApsAIMISmbLateFatDamping)).thenReturn(0.70)
        whenever(preferences.get(DoubleKey.OApsAIMILunchFactor)).thenReturn(1.0)
        whenever(preferences.get(DoubleKey.OApsAIMIDinnerFactor)).thenReturn(1.0)
        whenever(preferences.get(BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled)).thenReturn(true)
        whenever(preferences.get(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled)).thenReturn(false)
        whenever(preferences.get(DoubleKey.AimiTubeAggressiveness)).thenReturn(1.0)
        whenever(preferences.get(DoubleKey.AimiTubeHypoFloorMgdl)).thenReturn(80.0)

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
}
