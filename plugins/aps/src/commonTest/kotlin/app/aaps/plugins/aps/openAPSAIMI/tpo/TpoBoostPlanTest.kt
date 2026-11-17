package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The manual Boost may raise each of its three ceilings by the chosen percent at most (30 % by default), never more. */
class TpoBoostPlanTest {

    @Test
    fun raises_by_thirty_percent() {
        assertEquals(1.3, TpoBoostPlan.boosted(1.0, keyMax = 15.0, factor = 1.3), 1e-9)
        assertEquals(3.25, TpoBoostPlan.boosted(2.5, keyMax = 25.0, factor = 1.3), 1e-9)
    }

    @Test
    fun rounds_down_so_the_cap_is_never_passed() {
        // 1.37 x 1.3 = 1.781 -> 1.78, never 1.79
        assertEquals(1.78, TpoBoostPlan.boosted(1.37, keyMax = 15.0, factor = 1.3), 1e-9)
        for (cents in 5..2500) {
            val value = cents / 100.0
            assertTrue(TpoBoostPlan.boosted(value, keyMax = 25.0, factor = 1.3) <= value * 1.3 + 1e-9)
        }
    }

    @Test
    fun the_key_max_and_the_hard_limit_cap_it() {
        assertEquals(15.0, TpoBoostPlan.boosted(14.0, keyMax = 15.0, factor = 1.3), 1e-9)
        assertEquals(3.0, TpoBoostPlan.boosted(2.9, keyMax = 25.0, factor = 1.3, hardMax = 3.0), 1e-9)
    }

    @Test
    fun a_value_already_above_the_cap_is_never_lowered() {
        assertEquals(4.0, TpoBoostPlan.boosted(4.0, keyMax = 25.0, factor = 1.3, hardMax = 3.0), 1e-9)
    }

    @Test
    fun the_plan_touches_only_the_three_ceilings() {
        val preferences = MapPreferences().apply {
            doubles[DoubleKey.OApsAIMIHighBGMaxSMB] = 2.0
            doubles[DoubleKey.ApsMaxBasal] = 3.0
            doubles[DoubleKey.autodriveMaxBasal] = 2.5
        }
        val plan = TpoBoostPlan.build(preferences, hardMaxBasal = 10.0)

        assertEquals(TpoPackId.MANUAL_BOOST, plan.proposal.packId)
        assertEquals(
            mapOf(DoubleKey.OApsAIMIHighBGMaxSMB.key to 2.6, DoubleKey.ApsMaxBasal.key to 3.9, DoubleKey.autodriveMaxBasal.key to 3.25),
            plan.changes.associate { it.key.key to (it.newValue as Double) },
        )
    }

    @Test
    fun a_ceiling_already_at_the_hard_limit_is_left_out() {
        val preferences = MapPreferences().apply {
            doubles[DoubleKey.OApsAIMIHighBGMaxSMB] = 2.0
            doubles[DoubleKey.ApsMaxBasal] = 3.0
            doubles[DoubleKey.autodriveMaxBasal] = 1.0
        }
        val plan = TpoBoostPlan.build(preferences, hardMaxBasal = 3.0)

        assertEquals(setOf(DoubleKey.OApsAIMIHighBGMaxSMB.key, DoubleKey.autodriveMaxBasal.key), plan.changes.map { it.key.key }.toSet())
    }

    @Test
    fun the_strength_follows_the_user_setting_inside_10_to_100_percent() {
        val preferences = MapPreferences().apply {
            doubles[DoubleKey.OApsAIMIHighBGMaxSMB] = 2.0
            doubles[DoubleKey.ApsMaxBasal] = 3.0
            doubles[DoubleKey.autodriveMaxBasal] = 2.5
            ints[IntKey.OApsAIMIManualBoostPercent] = 100
        }
        val doubled = TpoBoostPlan.build(preferences, hardMaxBasal = 10.0)
        assertEquals(6.0, doubled.changes.first { it.key == DoubleKey.ApsMaxBasal }.newValue as Double, 1e-9)

        // The hard limit still wins at 100 %
        val capped = TpoBoostPlan.build(preferences, hardMaxBasal = 4.0)
        assertEquals(4.0, capped.changes.first { it.key == DoubleKey.ApsMaxBasal }.newValue as Double, 1e-9)

        // A value outside the key range is held inside it
        assertEquals(2.0, TpoBoostPlan.factor(500), 1e-9)
        assertEquals(1.1, TpoBoostPlan.factor(0), 1e-9)
    }
}
