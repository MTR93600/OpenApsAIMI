package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.aps.Predictions
import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionSanityTest {

    @Test
    fun hyperglycemiaKeepsRisingPrediction() {
        val series = Predictions(IOB = listOf(240, 250, 260, 270, 280, 290))
        val result = sanitizePredictionValues(
            bg = 240.0,
            delta = 2f,
            predBgRaw = 260.0,
            eventualBgRaw = 270.0,
            series = series
        )

        assertEquals(260.0, result.predBg, 0.1)
        assertEquals(270.0, result.eventualBg, 0.1)
        assertEquals("ok", result.label)
    }

    @Test
    fun unrealisticDropIsClampedForSafety() {
        val series = Predictions(IOB = listOf(240, 80, 70, 60, 55))
        val result = sanitizePredictionValues(
            bg = 240.0,
            delta = 2f,
            predBgRaw = 80.0,
            eventualBgRaw = 70.0,
            series = series
        )

        assertEquals(252.0, result.predBg, 0.1)
        assertEquals(252.0, result.eventualBg, 0.1)
    }

    @Test
    fun genuineLowPredictionIsPreserved() {
        val series = Predictions(IOB = listOf(94, 80, 60, 50, 42, 39))
        val result = sanitizePredictionValues(
            bg = 94.0,
            delta = -2f,
            predBgRaw = 39.0,
            eventualBgRaw = 39.0,
            series = series
        )

        assertEquals(39.0, result.predBg, 0.1)
        assertEquals(39.0, result.eventualBg, 0.1)
    }
}
