package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.core.interfaces.aps.GlucoseStatusAutoIsf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DynIsfTrajectoryTuningTest {

    @Test
    fun trajectoryRiseFallScores_strongRiseDominates() {
        val gs = GlucoseStatusAutoIsf(
            glucose = 180.0,
            delta = 14.0,
            shortAvgDelta = 12.0,
            longAvgDelta = 8.0,
            deltaPn = 12.0,
            bgAcceleration = 5.0,
            corrSqu = 0.72,
        )
        val (rise, fall) = trajectoryRiseFallScores(gs)
        assertTrue(rise >= 0.85, "rise=$rise")
        assertTrue(fall < 0.35, "fall=$fall")
    }

    @Test
    fun trajectoryRiseFallScores_flatTrajectoryLowScores() {
        val gs = GlucoseStatusAutoIsf(
            glucose = 110.0,
            delta = 0.5,
            shortAvgDelta = 0.4,
            longAvgDelta = 0.3,
            deltaPn = 0.2,
            bgAcceleration = 0.1,
            corrSqu = 0.4,
        )
        val (rise, fall) = trajectoryRiseFallScores(gs)
        assertTrue(rise < 0.15, "rise=$rise")
        assertTrue(fall < 0.15, "fall=$fall")
    }

    @Test
    fun trajectoryRiseFallScores_accelerationIgnoredWhenParabolaWeak() {
        val gs = GlucoseStatusAutoIsf(
            glucose = 140.0,
            delta = 2.0,
            shortAvgDelta = 2.0,
            longAvgDelta = 1.0,
            deltaPn = 1.0,
            bgAcceleration = 20.0,
            corrSqu = 0.35,
        )
        val (rise, _) = trajectoryRiseFallScores(gs)
        assertTrue(rise < 0.35, "rise should not be driven by accel alone when corr low rise=$rise")
    }
}
