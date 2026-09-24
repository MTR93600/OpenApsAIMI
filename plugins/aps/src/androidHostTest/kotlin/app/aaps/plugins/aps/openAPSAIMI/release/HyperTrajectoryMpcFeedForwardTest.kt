package app.aaps.plugins.aps.openAPSAIMI.release

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HyperTrajectoryMpcFeedForwardTest {

    @Test
    fun anticipatory_raises_ra_floor() {
        val classification = HyperSeverityClassifier.classify(
            HyperSeverityClassifier.Input(
                bgMgdl = 152.0,
                targetBgMgdl = 100.0,
                highBgPreferenceMgdl = 140.0,
                deltaMgdlPer5 = 23.0,
                shortAvgDeltaMgdlPer5 = 20.0,
                combinedDeltaMgdlPer5 = 23.0,
                floorTerminalMgdl = 39.0,
                bestTerminalMgdl = 401.0,
                tdd24hU = 55.0,
                dwellAboveHighBgMinutes = 5,
                trajectoryType = null,
            ),
        )
        val hints = HyperTrajectoryMpcFeedForward.hintsFromClassification(
            classification = classification,
            bgMgdl = 152.0,
            bestTerminalMgdl = 401.0,
            isNight = false,
            exerciseLockout = false,
        )
        val blended = HyperTrajectoryMpcFeedForward.blendEstimatedRa(0.2, hints)
        assertTrue(blended >= 0.85)
        assertTrue(hints.tierOrdinal >= HyperSeverityTier.ANTICIPATORY.ordinal)
        assertTrue(hints.projectionLeadMgdl > 200.0)
    }
}
