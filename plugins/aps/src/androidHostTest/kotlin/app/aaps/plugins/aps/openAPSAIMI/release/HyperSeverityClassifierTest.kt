package app.aaps.plugins.aps.openAPSAIMI.release

import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HyperSeverityClassifierTest {

    private fun baseInput(
        bg: Double = 226.0,
        bestT: Double = 401.0,
        floorT: Double = 147.0,
        delta: Double = 20.0,
        shortDelta: Double = 18.0,
        dwell: Int = 15,
        trajectory: TrajectoryType? = TrajectoryType.TIGHT_SPIRAL,
    ) = HyperSeverityClassifier.Input(
        bgMgdl = bg,
        targetBgMgdl = 100.0,
        highBgPreferenceMgdl = 140.0,
        deltaMgdlPer5 = delta,
        shortAvgDeltaMgdlPer5 = shortDelta,
        combinedDeltaMgdlPer5 = delta,
        floorTerminalMgdl = floorT,
        bestTerminalMgdl = bestT,
        tdd24hU = 55.0,
        dwellAboveHighBgMinutes = dwell,
        trajectoryType = trajectory,
    )

    @Test
    fun thomas1251_established_not_deep_on_strong_projection() {
        val out = HyperSeverityClassifier.classify(baseInput())
        assertEquals(HyperSeverityTier.ESTABLISHED, out.tier)
    }

    @Test
    fun thomas1226_anticipatory_at_bg152() {
        val out = HyperSeverityClassifier.classify(
            baseInput(bg = 152.0, delta = 23.0, dwell = 5, trajectory = TrajectoryType.OPEN_DIVERGING),
        )
        assertTrue(
            out.tier == HyperSeverityTier.ANTICIPATORY || out.tier == HyperSeverityTier.EMERGING,
            "tier was ${out.tier}",
        )
    }

    @Test
    fun plateau_deep_when_best_no_longer_leads() {
        val out = HyperSeverityClassifier.classify(
            baseInput(bg = 253.0, bestT = 226.0, floorT = 180.0, delta = 2.0, dwell = 45),
        )
        assertEquals(HyperSeverityTier.DEEP, out.tier)
        assertTrue(!out.plateauSustain)
    }

    @Test
    fun prolonged_hyper_plateau_sustain_established_not_off() {
        val out = HyperSeverityClassifier.classify(
            baseInput(
                bg = 256.0,
                bestT = 228.0,
                floorT = 200.0,
                delta = 0.5,
                shortDelta = 0.4,
                dwell = 90,
                trajectory = TrajectoryType.TIGHT_SPIRAL,
            ),
        )
        assertEquals(HyperSeverityTier.ESTABLISHED, out.tier)
        assertTrue(out.plateauSustain)
    }

    @Test
    fun stacking_spiral_downgrades_without_projection() {
        val out = HyperSeverityClassifier.classify(
            baseInput(
                bg = 155.0,
                bestT = 158.0,
                floorT = 150.0,
                delta = 1.0,
                dwell = 0,
                trajectory = TrajectoryType.TIGHT_SPIRAL,
            ),
        )
        assertEquals(HyperSeverityTier.OFF, out.tier)
    }

    @Test
    fun user_override_established_dev() {
        val out = HyperSeverityClassifier.classify(
            baseInput(bg = 200.0, bestT = 350.0, delta = 22.0).copy(establishedDevOverrideMgdl = 90.0),
        )
        assertTrue(out.tier.isReleaseEligible, "tier was ${out.tier}")
        assertTrue(out.establishedDevMgdl >= 90.0)
    }
}
