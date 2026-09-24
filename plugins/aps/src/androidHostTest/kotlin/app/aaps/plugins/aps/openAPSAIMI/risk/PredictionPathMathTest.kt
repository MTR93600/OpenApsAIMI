package app.aaps.plugins.aps.openAPSAIMI.risk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PredictionPathMathTest {

    @Test
    fun rawPathMinDetectsNumericFloorHit() {
        val bounds = PredictionPathMath.boundsFromRawSeries(listOf(120.0, 80.0, 35.0, 50.0))
        assertEquals(35.0, bounds.pathMinRawMgdl!!, 0.001)
        assertEquals(39.0, bounds.pathMinClampedMgdl!!, 0.001)
        assertTrue(bounds.pathMinHitNumericFloor)
    }

    @Test
    fun compositeMinUsesTerminalValuesNotPathMin() {
        val composite = PredictionPathMath.compositeMinMgdl(
            bg = 118.0,
            predTerminal = 125.0,
            eventualTerminal = 130.0,
        )
        assertEquals(118.0, composite, 0.001)
    }
}
