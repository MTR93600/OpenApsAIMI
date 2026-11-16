package app.aaps.plugins.aps.openAPSAIMI.tpo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TpoSignalStreakTest {

    private val t0 = 1_794_863_658_122L
    private val min = 60_000L

    @Test
    fun one_tick_is_not_enough() {
        // 2026-11-16: post-hypo was proposed on the 14:14 tick only.
        val streak = TpoSignalStreak()
            .observe(null, t0 - 5 * min)
            .observe(TpoPackId.POST_HYPO_RECOVERY, t0)
            .observe(null, t0 + 5 * min)
        assertFalse(streak.isConfirmed(t0 + 5 * min))
    }

    @Test
    fun three_five_minute_ticks_in_a_row_confirm() {
        var streak = TpoSignalStreak()
        streak = streak.observe(TpoPackId.POST_HYPO_RECOVERY, t0)
        assertFalse(streak.isConfirmed(t0))
        streak = streak.observe(TpoPackId.POST_HYPO_RECOVERY, t0 + 5 * min)
        assertFalse(streak.isConfirmed(t0 + 5 * min))
        streak = streak.observe(TpoPackId.POST_HYPO_RECOVERY, t0 + 10 * min)
        assertTrue(streak.isConfirmed(t0 + 10 * min))
    }

    @Test
    fun one_minute_ticks_wait_the_same_time() {
        var streak = TpoSignalStreak()
        for (m in 0L..9L) {
            streak = streak.observe(TpoPackId.POOR_SLEEP_WINDOW, t0 + m * min)
            assertFalse(streak.isConfirmed(t0 + m * min))
        }
        streak = streak.observe(TpoPackId.POOR_SLEEP_WINDOW, t0 + 10 * min)
        assertTrue(streak.isConfirmed(t0 + 10 * min))
    }

    @Test
    fun a_different_pack_starts_again() {
        val streak = TpoSignalStreak()
            .observe(TpoPackId.POOR_SLEEP_WINDOW, t0)
            .observe(TpoPackId.POOR_SLEEP_WINDOW, t0 + 5 * min)
            .observe(TpoPackId.POST_HYPO_RECOVERY, t0 + 10 * min)
        assertFalse(streak.isConfirmed(t0 + 10 * min))
    }

    @Test
    fun a_long_gap_starts_again() {
        val streak = TpoSignalStreak()
            .observe(TpoPackId.POST_HYPO_RECOVERY, t0)
            .observe(TpoPackId.POST_HYPO_RECOVERY, t0 + 12 * min)
        assertFalse(streak.isConfirmed(t0 + 12 * min))
    }
}
