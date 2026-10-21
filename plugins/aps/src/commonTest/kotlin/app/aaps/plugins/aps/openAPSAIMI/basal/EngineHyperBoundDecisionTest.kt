package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.RT
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Engine hyper bound (shadow first) on the "Slow rise" and "Eventual BG>110" branches.
 *
 * "Slow rise" multiplies by combinedDelta and has no bound (199 U/h seen on 2026-10-20). The stub
 * `calculateBasalRate` returns the multiplier, so the raw rate here is combinedDelta = 15.
 * Bound = profile 0.8 × interpolateBasal(145) = 0.8 × 3.25 = 2.6.
 *
 * Source: `dev_OAPSAIMI` @ `bef0366f4c` `BasalDecisionEngineTest`, the three engine-bound cases. That
 * class is mockk based, so the cases are rebuilt on the shared [MealWindowBasalBoostTest] fixtures with
 * the real [BasalPlanner]. Each case checks that the planner gave no plan, so the engine branches ran
 * as they do with the Android mock (`basalPlanner.plan(any()) returns null`). The current temp is
 * rate 0, as the relaxed Android mock gives.
 */
class EngineHyperBoundDecisionTest {

    private val fixtures = MealWindowBasalBoostTest()

    @BeforeTest
    fun emptyBasalHistory() {
        BasalHistoryUtils.historyProvider = BasalHistoryUtils.EmptyProvider
    }

    /** Same values as the Android `forcedTbrInput(delta)`. */
    private fun forcedTbrInput(delta: Double) = fixtures.lunchInput(
        variableSensitivity = 50.0,
        profileSens = 50.0,
        preFloorCommandedSens = null,
    ).copy(
        lunchTime = false,
        lunchRuntimeMin = 0,
        delta = delta,
        shortAvgDelta = delta,
        longAvgDelta = delta,
        combinedDelta = delta,
        featuresCombinedDelta = delta,
        currentTemp = CurrentTemp(duration = 0, rate = 0.0, minutesrunning = 0),
    )

    private fun slowRiseInput(armed: Boolean) = forcedTbrInput(delta = 15.0)
        .copy(slopeFromMinDeviation = 1.0, bgAcceleration = 2.0, engineHyperBoundArmed = armed)

    private fun decide(input: BasalDecisionEngine.Input): Pair<BasalDecisionEngine.Decision, RT> {
        val rt = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true)
        val decision = fixtures.engine.decide(input, rt, fixtures.noMealOnsetHelpers())
        assertFalse(rt.reason.toString().contains("BasalPlanner"), "the planner must give no plan here: ${rt.reason}")
        return decision to rt
    }

    @Test
    fun slowRiseIsExportedInShadowAndKeepsItsRawRateWhenDisarmed() {
        val (decision, _) = decide(slowRiseInput(armed = false))
        assertEquals(15.0, decision.rate, absoluteTolerance = 1e-9)
        val bound = decision.hyperBound
        assertNotNull(bound)
        assertEquals(EngineHyperRateBound.Branch.SLOW_RISE, bound.branch)
        assertTrue(bound.wouldBind)
        assertFalse(bound.applied)
        assertEquals(2.6, bound.boundUph, absoluteTolerance = 1e-9)
    }

    @Test
    fun slowRiseIsBoundToProfileTimesTheGlucoseFactorWhenArmed() {
        val (decision, rt) = decide(slowRiseInput(armed = true))
        assertEquals(2.6, decision.rate, absoluteTolerance = 1e-9)
        assertTrue(rt.reason.toString().contains("ENGINE_BOUND"), rt.reason.toString())
    }

    /** "Eventual BG>110" at BG 160: the stub gives the factor itself, 4.0, the bound is 0.8 × 4.0 = 3.2. */
    @Test
    fun eventualBranchIsBoundByASingleGlucoseFactorWhenArmed() {
        val input = forcedTbrInput(delta = 3.0).copy(bg = 160.0, engineHyperBoundArmed = true)
        val (decision, _) = decide(input)
        assertEquals(EngineHyperRateBound.Branch.EVENTUAL_BG_110, decision.hyperBound?.branch)
        assertEquals(3.2, decision.rate, absoluteTolerance = 1e-9)
    }
}
