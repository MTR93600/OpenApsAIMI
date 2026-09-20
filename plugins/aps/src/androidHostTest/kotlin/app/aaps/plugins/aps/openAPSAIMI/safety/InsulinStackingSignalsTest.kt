package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InsulinStackingSignalsTest {

    @Test
    fun eventualDropWhenSixBelowBg() {
        assertTrue(signalEventualDrop(120.0, 113.0))
        assertFalse(signalEventualDrop(120.0, 114.0))
        assertFalse(signalEventualDrop(120.0, null))
    }

    @Test
    fun minPredDropWhenTenBelowBg() {
        assertTrue(signalMinPredDrop(150.0, 139.0))
        assertFalse(signalMinPredDrop(150.0, 140.0))
        assertFalse(signalMinPredDrop(150.0, null))
    }

    @Test
    fun trajectoryStackWhenEnergyAboveTwoAndFinite() {
        assertTrue(signalTrajectoryStack(2.1))
        assertFalse(signalTrajectoryStack(2.0))
        assertFalse(signalTrajectoryStack(1.0))
        assertFalse(signalTrajectoryStack(null))
        assertFalse(signalTrajectoryStack(Double.NaN))
        assertFalse(signalTrajectoryStack(Double.POSITIVE_INFINITY))
    }
}
