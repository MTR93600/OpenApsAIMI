package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The meal-onset detector of the early-meal forced TBR must be given the real predicted delta.
 *
 * The old call passed the measured 5-minute delta twice, so one noisy step could start the forced
 * basal on its own. `Input.predictedDelta` is the real prediction; `null` keeps the old behaviour,
 * which is what the preference off state does.
 *
 * Source: `origin/dev_OAPSAIMI` @ `dd096225fb` `BasalDecisionEngineTest` forced-TBR case. That test
 * class is mockk based and has no counterpart here, so the case is rebuilt on the shared
 * [MealWindowBasalBoostTest] fixtures, like [StepDataAbsenceBasalTest]. Backtick names omit commas.
 */
class ForcedTbrPredictedDeltaTest {

    private val fixtures = MealWindowBasalBoostTest()

    @BeforeTest
    fun emptyBasalHistory() {
        BasalHistoryUtils.historyProvider = BasalHistoryUtils.EmptyProvider
    }

    /** Runs one decision and returns the predicted delta the detector was handed. */
    private fun predictedDeltaSeenBy(predictedDelta: Double?): Float {
        var seenPredicted: Float? = null
        val helpers = BasalDecisionEngine.Helpers(
            calculateRate = { _, _, mult, _ -> 1.0 * mult },
            calculateBasalRate = { _, _, mult -> 1.0 * mult },
            detectMealOnset = { _, predicted, _, _, _ -> seenPredicted = predicted; false },
            round = { v, _ -> v },
        )
        val input = fixtures.lunchInput(
            variableSensitivity = 50.0,
            profileSens = 50.0,
            preFloorCommandedSens = null,
        ).copy(
            lunchTime = false,
            lunchRuntimeMin = 0,
            predictedDelta = predictedDelta,
        )
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        fixtures.engine.decide(input, rt, helpers)
        return seenPredicted!!
    }

    @Test
    fun `the detector receives the predicted delta when one is given`() {
        assertEquals(0.5f, predictedDeltaSeenBy(0.5), absoluteTolerance = 1e-6f)
    }

    @Test
    fun `the detector falls back to the measured delta when no prediction is given`() {
        // lunchInput uses delta 3.0 on every delta field.
        assertEquals(3.0f, predictedDeltaSeenBy(null), absoluteTolerance = 1e-6f)
    }
}
