package app.aaps.core.ui.compose.preference

import app.aaps.core.keys.UnitType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slider step of [sliderDecimalsAndStep]. A key with no unit must never get a step of 1 on a
 * small range, or one touch moves a factor from one end of its range to the other.
 */
class SliderStepTest {

    @Test
    fun `a factor between 0 and 1 moves by 0_01`() {
        assertEquals(2 to 0.01, sliderDecimalsAndStep(UnitType.NONE, 0.0, 1.0))
    }

    @Test
    fun `a very small range moves by 0_001`() {
        assertEquals(3 to 0.001, sliderDecimalsAndStep(UnitType.NONE, 0.03, 0.12))
    }

    @Test
    fun `a range up to 25 moves by 0_1`() {
        assertEquals(1 to 0.1, sliderDecimalsAndStep(UnitType.NONE, 4.0, 24.0))
        assertEquals(1 to 0.1, sliderDecimalsAndStep(UnitType.NONE, 0.1, 10.0))
    }

    @Test
    fun `a wide range moves by 1`() {
        assertEquals(0 to 1.0, sliderDecimalsAndStep(UnitType.NONE, 1.0, 150.0))
    }

    @Test
    fun `the limits of each band belong to the smaller step`() {
        assertEquals(3 to 0.001, sliderDecimalsAndStep(UnitType.NONE, 0.0, 0.15))
        assertEquals(2 to 0.01, sliderDecimalsAndStep(UnitType.NONE, 0.0, 1.5))
        assertEquals(1 to 0.1, sliderDecimalsAndStep(UnitType.NONE, 0.0, 25.0))
    }

    @Test
    fun `a key with a unit keeps the step of its unit`() {
        assertEquals(1 to 0.1, sliderDecimalsAndStep(UnitType.INSULIN, 0.05, 15.0))
        assertEquals(2 to 0.01, sliderDecimalsAndStep(UnitType.DOUBLE_2, 0.30, 1.0))
    }
}
