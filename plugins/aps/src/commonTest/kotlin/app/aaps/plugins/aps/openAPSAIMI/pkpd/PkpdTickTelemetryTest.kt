package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Step P3: the PK/PD tick telemetry that replaced the peak branch keys, the dominant branch key and
 * the two `PEAK_GOV` log keys. These values were preferences that only the loop wrote. They are now
 * kept in memory, and the PK/PD status card reads them with the 24 h learning summary.
 */
class PkpdTickTelemetryTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    // ---------------------------------------------------------------- learning log

    @Test
    fun `an empty log has no ticks and no steps and no reason`() {
        val summary = PkpdLearningTickLog().summary(now)
        assertEquals(0, summary.learningTicks)
        assertEquals(0, summary.acceptedSteps)
        assertEquals(0, summary.blockedTicks)
        assertEquals(0, summary.blockedPercent)
        assertNull(summary.topBlockReason)
    }

    @Test
    fun `accepted steps and blocked ticks are counted`() {
        val log = PkpdLearningTickLog()
        log.record(now - 3 * hour, accepted = true, blockedBy = null)
        log.record(now - 2 * hour, accepted = true, blockedBy = null)
        log.record(now - 2 * hour, accepted = false, blockedBy = null)
        log.record(now - hour, accepted = false, blockedBy = "causal_unclean")

        val summary = log.summary(now)
        assertEquals(4, summary.learningTicks)
        assertEquals(2, summary.acceptedSteps)
        assertEquals(1, summary.blockedTicks)
        assertEquals(25, summary.blockedPercent)
        assertEquals("causal_unclean", summary.topBlockReason)
    }

    @Test
    fun `ticks older than 24 h are not counted`() {
        val log = PkpdLearningTickLog()
        log.record(now - 25 * hour, accepted = true, blockedBy = null)
        log.record(now - 24 * hour - 1, accepted = false, blockedBy = "curve:recent_rise")
        log.record(now - 23 * hour, accepted = true, blockedBy = null)

        val summary = log.summary(now)
        assertEquals(1, summary.learningTicks)
        assertEquals(1, summary.acceptedSteps)
        assertEquals(0, summary.blockedTicks)
        assertNull(summary.topBlockReason)
    }

    @Test
    fun `the top reason groups a reason by the part before the colon`() {
        val log = PkpdLearningTickLog()
        log.record(now - 5 * hour, accepted = false, blockedBy = "causal_unclean")
        log.record(now - 4 * hour, accepted = false, blockedBy = "causal_unclean")
        log.record(now - 3 * hour, accepted = false, blockedBy = "curve:recent_rise")
        log.record(now - 2 * hour, accepted = false, blockedBy = "curve:meal_belief")
        log.record(now - hour, accepted = false, blockedBy = "curve:declared_meal")

        val summary = log.summary(now)
        assertEquals(5, summary.blockedTicks)
        assertEquals(100, summary.blockedPercent)
        assertEquals("curve", summary.topBlockReason)
    }

    @Test
    fun `a tie between two reasons gives the same answer every time`() {
        val log = PkpdLearningTickLog()
        log.record(now - 2 * hour, accepted = false, blockedBy = "curve:recent_rise")
        log.record(now - hour, accepted = false, blockedBy = "causal_unclean")

        assertEquals("causal_unclean", log.summary(now).topBlockReason)
    }

    @Test
    fun `the blocked percent is rounded`() {
        val log = PkpdLearningTickLog()
        log.record(now - 3 * hour, accepted = false, blockedBy = "causal_unclean")
        log.record(now - 2 * hour, accepted = true, blockedBy = null)
        log.record(now - hour, accepted = true, blockedBy = null)

        assertEquals(33, log.summary(now).blockedPercent)
    }

    @Test
    fun `the log keeps a bounded number of ticks`() {
        val log = PkpdLearningTickLog()
        // A 1-minute CGM runs about 1440 loops a day. Many more ticks than the bound, all in the window.
        repeat(PkpdLearningTickLog.MAX_ENTRIES + 500) { i ->
            log.record(now - 1000L + i % 1000, accepted = true, blockedBy = null)
        }
        assertEquals(PkpdLearningTickLog.MAX_ENTRIES, log.summary(now).learningTicks)
    }

    @Test
    fun `clear forgets every tick`() {
        val log = PkpdLearningTickLog()
        log.record(now - hour, accepted = true, blockedBy = null)
        log.clear()
        assertEquals(0, log.summary(now).learningTicks)
    }

    // ---------------------------------------------------------------- block reason of one learning tick

    @Test
    fun `a gate that blocks is the reason whatever the estimator says`() {
        assertEquals(
            PkPdIntegration.LEARN_BLOCKED_CAUSAL_UNCLEAN,
            PkPdIntegration.learningTickBlockedBy(
                gateBlockedBy = PkPdIntegration.LEARN_BLOCKED_CAUSAL_UNCLEAN,
                accepted = false,
                estimatorReason = PkPdUpdateReason.IOB_TOO_LOW,
            ),
        )
    }

    @Test
    fun `a step the estimator refuses after the gates passed is blocked with the estimator reason`() {
        val expected = mapOf(
            PkPdUpdateReason.WINDOW_OUT_OF_RANGE to "estimator_window_out_of_range",
            PkPdUpdateReason.IOB_TOO_LOW to "estimator_iob_too_low",
            PkPdUpdateReason.CARBS_ACTIVE to "estimator_carbs_active",
            PkPdUpdateReason.EXERCISE to "estimator_exercise",
            PkPdUpdateReason.RISE_TOO_FAST to "estimator_rise_too_fast",
            PkPdUpdateReason.HYPO to "estimator_hypo",
            PkPdUpdateReason.NEAR_HYPO_FALLING to "estimator_near_hypo_falling",
            PkPdUpdateReason.FAST_FALL to "estimator_fast_fall",
        )
        expected.forEach { (reason, blockedBy) ->
            assertEquals(blockedBy, PkPdIntegration.learningTickBlockedBy(null, accepted = false, estimatorReason = reason))
        }
    }

    @Test
    fun `an accepted step is not blocked`() {
        assertNull(PkPdIntegration.learningTickBlockedBy(null, accepted = true, estimatorReason = PkPdUpdateReason.ACCEPTED))
    }

    @Test
    fun `an estimator reason keeps its full name as the top reason`() {
        val log = PkpdLearningTickLog()
        log.record(now - 3 * hour, accepted = false, blockedBy = "estimator_iob_too_low")
        log.record(now - 2 * hour, accepted = false, blockedBy = "estimator_iob_too_low")
        log.record(now - hour, accepted = false, blockedBy = "estimator_carbs_active")

        assertEquals("estimator_iob_too_low", log.summary(now).topBlockReason)
    }

    // ---------------------------------------------------------------- age of the last tick

    @Test
    fun `the kinetics of the last tick are fresh for 15 minutes only`() {
        assertTrue(PkpdTickTelemetry.isFresh(atMs = now - 15 * 60_000L, nowMs = now))
        assertFalse(PkpdTickTelemetry.isFresh(atMs = now - 15 * 60_000L - 1, nowMs = now))
    }

    // ---------------------------------------------------------------- peak governor console echo

    @Test
    fun `a peak governor line is echoed to the console once`() {
        val echo = PeakGovConsoleEcho()
        echo.record("PEAK_GOV: line A")

        assertEquals("PEAK_GOV: line A", echo.lineToEcho())
        assertNull(echo.lineToEcho())

        echo.record("PEAK_GOV: line A")
        assertNull(echo.lineToEcho())
    }

    @Test
    fun `a new peak governor line is echoed again`() {
        val echo = PeakGovConsoleEcho()
        echo.record("PEAK_GOV: line A")
        echo.lineToEcho()
        echo.record("PEAK_GOV: line B")

        assertEquals("PEAK_GOV: line B", echo.lineToEcho())
    }

    @Test
    fun `a blank line resets the echo so the same line shows again later`() {
        val echo = PeakGovConsoleEcho()
        echo.record("PEAK_GOV: line A")
        echo.lineToEcho()
        echo.record(null)
        assertNull(echo.lineToEcho())

        echo.record("PEAK_GOV: line A")
        assertEquals("PEAK_GOV: line A", echo.lineToEcho())
    }
}
