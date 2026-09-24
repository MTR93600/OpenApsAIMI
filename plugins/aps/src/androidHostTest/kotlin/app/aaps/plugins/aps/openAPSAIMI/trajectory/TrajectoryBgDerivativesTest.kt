package app.aaps.plugins.aps.openAPSAIMI.trajectory

import app.aaps.core.data.iob.InMemoryGlucoseValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Synthetic CGM series → δ / accel shape (RFC C.3).
 */
class TrajectoryBgDerivativesTest {

    @Test
    fun `linear fall 10 mg per 5 min yields negative delta per 5 min`() {
        val series = fiveMinuteSeries(startMgdl = 180.0, steps = 5, slopePer5Min = -10.0)
        val tMid = series[2].timestamp
        val d = TrajectoryBgDerivatives.deltaAt(tMid, series)
        assertEquals(-10.0, d, 0.01)
    }

    @Test
    fun `flat glucose yields zero delta`() {
        val series = fiveMinuteSeries(startMgdl = 120.0, steps = 4, slopePer5Min = 0.0)
        val d = TrajectoryBgDerivatives.deltaAt(series[2].timestamp, series)
        assertEquals(0.0, d, 0.01)
    }

    @Test
    fun `rising glucose yields positive delta`() {
        val series = fiveMinuteSeries(startMgdl = 100.0, steps = 4, slopePer5Min = 6.0)
        val d = TrajectoryBgDerivatives.deltaAt(series[2].timestamp, series)
        assertEquals(6.0, d, 0.01)
    }

    @Test
    fun `acceleration positive when fall is slowing`() {
        // Slopes: -10, then -5 per 5 min → delta goes from -10 toward -5 → positive accel at first interior point
        val series = variableSlopeSeries(listOf(100.0, 90.0, 85.0))
        val t1 = series[1].timestamp
        val d1 = TrajectoryBgDerivatives.deltaAt(t1, series)
        val d2 = TrajectoryBgDerivatives.deltaAt(series[2].timestamp, series)
        assertEquals(-10.0, d1, 0.05)
        assertEquals(-5.0, d2, 0.05)
        val a1 = TrajectoryBgDerivatives.accelAt(t1, series)
        assertTrue(a1 > 0.0, "expected accel > 0 when |delta| decreases, got $a1")
    }

    private fun fiveMinuteSeries(
        startMgdl: Double,
        steps: Int,
        slopePer5Min: Double,
    ): List<InMemoryGlucoseValue> {
        val t0 = 1_700_000_000_000L
        return List(steps) { i ->
            InMemoryGlucoseValue(
                timestamp = t0 + i * 5 * 60_000L,
                value = startMgdl + i * slopePer5Min,
            )
        }
    }

    private fun variableSlopeSeries(valuesMgdl: List<Double>): List<InMemoryGlucoseValue> {
        val t0 = 1_700_000_000_000L
        return valuesMgdl.mapIndexed { i, mg ->
            InMemoryGlucoseValue(
                timestamp = t0 + (i * 5 * 60_000L),
                value = mg,
            )
        }
    }
}
