package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.max
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SafetyNet.calculateSafeSmbLimit].
 * Guards the 120 mg/dL boundary, meal-priority ramp toward [maxSmbHigh], and eventual-BG clamp.
 */
class SafetyNetTest {

    private val low = 1.3
    private val high = 5.0
    private val range = high - low

    private fun limit(
        bg: Double,
        targetBg: Double = 100.0,
        eventualBg: Double = 200.0,
        delta: Double = 2.0,
        shortAvgDelta: Double = 2.0,
        maxSmbLow: Double = low,
        maxSmbHigh: Double = high,
        isExplicitUserAction: Boolean = false,
        auditorConfidence: Double? = null,
        mealPriorityContext: Boolean = false
    ): Double = SafetyNet.calculateSafeSmbLimit(
        bg = bg,
        targetBg = targetBg,
        eventualBg = eventualBg,
        delta = delta,
        shortAvgDelta = shortAvgDelta,
        maxSmbLow = maxSmbLow,
        maxSmbHigh = maxSmbHigh,
        isExplicitUserAction = isExplicitUserAction,
        auditorConfidence = auditorConfidence,
        mealPriorityContext = mealPriorityContext
    )

    @Test
    fun `explicit user action returns high max`() {
        assertEquals(high, limit(bg = 90.0, isExplicitUserAction = true), 1e-9)
    }

    @Test
    fun `meal priority below 120 uses 70 percent of low max`() {
        assertEquals(low * 0.70, limit(bg = 119.0, mealPriorityContext = true), 1e-9)
    }

    @Test
    fun `meal priority at 125 uses at least 75 percent of low to high range`() {
        val progress = (125.0 - 120.0) / (170.0 - 120.0)
        val boosted = max(progress, 0.75)
        val expected = low + range * boosted
        assertEquals(expected, limit(bg = 125.0, mealPriorityContext = true), 1e-9)
    }

    @Test
    fun `meal priority at 170 returns high max`() {
        assertEquals(high, limit(bg = 170.0, mealPriorityContext = true), 1e-9)
    }

    @Test
    fun `strict guard below 120 without rocket halves low max`() {
        assertEquals(low * 0.5, limit(bg = 115.0, delta = 2.0, shortAvgDelta = 2.0), 1e-9)
    }

    @Test
    fun `UAM rocket below 120 returns full low max`() {
        // BG must be > target+15 so ZONE 0.5 soft landing does not run before strict guard
        // (e.g. bg=115 target=100 is still "soft landing"; delta=0 would coast and return 1.1× low).
        val bgRocket = 118.0
        assertEquals(low, limit(bg = bgRocket, delta = 7.0, shortAvgDelta = 0.0), 1e-9)
        assertEquals(low, limit(bg = bgRocket, delta = 0.0, shortAvgDelta = 7.0), 1e-9)
    }

    @Test
    fun `buffer 125 with safe eventual interpolates from 120`() {
        val progress = (125.0 - 120.0) / (170.0 - 120.0)
        assertEquals(low + range * progress, limit(bg = 125.0, eventualBg = 125.0), 1e-9)
    }

    @Test
    fun `buffer mid range interpolates linearly`() {
        val bg = 145.0
        val progress = (bg - 120.0) / (170.0 - 120.0)
        assertEquals(low + range * progress, limit(bg = bg, eventualBg = 200.0), 1e-9)
    }

    @Test
    fun `eventual below 120 clamps to low max even when current bg elevated`() {
        assertEquals(low, limit(bg = 150.0, eventualBg = 119.0), 1e-9)
    }

    @Test
    fun `eventual 125 no longer forces low max at bg 150`() {
        val progress = (150.0 - 120.0) / (170.0 - 120.0)
        assertEquals(low + range * progress, limit(bg = 150.0, eventualBg = 125.0), 1e-9)
    }

    @Test
    fun `reactor zone returns high max`() {
        assertEquals(high, limit(bg = 180.0, targetBg = 100.0, eventualBg = 200.0, delta = 3.0), 1e-9)
    }

    @Test
    fun `soft landing before strict guard when coasting just above target`() {
        val target = 100.0
        val bg = 110.0
        assertEquals(
            low * 1.10,
            limit(bg = bg, targetBg = target, eventualBg = 105.0, delta = 0.5, shortAvgDelta = 0.5),
            1e-9
        )
    }

    @Test
    fun `auditor low confidence removes soft landing boost`() {
        val target = 100.0
        assertEquals(
            low,
            limit(
                bg = 110.0,
                targetBg = target,
                eventualBg = 105.0,
                delta = 0.5,
                shortAvgDelta = 0.5,
                auditorConfidence = 0.4
            ),
            1e-9
        )
    }
}
