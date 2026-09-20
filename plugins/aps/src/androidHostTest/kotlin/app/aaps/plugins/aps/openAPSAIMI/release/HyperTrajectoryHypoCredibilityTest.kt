package app.aaps.plugins.aps.openAPSAIMI.release

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HyperTrajectoryHypoCredibilityTest {

    @Test
    fun minPred39_at_bg243_not_credible() {
        assertFalse(
            HyperTrajectoryHypoCredibility.isMinPredictedCredible(
                bgMgdl = 243.0,
                minPredictedBgMgdl = 39.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
            ),
        )
    }

    @Test
    fun highFlatBg_minPred39_not_credible_even_without_hyper_tier() {
        assertFalse(
            HyperTrajectoryHypoCredibility.isMinPredictedCredible(
                bgMgdl = 180.0,
                minPredictedBgMgdl = 39.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                tier = HyperSeverityTier.OFF,
                deltaMgdlPer5 = 0.5,
            ),
        )
        assertTrue(
            HyperTrajectoryHypoCredibility.isNumericFloorArtefactOnPlateau(
                bgMgdl = 180.0,
                minPredictedBgMgdl = 39.0,
                deltaMgdlPer5 = 0.5,
            ),
        )
    }

    @Test
    fun fallingHard_floor_still_treated_as_possible_hypo_signal() {
        // Hard fall: Wave1/H2 plateau artefact does not apply — leave to drop-distance rules.
        assertFalse(
            HyperTrajectoryHypoCredibility.isNumericFloorArtefactOnPlateau(
                bgMgdl = 180.0,
                minPredictedBgMgdl = 39.0,
                deltaMgdlPer5 = -4.0,
            ),
        )
    }

    @Test
    fun sanitize_uplifts_eventual_when_best_high() {
        val (predicted, eventual) = HyperTrajectoryHypoCredibility.sanitizeTerminalsForHypoGuard(
            bgMgdl = 243.0,
            predictedBgMgdl = 39.0,
            eventualBgMgdl = 39.0,
            minPredictedBgMgdl = 39.0,
            targetBgMgdl = 100.0,
            highBgPreferenceMgdl = 140.0,
            scenarioBestTerminalMgdl = 320.0,
        )
        assertTrue(eventual > 150.0)
        assertTrue(predicted > 150.0)
    }

    @Test
    fun sanitize_plateau_floor_lifts_to_soft_pathmin() {
        val (predicted, eventual) = HyperTrajectoryHypoCredibility.sanitizeTerminalsForHypoGuard(
            bgMgdl = 200.0,
            predictedBgMgdl = 39.0,
            eventualBgMgdl = 39.0,
            minPredictedBgMgdl = 39.0,
            targetBgMgdl = 100.0,
            highBgPreferenceMgdl = 140.0,
            scenarioBestTerminalMgdl = null,
            deltaMgdlPer5 = 1.0,
        )
        assertTrue(predicted >= 80.0)
        assertTrue(eventual >= 80.0)
    }
}
