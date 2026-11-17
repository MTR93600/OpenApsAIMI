package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.data.model.TrendArrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EssentialShapeStateTest {

    private fun inputs(
        bg: Double? = 120.0,
        stale: Boolean = false,
        trend: TrendArrow? = TrendArrow.FLAT,
        prediction: Double? = 125.0,
        low: Double = 70.0,
        high: Double = 180.0,
    ) = EssentialInputs(bg, stale, trend, prediction, low, high)

    private fun mood(inputs: EssentialInputs, previous: EssentialMood? = null) = EssentialShapeState.classify(inputs, previous)

    @Test
    fun all_eight_moods_are_reachable() {
        assertEquals(EssentialMood.STABLE, mood(inputs()))
        assertEquals(EssentialMood.RISING, mood(inputs(trend = TrendArrow.FORTY_FIVE_UP)))
        assertEquals(EssentialMood.FALLING, mood(inputs(trend = TrendArrow.SINGLE_DOWN)))
        assertEquals(EssentialMood.HYPER, mood(inputs(bg = 243.0)))
        assertEquals(EssentialMood.HYPO, mood(inputs(bg = 58.0)))
        assertEquals(EssentialMood.TRAJECTORY_HYPER, mood(inputs(bg = 150.0, prediction = 196.0)))
        assertEquals(EssentialMood.TRAJECTORY_HYPO, mood(inputs(bg = 96.0, prediction = 64.0)))
        assertEquals(EssentialMood.NO_DATA, mood(inputs(bg = null)))
        assertEquals(EssentialMood.NO_DATA, mood(inputs(stale = true)))
    }

    @Test
    fun the_user_thresholds_are_used_not_70_and_180() {
        assertEquals(EssentialMood.HYPO, mood(inputs(bg = 78.0, low = 80.0)))
        assertEquals(EssentialMood.HYPER, mood(inputs(bg = 165.0, high = 160.0)))
        assertEquals(EssentialMood.STABLE, mood(inputs(bg = 175.0, high = 200.0, prediction = 190.0)))
    }

    @Test
    fun without_a_prediction_there_is_no_trajectory() {
        assertEquals(EssentialMood.STABLE, mood(inputs(bg = 150.0, prediction = null)))
        assertEquals(EssentialMood.RISING, mood(inputs(bg = 150.0, prediction = null, trend = TrendArrow.SINGLE_UP)))
    }

    @Test
    fun a_hypo_ends_only_past_the_margin() {
        assertEquals(EssentialMood.HYPO, mood(inputs(bg = 72.0), previous = EssentialMood.HYPO))
        assertEquals(EssentialMood.STABLE, mood(inputs(bg = 76.0), previous = EssentialMood.HYPO))
        // Without the previous mood the same 72 is in range
        assertEquals(EssentialMood.STABLE, mood(inputs(bg = 72.0)))
    }

    @Test
    fun a_hyper_ends_only_past_the_margin() {
        assertEquals(EssentialMood.HYPER, mood(inputs(bg = 178.0), previous = EssentialMood.HYPER))
        assertEquals(EssentialMood.STABLE, mood(inputs(bg = 174.0), previous = EssentialMood.HYPER))
    }

    @Test
    fun a_trajectory_ends_only_past_the_margin() {
        assertEquals(EssentialMood.TRAJECTORY_HYPER, mood(inputs(bg = 150.0, prediction = 177.0), previous = EssentialMood.TRAJECTORY_HYPER))
        assertEquals(EssentialMood.STABLE, mood(inputs(bg = 150.0, prediction = 174.0), previous = EssentialMood.TRAJECTORY_HYPER))
    }

    @Test
    fun only_high_going_moods_offer_the_boost() {
        assertTrue(EssentialMood.TRAJECTORY_HYPER.offersBoost())
        assertTrue(EssentialMood.RISING.offersBoost())
        assertTrue(EssentialMood.HYPER.offersBoost())
        listOf(EssentialMood.STABLE, EssentialMood.FALLING, EssentialMood.HYPO, EssentialMood.TRAJECTORY_HYPO, EssentialMood.NO_DATA)
            .forEach { assertFalse(it.offersBoost(), it.name) }
    }

    @Test
    fun only_hypo_pulses() {
        EssentialMood.entries.forEach { assertEquals(it == EssentialMood.HYPO, it.shapeParams().pulses, it.name) }
    }
}
