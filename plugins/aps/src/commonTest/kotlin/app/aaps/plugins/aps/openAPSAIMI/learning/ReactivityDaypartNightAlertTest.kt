package app.aaps.plugins.aps.openAPSAIMI.learning

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The combination used on a day after an elevated night. The night must never choose the direction:
 * only the short-term factor, which follows the last two hours of glucose, may move the result, and
 * only the way it already points.
 */
class ReactivityDaypartNightAlertTest {

    private val tolerance = 1e-12
    private val extra = ReactivityDaypart.NIGHT_ALERT_EXTRA_SHORT_WEIGHT

    @Test
    fun `no extra weight gives exactly the usual combination`() {
        assertEquals(
            ReactivityDaypart.combineFactors(0.92, 0.97, 0.93),
            ReactivityDaypart.combineFactors(0.92, 0.97, 0.93, 0.0),
        )
    }

    @Test
    fun `a neutral short-term factor changes nothing even when the other factors are not neutral`() {
        // The person's real factors on 2026-11-24: global 0.921, segment 0.921. The night alone must
        // not pull this toward 1.0, which would be more insulin chosen by the night.
        assertEquals(
            ReactivityDaypart.combineFactors(0.921, 1.0, 0.921),
            ReactivityDaypart.combineFactors(0.921, 1.0, 0.921, extra),
        )
    }

    @Test
    fun `after highs the alert gives more insulin than usual`() {
        val usual = ReactivityDaypart.combineFactors(1.0, 1.2, 1.0)
        val alert = ReactivityDaypart.combineFactors(1.0, 1.2, 1.0, extra)

        assertEquals(1.06, usual, tolerance)
        assertEquals(1.10, alert, tolerance)
    }

    @Test
    fun `after a low the alert gives less insulin than usual`() {
        val usual = ReactivityDaypart.combineFactors(1.0, 0.85, 1.0)
        val alert = ReactivityDaypart.combineFactors(1.0, 0.85, 1.0, extra)

        assertEquals(0.955, usual, tolerance)
        assertEquals(0.925, alert, tolerance)
    }

    @Test
    fun `the change follows the short-term factor and not the others`() {
        // Global and segment below 1, short-term above: the alert adds, it does not subtract.
        val usual = ReactivityDaypart.combineFactors(0.8, 1.1, 0.8)
        val alert = ReactivityDaypart.combineFactors(0.8, 1.1, 0.8, extra)

        assertEquals(usual + 0.02, alert, tolerance)
    }

    @Test
    fun `the result stays inside the factor bounds`() {
        assertEquals(ReactivityDaypart.FACTOR_MAX, ReactivityDaypart.combineFactors(1.5, 1.5, 1.5, extra))
        assertEquals(ReactivityDaypart.FACTOR_MIN, ReactivityDaypart.combineFactors(0.5, 0.5, 0.5, extra))
    }
}
