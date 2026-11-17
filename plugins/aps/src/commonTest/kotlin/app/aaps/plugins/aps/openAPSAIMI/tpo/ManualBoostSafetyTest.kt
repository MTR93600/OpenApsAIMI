package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.SportGuardKind
import app.aaps.plugins.aps.openAPSAIMI.safety.AimiHypoSmbSafety.SportSafetyInput
import kotlin.test.Test
import kotlin.test.assertEquals

class ManualBoostSafetyTest {

    private fun steps(s5: Int, s10: Int, s30: Int, s60: Int, s180: Int, sport: Boolean = false) =
        SportSafetyInput(s5, s10, s30, s60, s180, sportTime = sport, activityActive = false, averageHr = 70.0, averageHr10 = 70.0, targetBg = 100f)

    @Test
    fun sitting_after_a_morning_walk_is_past_activity_only() {
        // 2026-11-17 13:14: a few steps now, over 4500 in the last 3 hours
        assertEquals(SportGuardKind.PAST_ACTIVITY_ONLY, AimiHypoSmbSafety.sportGuard(steps(20, 40, 200, 2200, 4800)).kind)
    }

    @Test
    fun walking_now_or_sport_mode_is_activity_now() {
        assertEquals(SportGuardKind.ACTIVITY_NOW, AimiHypoSmbSafety.sportGuard(steps(450, 850, 1300, 2200, 4800)).kind)
        assertEquals(SportGuardKind.ACTIVITY_NOW, AimiHypoSmbSafety.sportGuard(steps(20, 40, 1300, 1300, 1300)).kind)
        assertEquals(SportGuardKind.ACTIVITY_NOW, AimiHypoSmbSafety.sportGuard(steps(0, 0, 0, 0, 0, sport = true)).kind)
    }

    @Test
    fun no_step_in_the_last_5_minutes_and_no_sport_mode_is_no_guard() {
        assertEquals(SportGuardKind.NONE, AimiHypoSmbSafety.sportGuard(steps(0, 0, 0, 5000, 9000)).kind)
    }

    @Test
    fun the_old_yes_or_no_answer_is_unchanged() {
        assertEquals(true, AimiHypoSmbSafety.sportSafety(steps(20, 40, 200, 2200, 4800)))
        assertEquals(false, AimiHypoSmbSafety.sportSafety(steps(20, 40, 200, 2200, 4000)))
    }

    @Test
    fun without_a_boost_the_sport_guard_still_stops_the_smb() {
        assertEquals(0.0, ManualBoostSafety.sportSmbFactor(SportGuardKind.PAST_ACTIVITY_ONLY, boostActive = false))
        assertEquals(0.0, ManualBoostSafety.sportSmbFactor(SportGuardKind.ACTIVITY_NOW, boostActive = false))
        assertEquals(1.0, ManualBoostSafety.sportSmbFactor(SportGuardKind.NONE, boostActive = false))
    }

    @Test
    fun a_boost_halves_on_past_activity_but_still_stops_on_activity_now() {
        assertEquals(0.5, ManualBoostSafety.sportSmbFactor(SportGuardKind.PAST_ACTIVITY_ONLY, boostActive = true))
        assertEquals(0.0, ManualBoostSafety.sportSmbFactor(SportGuardKind.ACTIVITY_NOW, boostActive = true))
    }

    @Test
    fun a_boost_keeps_the_effort_factor_at_half_or_more() {
        assertEquals(0.5, ManualBoostSafety.effortFactor(0.45, boostActive = true, sportHalvedForBoost = false))
        assertEquals(0.8, ManualBoostSafety.effortFactor(0.8, boostActive = true, sportHalvedForBoost = false))
        assertEquals(0.45, ManualBoostSafety.effortFactor(0.45, boostActive = false, sportHalvedForBoost = false))
    }

    @Test
    fun the_effort_factor_does_not_count_the_walk_a_second_time_after_the_sport_half() {
        // 2026-11-17 13:19: 4.46 U, halved by the sport guard, then x0.54 effort left about 1 U
        assertEquals(1.0, ManualBoostSafety.effortFactor(0.54, boostActive = true, sportHalvedForBoost = true))
        // Without a Boost the flag is never set, and the factor is unchanged anyway
        assertEquals(0.54, ManualBoostSafety.effortFactor(0.54, boostActive = false, sportHalvedForBoost = true))
    }
}
