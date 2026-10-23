package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Governance of the basal learner: when it holds back, how hard, and how it lets go again.
 *
 * The first four tests are a port of `BasalNeuralLearnerGovernanceTest` from `dev_OAPSAIMI`. dev used
 * mockk for the preferences, the storage and the logger; here they are [MapPreferences],
 * [InMemoryAimiStorage] and [NoOpAapsLogger], so the test runs on every target.
 *
 * The other tests pin two governance fixes:
 * - the severe tier counts the **realised** outcome. `bgAfter` is `rT.eventualBG`, which floors at
 *   39 mg/dL as an artefact; such a sample with a normal real outcome is not a severe hypo.
 * - under HOLD_CONSERVATIVE the decay works on the distance from 1.0. A factor below 1.0 moves up
 *   toward 1.0, not down toward the floor.
 */
class BasalNeuralLearnerGovernanceTest {

    private val preferences = MapPreferences()
    private val learner = BasalNeuralLearner(preferences, InMemoryAimiStorage(), NoOpAapsLogger).also {
        it.resetGovernanceStateForTesting()
    }

    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 1e-6) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")
    }

    private fun basalFactor(): Double =
        learner.getUniversalBasalDecision(bg = 120.0, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 45.0, iob = 0.5).rawValue

    private fun aggressivenessFactor(): Double =
        learner.getT3cAdaptiveDecision(bg = 120.0, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 45.0, iob = 0.5).rawMultiplier

    /** Turns on both heuristics, so [BasalNeuralLearner.updateLearning] moves the two factors. */
    private fun enableHeuristics() {
        preferences.booleans[BooleanKey.OApsAIMIT3cBrittleMode] = true
        preferences.booleans[BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled] = true
    }

    /** One learning tick. The governance window gets one sample without an outcome, so it is not scored. */
    private fun learnTick(bgBefore: Double, deltaMgDl: Double) {
        learner.updateLearning(
            bgBefore = bgBefore,
            bgAfter = bgBefore + deltaMgDl,
            basalDelivered = 1.0,
            targetBg = 100.0,
            accel = 0.0,
            duraISFminutes = 0.0,
            duraISFaverage = 0.0,
            iob = 1.0,
            loopDeltaMgDl5m = deltaMgDl,
        )
    }

    // ---- port of the dev tests ----

    @Test
    fun hysteresisKeepsHoldWhenHypoRateIsBetweenExitAndEnterThresholds() {
        learner.replaceGovernanceSamplesForTesting(List(60) { 75.0 })
        learner.evaluateGovernance()
        val snap1 = learner.getGovernanceSnapshot()
        assertEquals(BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE, snap1.action)
        assertFalse(snap1.hypoHoldLatched)

        // 9/60 = 15 % hypo: below the 20 % enter, above the 12 % exit, so HOLD must stay, latched.
        val mixed = List(51) { 120.0 } + List(9) { 75.0 }
        learner.replaceGovernanceSamplesForTesting(mixed)
        learner.evaluateGovernance()
        val snap2 = learner.getGovernanceSnapshot()
        assertEquals(BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE, snap2.action)
        assertTrue(snap2.hypoHoldLatched)

        learner.replaceGovernanceSamplesForTesting(List(60) { 120.0 })
        learner.evaluateGovernance()
        assertEquals(BasalNeuralLearner.GovernanceAction.KEEP, learner.getGovernanceSnapshot().action)
        assertFalse(learner.getGovernanceSnapshot().hypoHoldLatched)
    }

    @Test
    fun severeHypoInWindowUsesStricterBasalFloorThanRateOnlyHypoPressure() {
        learner.replaceGovernanceSamplesForTesting(List(60) { 75.0 })
        learner.evaluateGovernance()
        assertEquals(0.85, learner.getGovernanceSnapshot().activeBasalFloor)

        learner.replaceGovernanceSamplesForTesting(listOf(65.0) + List(59) { 120.0 })
        learner.evaluateGovernance()
        assertEquals(0.88, learner.getGovernanceSnapshot().activeBasalFloor)
    }

    @Test
    fun noisyHypoSamplesReduceGovernanceHypoRateVersusRawFraction() {
        val bg = List(15) { 75.0 } + List(45) { 120.0 }
        val noises = List(15) { 3.0 } + List(45) { 0.0 }
        learner.replaceGovernanceSamplesForTesting(bg, sensorNoises = noises)
        learner.evaluateGovernance()
        val snap = learner.getGovernanceSnapshot()
        assertNear(0.25, snap.hypoRate, 0.001)
        assertTrue(snap.hypoRateGovernance < snap.hypoRate)
        assertTrue(snap.hypoRateGovernance < 0.20)
        assertEquals(BasalNeuralLearner.GovernanceAction.KEEP, snap.action)
        assertTrue(snap.meanGovernanceWeight < 1.0)
    }

    @Test
    fun shortHorizonMinPredAboveHypoBandLowersAdjustedHypoRateAndCanExitHoldPressure() {
        val bg = List(15) { 75.0 } + List(45) { 120.0 }
        learner.replaceGovernanceSamplesForTesting(bg)
        learner.evaluateGovernance()
        val withoutPred = learner.getGovernanceSnapshot()
        assertNear(withoutPred.hypoRateGovernance, withoutPred.hypoGovernanceAdjusted, 0.001)
        assertNear(0.0, withoutPred.anticipationRelief, 0.001)
        assertEquals(BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE, withoutPred.action)

        val preds = List(42) { Double.NaN } + List(18) { 130.0 }
        learner.replaceGovernanceSamplesForTesting(bg, shortMinPredBgs = preds)
        learner.evaluateGovernance()
        val withPred = learner.getGovernanceSnapshot()
        assertTrue(withPred.anticipationRelief > 0.8)
        assertTrue(withPred.hypoGovernanceAdjusted < withPred.hypoRateGovernance)
        assertEquals(BasalNeuralLearner.GovernanceAction.KEEP, withPred.action)
    }

    // ---- severe tier scores the realised outcome ----

    @Test
    fun eventualBgFloorArtefactWithNormalOutcomeIsNotSevere() {
        // Five ticks where the prediction was clipped to 39 mg/dL, but the BG really reached was 110.
        val outcomes = List(60) { 110.0 }
        val eventual = List(55) { 110.0 } + List(5) { 39.0 }
        learner.replaceGovernanceSamplesForTesting(outcomes, eventualBgs = eventual)
        learner.evaluateGovernance()

        val snap = learner.getGovernanceSnapshot()
        assertEquals(0, snap.severeHypoCount)
        assertEquals(BasalNeuralLearner.GovernanceAction.KEEP, snap.action)
        assertNull(snap.activeBasalFloor)
    }

    @Test
    fun realSevereOutcomeIsStillCountedWhenThePredictionWasNormal() {
        // The opposite case: a normal prediction, but the BG really went to 60 mg/dL.
        val outcomes = listOf(60.0) + List(59) { 120.0 }
        val eventual = List(60) { 120.0 }
        learner.replaceGovernanceSamplesForTesting(outcomes, eventualBgs = eventual)
        learner.evaluateGovernance()

        val snap = learner.getGovernanceSnapshot()
        assertEquals(1, snap.severeHypoCount)
        assertEquals(BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE, snap.action)
        assertEquals(0.88, snap.activeBasalFloor)
    }

    // ---- HOLD decay works on the distance from 1.0 ----

    @Test
    fun holdMovesFactorsBelowOneUpTowardOne() {
        enableHeuristics()
        // BG 85 falling 20 mg/dL: basal factor 1.00 -> 0.98, aggressiveness 1.00 -> 0.90.
        learnTick(bgBefore = 85.0, deltaMgDl = -20.0)
        assertNear(0.98, basalFactor())
        assertNear(0.90, aggressivenessFactor())

        // Rate tier HOLD (75 mg/dL is a hypo, not a severe one), no anticipation relief.
        learner.replaceGovernanceSamplesForTesting(List(60) { 75.0 })
        learner.evaluateGovernance()
        assertEquals(BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE, learner.getGovernanceSnapshot().action)

        // 1 + (0.98 - 1) * 0.98 and 1 + (0.90 - 1) * 0.97. The old `x * decay` gave 0.9604 and 0.873.
        assertNear(0.9804, basalFactor())
        assertNear(0.903, aggressivenessFactor())
    }

    @Test
    fun manyHoldTicksBringFactorsBelowOneCloseToOneAndNeverPastIt() {
        enableHeuristics()
        learnTick(bgBefore = 85.0, deltaMgDl = -20.0)
        learner.replaceGovernanceSamplesForTesting(List(60) { 75.0 })

        var previousBasal = basalFactor()
        var previousAgg = aggressivenessFactor()
        repeat(200) {
            learner.evaluateGovernance()
            val basal = basalFactor()
            val agg = aggressivenessFactor()
            assertTrue(basal >= previousBasal, "basal factor went down: $previousBasal -> $basal")
            assertTrue(agg >= previousAgg, "aggressiveness went down: $previousAgg -> $agg")
            assertTrue(basal <= 1.0 && agg <= 1.0, "a factor passed 1.0: basal $basal agg $agg")
            previousBasal = basal
            previousAgg = agg
        }
        assertTrue(previousBasal > 0.999, "basal factor still $previousBasal")
        assertTrue(previousAgg > 0.999, "aggressiveness still $previousAgg")
    }

    @Test
    fun holdStillMovesFactorsAboveOneDownTowardOne() {
        enableHeuristics()
        // BG 200 not falling: basal factor 1.00 -> 1.01, aggressiveness 1.00 -> 1.05.
        learnTick(bgBefore = 200.0, deltaMgDl = 0.0)
        assertNear(1.01, basalFactor())
        assertNear(1.05, aggressivenessFactor())

        learner.replaceGovernanceSamplesForTesting(List(60) { 75.0 })
        learner.evaluateGovernance()

        assertNear(1.0098, basalFactor())
        assertNear(1.0485, aggressivenessFactor())
    }
}
