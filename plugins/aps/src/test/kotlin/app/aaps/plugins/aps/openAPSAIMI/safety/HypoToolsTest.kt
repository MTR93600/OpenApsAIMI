package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.Assert.assertEquals
import org.junit.Test

class HypoToolsTest {

    @Test
    fun `test calculateMinutesAboveThreshold`() {
        // BG 100, Threshold 70. Diff 30.
        // Slope -2 (dropping 2 per min).
        // Minutes = 30 / 2 = 15.
        assertEquals(15, HypoTools.calculateMinutesAboveThreshold(100.0, -2.0, 70.0))
        
        // Slope positive (rising) -> MAX_VALUE
        assertEquals(Int.MAX_VALUE, HypoTools.calculateMinutesAboveThreshold(100.0, 2.0, 70.0))
    }

    @Test
    fun `test calculateDropPerHour`() {
        // Start 200, End 100. Drop 100.
        // Window 60 min.
        // Drop per hour = 100 * (60/60) = 100.
        val history = listOf(200f, 150f, 100f)
        assertEquals(100f, HypoTools.calculateDropPerHour(history, 60f), 0.01f)
    }
}
