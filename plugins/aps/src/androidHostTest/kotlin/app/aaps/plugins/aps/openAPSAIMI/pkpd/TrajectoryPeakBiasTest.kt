package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetrics
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryModulation
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrajectoryPeakBiasTest {

    @Test
    fun `null analysis returns zero`() {
        assertEquals(0.0, TrajectoryPeakBias.minutesNudge(null, 45, 0.0), 0.001)
    }

    @Test
    fun `high cob vetoes`() {
        val a = dummyAnalysis(
            curvature = 0.1,
            convergenceVelocity = -0.5,
            coherence = 0.2,
            energyBalance = 1.0,
            openness = 0.8,
        )
        assertEquals(0.0, TrajectoryPeakBias.minutesNudge(a, 45, 15.0), 0.001)
    }

    @Test
    fun `diverging low coherence returns positive nudge`() {
        val a = dummyAnalysis(
            curvature = 0.1,
            convergenceVelocity = -0.5,
            coherence = 0.4,
            energyBalance = 1.0,
            openness = 0.6,
        )
        val n = TrajectoryPeakBias.minutesNudge(a, 45, 5.0)
        assertEquals(true, n > 0.0)
        assertEquals(true, n <= 4.0)
    }

    @Test
    fun `converging coherent returns negative nudge`() {
        val a = dummyAnalysis(
            curvature = 0.1,
            convergenceVelocity = 0.4,
            coherence = 0.7,
            energyBalance = 1.0,
            openness = 0.2,
        )
        val n = TrajectoryPeakBias.minutesNudge(a, 50, 0.0)
        assertEquals(true, n < 0.0)
        assertEquals(true, n >= -4.0)
    }

    @Test
    fun `outside bolus age window returns zero`() {
        val a = dummyAnalysis(
            curvature = 0.1,
            convergenceVelocity = -0.5,
            coherence = 0.4,
            energyBalance = 1.0,
            openness = 0.6,
        )
        assertEquals(0.0, TrajectoryPeakBias.minutesNudge(a, 20, 0.0), 0.001)
        assertEquals(0.0, TrajectoryPeakBias.minutesNudge(a, 120, 0.0), 0.001)
        assertEquals(0.0, TrajectoryPeakBias.minutesNudge(a, 29, 5.0), 0.001)
    }

    private fun dummyAnalysis(
        curvature: Double,
        convergenceVelocity: Double,
        coherence: Double,
        energyBalance: Double,
        openness: Double,
    ): TrajectoryAnalysis {
        val metrics = TrajectoryMetrics(
            curvature = curvature,
            convergenceVelocity = convergenceVelocity,
            coherence = coherence,
            energyBalance = energyBalance,
            openness = openness,
        )
        return TrajectoryAnalysis(
            classification = TrajectoryType.UNCERTAIN,
            metrics = metrics,
            modulation = TrajectoryModulation.NEUTRAL,
            warnings = emptyList(),
            stableOrbitDistance = 0.0,
            predictedConvergenceTime = null,
        )
    }
}
