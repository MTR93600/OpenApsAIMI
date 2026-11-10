package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoMealPlateauBrakeTest {
    @Test
    fun `undeclared auto meal plateau keeps only gradual correction basal`() {
        val decision = evaluate(listOf(120.0, 119.7, 119.4, 119.0, 116.5, 114.5, 112.5))
        assertTrue(decision.active)
        assertTrue(decision.blockSmb)
        assertEquals(0.95, decision.maxBasalUph!!, 1e-9)
    }

    @Test
    fun `renewed delayed wave releases immediately`() {
        assertFalse(evaluate(listOf(123.0, 121.4, 120.6, 120.0, 117.5, 115.5, 113.5)).active)
    }

    @Test
    fun `flat glucose without prior rise is ignored`() {
        assertFalse(evaluate(listOf(110.0, 110.0, 110.1, 110.0, 110.0, 110.1, 110.0)).active)
    }

    @Test
    fun `declared meal bypasses brake`() {
        assertFalse(evaluate(
            listOf(120.0, 119.7, 119.4, 119.0, 116.5, 114.5, 112.5),
            explicitMeal = true,
        ).active)
    }

    @Test
    fun `positive COB bypasses brake`() {
        assertFalse(evaluate(
            listOf(120.0, 119.7, 119.4, 119.0, 116.5, 114.5, 112.5),
            cob = 10.0,
        ).active)
    }

    @Test
    fun `active insulin already covering target removes correction excess`() {
        val decision = AutoMealPlateauBrake.evaluate(
            bgNewestFirst = listOf(120.0, 119.7, 119.4, 119.0, 116.5, 114.5, 112.5),
            requestedBasalUph = 2.1,
            profileBasalUph = 0.7,
            targetBgMgdl = 100.0,
            isfMgdlPerU = 40.0,
            iobU = 0.6,
            cobG = 0.0,
            explicitMealMode = false,
        )
        assertTrue(decision.active)
        assertEquals(0.7, decision.maxBasalUph!!, 1e-9)
    }

    private fun evaluate(values: List<Double>, cob: Double = 0.0, explicitMeal: Boolean = false) =
        AutoMealPlateauBrake.evaluate(
            bgNewestFirst = values,
            requestedBasalUph = 2.1,
            profileBasalUph = 0.7,
            targetBgMgdl = 100.0,
            isfMgdlPerU = 40.0,
            iobU = 0.0,
            cobG = cob,
            explicitMealMode = explicitMeal,
        )
}
