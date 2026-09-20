package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.plugins.aps.openAPSAIMI.prediction.minPredictedAcrossCurves
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PredictionCurveMathTest {

    @Test
    fun nullPredictionsReturnsNull() {
        assertNull(minPredictedAcrossCurves(null))
    }

    @Test
    fun emptySeriesReturnsNull() {
        assertNull(minPredictedAcrossCurves(Predictions()))
    }

    @Test
    fun takesMinimumOfPerSeriesMins() {
        val p = Predictions(
            IOB = listOf(100, 90, 80),
            COB = listOf(95, 70),
            UAM = listOf(120, 110),
            ZT = listOf(88, 85),
        )
        assertEquals(70.0, minPredictedAcrossCurves(p)!!, 0.01)
    }

    @Test
    fun ignoresNullSeriesLists() {
        val p = Predictions(
            IOB = listOf(100),
            COB = null,
            UAM = listOf(50),
            ZT = null,
        )
        assertEquals(50.0, minPredictedAcrossCurves(p)!!, 0.01)
    }
}
