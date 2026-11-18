package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData.ContextKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveContextSummaryTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun sport(start: Long = now - 10 * minute, duration: Long = hour) =
        ContextIntent.Activity(startTimeMs = start, durationMs = duration, intensity = ContextIntent.Intensity.MEDIUM)

    private fun illness(start: Long = now - hour, duration: Long = 24 * hour) =
        ContextIntent.Illness(startTimeMs = start, durationMs = duration, intensity = ContextIntent.Intensity.MEDIUM)

    private fun travel() =
        ContextIntent.Travel(startTimeMs = now - hour, durationMs = 48 * hour, intensity = ContextIntent.Intensity.LOW)

    private fun summarize(
        vararg intents: ContextIntent,
        moduleOn: Boolean = true,
        aimiActive: Boolean = true,
        lastTickMs: Long? = now - 2 * minute,
    ) = ActiveContextSummary.summarize(intents.toList(), moduleOn, aimiActive, lastTickMs, now)

    @Test
    fun no_active_context_gives_nothing() {
        assertNull(summarize())
        assertNull(summarize(sport(start = now - 3 * hour, duration = hour)))
    }

    @Test
    fun a_context_the_last_tick_saw_is_used_by_the_loop() {
        val summary = summarize(sport())
        assertEquals(ContextKind.ACTIVITY, summary?.kind)
        assertEquals(1, summary?.count)
        assertEquals(now + 50 * minute, summary?.endsAtMs)
        assertTrue(summary!!.usedByLoop)
    }

    @Test
    fun the_most_important_kind_leads() {
        val summary = summarize(sport(), illness())
        assertEquals(ContextKind.ILLNESS, summary?.kind)
        assertEquals(2, summary?.count)
    }

    @Test
    fun module_off_means_declared_only() {
        assertFalse(summarize(sport(), moduleOn = false)!!.usedByLoop)
    }

    @Test
    fun another_algorithm_means_declared_only() {
        assertFalse(summarize(sport(), aimiActive = false)!!.usedByLoop)
    }

    @Test
    fun no_tick_since_the_context_started_means_declared_only() {
        assertFalse(summarize(sport(start = now - minute), lastTickMs = now - 3 * minute)!!.usedByLoop)
        assertFalse(summarize(sport(), lastTickMs = null)!!.usedByLoop)
    }

    @Test
    fun an_old_tick_means_declared_only() {
        val old = now - ActiveContextSummary.LAST_TICK_MAX_AGE_MS - minute
        assertFalse(summarize(sport(start = now - hour, duration = 2 * hour), lastTickMs = old)!!.usedByLoop)
    }

    @Test
    fun a_kind_the_dosing_does_not_read_is_never_used_by_the_loop() {
        val summary = summarize(travel())
        assertEquals(ContextKind.TRAVEL, summary?.kind)
        assertFalse(summary!!.usedByLoop)
    }
}
