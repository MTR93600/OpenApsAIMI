package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthActivation
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Step D2 (owner decision 2026-11-15): the default of the night growth (NGR) switch is now off, so
 * a plain `get` of the switch answers what the loop does when nothing was stored. (Since step A1 the
 * switch row no longer shows `get`: it shows the state, see `AimiNightGrowthSwitchRowTest`.) This
 * must not change dosing:
 * every reader that decides about NGR reads the switch with `getIfExists`, so the key default is
 * never used for the decision.
 *
 * The tests go over every combination of (switch stored on / off / never) x (age stored 12 / 30 /
 * never) x (T3C brittle mode on / off), and compare the state with the old switch default (on) and
 * the new one (off).
 */
class AimiNightGrowthSwitchDefaultTest {

    /**
     * A store where "never stored" is kept apart from a stored value. [switchDefault] is what a
     * `get` of the NGR switch answers when nothing is stored: the old key default (true) or the new
     * one (false). Every other key answers its own key default.
     */
    private class Store(
        private val switch: Boolean?,
        private val age: Int?,
        private val t3c: Boolean,
        private val switchDefault: Boolean,
        private val base: MapPreferences = MapPreferences(),
    ) : Preferences by base {

        private fun booleanOf(key: BooleanNonPreferenceKey): Boolean? = when (key) {
            BooleanKey.OApsAIMINightGrowthEnabled -> switch
            BooleanKey.OApsAIMIT3cBrittleMode     -> t3c
            else                                  -> null
        }

        override fun getIfExists(key: BooleanNonPreferenceKey): Boolean? = booleanOf(key)
        override fun get(key: BooleanNonPreferenceKey): Boolean = booleanOf(key) ?: defaultOf(key)
        override fun get(key: BooleanPreferenceKey): Boolean = booleanOf(key) ?: defaultOf(key)

        private fun defaultOf(key: BooleanNonPreferenceKey): Boolean =
            if (key == BooleanKey.OApsAIMINightGrowthEnabled) switchDefault else key.defaultValue

        override fun getIfExists(key: IntNonPreferenceKey): Int? = if (key == IntKey.OApsAIMINightGrowthAgeYears) age else null
        override fun get(key: IntNonPreferenceKey): Int = getIfExists(key) ?: key.defaultValue
        override fun get(key: IntPreferenceKey): Int = getIfExists(key) ?: key.defaultValue
    }

    private data class Case(val switch: Boolean?, val age: Int?, val t3c: Boolean)

    private val cases: List<Case> =
        listOf(true, false, null).flatMap { switch ->
            listOf(12, 30, null).flatMap { age -> listOf(true, false).map { t3c -> Case(switch, age, t3c) } }
        }

    /** The state of each case before and after step D2: the rule of step D1, written out by hand. */
    private fun expected(case: Case): NightGrowthState = when {
        case.switch == true                   -> NightGrowthState.ON_BY_SWITCH
        case.switch == null && case.age == 12 -> NightGrowthState.ON_BY_AGE
        case.t3c                              -> NightGrowthState.ON_BY_T3C
        case.switch == false                  -> NightGrowthState.OFF_BY_SWITCH
        case.age == null                      -> NightGrowthState.OFF_NO_AGE
        else                                  -> NightGrowthState.OFF_BY_AGE
    }

    private fun store(case: Case, switchDefault: Boolean) = Store(case.switch, case.age, case.t3c, switchDefault)

    @Test
    fun theSwitchDefaultIsOff() {
        assertFalse(BooleanKey.OApsAIMINightGrowthEnabled.defaultValue)
    }

    @Test
    fun theStateIsTheSameWithTheOldAndTheNewSwitchDefaultForEveryCombination() {
        assertEquals(18, cases.size)
        for (case in cases) {
            val before = NightGrowthActivation.read(store(case, switchDefault = true))
            val after = NightGrowthActivation.read(store(case, switchDefault = false))
            assertEquals(expected(case), before, "before D2, $case")
            assertEquals(expected(case), after, "after D2, $case")
        }
    }

    @Test
    fun theLoopRuleAgreesWithTheStateForEveryCombination() {
        // `DetermineBasalAIMI2.buildNightGrowthResistanceConfig` decides with the switch and the age
        // as stored (getIfExists) plus T3C brittle mode. Same inputs, same answer as the pages.
        for (case in cases) {
            val loop = NightGrowthActivation.isEnabledBySwitchOrAge(case.switch, case.age) || case.t3c
            assertEquals(expected(case).enabled, loop, "loop, $case")
        }
    }

    @Test
    fun withNothingStoredTheSwitchRowShowsWhatTheLoopDoes() {
        // A plain `get` of the key answers the key default (the switch row showed this until step A1,
        // which made it show the state instead). Before D2 it answered on while
        // the loop kept NGR off.
        val preferences = Store(switch = null, age = null, t3c = false, switchDefault = BooleanKey.OApsAIMINightGrowthEnabled.defaultValue)
        assertEquals(NightGrowthState.OFF_NO_AGE, NightGrowthActivation.read(preferences))
        assertEquals(NightGrowthActivation.read(preferences).enabled, preferences.get(BooleanKey.OApsAIMINightGrowthEnabled))
    }
}
