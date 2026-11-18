package app.aaps.ui.compose.dashboard.glass

import kotlin.test.Test
import kotlin.test.assertEquals

/** Negative IOB gets room below the 0 line instead of being drawn on it. */
class IobChartScaleTest {

    @Test
    fun negativeIobGoesBelowZero() {
        // The field case: the chip showed -1.24 U while the line sat on 0
        assertEquals(-2f to 3f, iobChartScale(listOf(-1.24f, 0.5f, 2.3f)))
    }

    @Test
    fun positiveIobKeepsTheOldScale() {
        assertEquals(0f to 3f, iobChartScale(listOf(0.2f, 2.3f)))
        assertEquals(0f to 3f, iobChartScale(listOf(0.0f, 2.0f)))
    }

    @Test
    fun noDataGivesOneUnit() {
        assertEquals(0f to 1f, iobChartScale(emptyList()))
        assertEquals(0f to 1f, iobChartScale(listOf(0f)))
    }

    @Test
    fun onlyNegativeIobStillShowsTheZeroLine() {
        assertEquals(-1f to 1f, iobChartScale(listOf(-0.4f, -0.1f)))
    }
}
