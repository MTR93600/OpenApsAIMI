package app.aaps.ui.compose.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals

class GlassMetricValueTest {

    @Test
    fun preservesAvailableTextAndUsesLocalizedUnavailableLabelInsteadOfBlank() {
        assertEquals("1.25 U", glassMetricDisplayText("1.25 U", "Unavailable"))
        assertEquals("Unavailable", glassMetricDisplayText("", "Unavailable"))
        assertEquals("Unavailable", glassMetricDisplayText("   ", "Unavailable"))
    }
}
