package app.aaps.plugins.aps.loop.runningMode

import app.aaps.core.data.model.RM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The shared "when does the mode end" rule, run on every target that schedules an expiry. */
class RunningModeExpiryDecisionTest {

    private val now = 1_700_000_000_000L
    private val thirtyMinutes = 30L * 60L * 1000L

    private fun mode(mode: RM.Mode, timestamp: Long = now, duration: Long) =
        RM(timestamp = timestamp, utcOffset = 0L, mode = mode, duration = duration)

    @Test
    fun `a permanent mode has nothing to schedule`() {
        assertNull(RunningModeExpiryDecision.delayUntilExpiry(mode(RM.Mode.CLOSED_LOOP, duration = 0L), now))
    }

    @Test
    fun `a temporary mode is scheduled for its end`() {
        val active = mode(RM.Mode.DISCONNECTED_PUMP, timestamp = now - 1_000L, duration = thirtyMinutes)

        assertEquals(thirtyMinutes - 1_000L, RunningModeExpiryDecision.delayUntilExpiry(active, now))
    }

    @Test
    fun `a temporary mode that already ended has nothing to schedule`() {
        val active = mode(RM.Mode.SUSPENDED_BY_USER, timestamp = now - 2 * thirtyMinutes, duration = thirtyMinutes)

        assertNull(RunningModeExpiryDecision.delayUntilExpiry(active, now))
    }

    @Test
    fun `an open-ended mode has nothing to schedule`() {
        assertNull(RunningModeExpiryDecision.delayUntilExpiry(mode(RM.Mode.SUSPENDED_BY_PUMP, duration = Long.MAX_VALUE), now))
    }

    @Test
    fun `an end time that overflows has nothing to schedule`() {
        val active = mode(RM.Mode.DISCONNECTED_PUMP, timestamp = Long.MAX_VALUE - 10L, duration = Long.MAX_VALUE - 1L)

        assertNull(RunningModeExpiryDecision.delayUntilExpiry(active, now))
    }
}
