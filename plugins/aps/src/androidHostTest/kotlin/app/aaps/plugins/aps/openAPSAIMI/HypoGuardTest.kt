package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.safety.HypoGuard
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HypoGuardTest {

    @Test
    fun strongNowBlocksRegardlessOfPredictions() {
        assertTrue(
            HypoGuard.isBelowHypoThreshold(
                bgNow = 60.0,
                predicted = 120.0,
                eventual = 120.0,
                hypo = 70.0,
                delta = 0.0,
            )
        )
    }

    @Test
    fun risingFastBypassesLowPredictions() {
        assertFalse(
            HypoGuard.isBelowHypoThreshold(
                bgNow = 100.0,
                predicted = 50.0,
                eventual = 50.0,
                hypo = 70.0,
                delta = 4.0,
            )
        )
    }

    @Test
    fun strongFutureWhenBothPredictionsAtOrBelowFloor() {
        assertTrue(
            HypoGuard.isBelowHypoThreshold(
                bgNow = 100.0,
                predicted = 60.0,
                eventual = 60.0,
                hypo = 70.0,
                delta = 0.0,
            )
        )
    }

    @Test
    fun fastFallWithRapidDropAndPredictionAtOrBelowHypo() {
        assertTrue(
            HypoGuard.isBelowHypoThreshold(
                bgNow = 200.0,
                predicted = 68.0,
                eventual = 150.0,
                hypo = 70.0,
                delta = -2.5,
            )
        )
    }

    @Test
    fun risingModerateBypassesStrongFutureButNotWhenNoFastFall() {
        assertFalse(
            HypoGuard.isBelowHypoThreshold(
                bgNow = 100.0,
                predicted = 60.0,
                eventual = 60.0,
                hypo = 70.0,
                delta = 2.0,
            )
        )
    }

    @Test
    fun highBgWithSafePredictionsIsNotBelowHypo() {
        assertFalse(
            HypoGuard.isBelowHypoThreshold(
                bgNow = 180.0,
                predicted = 160.0,
                eventual = 150.0,
                hypo = 70.0,
                delta = 0.0,
            )
        )
    }
}
