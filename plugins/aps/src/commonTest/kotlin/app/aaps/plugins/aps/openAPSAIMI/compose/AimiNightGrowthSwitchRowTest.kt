package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.preference.BooleanKeyWithRow
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthActivation
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Step A1 (owner decision 2026-11-15, display only): the night growth (NGR) switch row shows the
 * state the loop uses, from [NightGrowthActivation.read], not the stored value of the switch. A
 * line under it says why. A tap stores the opposite of what the row shows on screen, as an explicit value,
 * which then wins over the age.
 *
 * Every case of (switch stored on / off / never) x (age stored 12 / 30 / never) x (T3C brittle mode
 * on / off) is checked against the loop rule of `DetermineBasalAIMI2.buildNightGrowthResistanceConfig`.
 */
class AimiNightGrowthSwitchRowTest {

    /** [MapPreferences] with real storage for the NGR switch, the age and T3C, "not stored" kept apart. */
    private class Store(private val base: MapPreferences = MapPreferences()) : Preferences by base {

        val ints: MutableMap<NonPreferenceKey, Int> = mutableMapOf()
        val booleans: MutableMap<NonPreferenceKey, Boolean> = mutableMapOf()

        override fun getIfExists(key: IntNonPreferenceKey): Int? = ints[key]
        override fun get(key: IntNonPreferenceKey): Int = ints[key] ?: key.defaultValue
        override fun get(key: IntPreferenceKey): Int = ints[key] ?: key.defaultValue
        override fun put(key: IntNonPreferenceKey, value: Int) {
            ints[key] = value
        }

        override fun getIfExists(key: BooleanNonPreferenceKey): Boolean? = booleans[key]
        override fun get(key: BooleanNonPreferenceKey): Boolean = booleans[key] ?: key.defaultValue
        override fun get(key: BooleanPreferenceKey): Boolean = booleans[key] ?: key.defaultValue
        override fun put(key: BooleanNonPreferenceKey, value: Boolean) {
            booleans[key] = value
        }

        override fun remove(key: NonPreferenceKey) {
            ints.remove(key)
            booleans.remove(key)
        }
    }

    private data class Case(val switch: Boolean?, val age: Int?, val t3c: Boolean)

    private val cases: List<Case> =
        listOf(true, false, null).flatMap { switch ->
            listOf(12, 30, null).flatMap { age -> listOf(true, false).map { t3c -> Case(switch, age, t3c) } }
        }

    private fun store(case: Case) = Store().apply {
        case.switch?.let { put(BooleanKey.OApsAIMINightGrowthEnabled, it) }
        case.age?.let { put(IntKey.OApsAIMINightGrowthAgeYears, it) }
        put(BooleanKey.OApsAIMIT3cBrittleMode, case.t3c)
    }

    /** What the loop decides, with the same inputs (`buildNightGrowthResistanceConfig`). */
    private fun loopRunsNgr(case: Case): Boolean =
        NightGrowthActivation.isEnabledBySwitchOrAge(case.switch, case.age) || case.t3c

    @Test
    fun theRowShowsWhatTheLoopDoesForEveryCombination() {
        assertEquals(18, cases.size)
        for (case in cases) {
            assertEquals(loopRunsNgr(case), AimiNightGrowthSwitch.shown(store(case)), "row, $case")
        }
    }

    @Test
    fun aTapStoresTheExplicitOppositeOfTheShownStateForEveryCombination() {
        for (case in cases) {
            val preferences = store(case)
            val shown = AimiNightGrowthSwitch.shown(preferences)

            AimiNightGrowthSwitch.tap(preferences, shownOnScreen = shown)

            assertEquals(!shown, preferences.getIfExists(BooleanKey.OApsAIMINightGrowthEnabled), "stored after tap, $case")
            // Only T3C brittle mode can keep NGR on after a tap that stored off.
            val expectedAfter = !shown || case.t3c
            assertEquals(expectedAfter, AimiNightGrowthSwitch.shown(preferences), "row after tap, $case")
            assertEquals(expectedAfter, NightGrowthActivation.isEnabledBySwitchOrAge(!shown, case.age) || case.t3c, "loop after tap, $case")
        }
    }

    @Test
    fun theReasonLineSaysWhyForEveryState() {
        assertEquals(ApsStrings.aimi_ngr_switch_reason_hand, AimiNightGrowthSwitch.reason(NightGrowthState.ON_BY_SWITCH))
        assertEquals(ApsStrings.aimi_ngr_switch_reason_hand, AimiNightGrowthSwitch.reason(NightGrowthState.OFF_BY_SWITCH))
        assertEquals(ApsStrings.aimi_ngr_switch_reason_age, AimiNightGrowthSwitch.reason(NightGrowthState.ON_BY_AGE))
        assertEquals(ApsStrings.aimi_ngr_switch_reason_age, AimiNightGrowthSwitch.reason(NightGrowthState.OFF_BY_AGE))
        assertEquals(ApsStrings.aimi_ngr_switch_reason_no_age, AimiNightGrowthSwitch.reason(NightGrowthState.OFF_NO_AGE))
        assertEquals(ApsStrings.aimi_ngr_switch_reason_t3c, AimiNightGrowthSwitch.reason(NightGrowthState.ON_BY_T3C))
    }

    @Test
    fun theChildCaseFromTheD2ReviewShowsOnAndATapTurnsItReallyOff() {
        // Age 12 stored, the switch never touched: the loop runs NGR. Before step A1 the row showed off.
        val preferences = store(Case(switch = null, age = 12, t3c = false))
        assertTrue(AimiNightGrowthSwitch.shown(preferences))
        assertEquals(NightGrowthState.ON_BY_AGE, NightGrowthActivation.read(preferences))

        AimiNightGrowthSwitch.tap(preferences, shownOnScreen = AimiNightGrowthSwitch.shown(preferences))

        assertEquals(false, preferences.getIfExists(BooleanKey.OApsAIMINightGrowthEnabled))
        assertEquals(NightGrowthState.OFF_BY_SWITCH, NightGrowthActivation.read(preferences))
    }

    @Test
    fun theAuditResetStillRemovesTheStoredSwitchSoTheAgeDecidesAgain() {
        val preferences = store(Case(switch = null, age = 12, t3c = false))
        AimiNightGrowthSwitch.tap(preferences, shownOnScreen = true)

        AimiSettingsAudit.build(preferences, AimiSettingsAuditResourceStrings).rows
            .single { it.keyString == BooleanKey.OApsAIMINightGrowthEnabled.key }
            .resetToDefault(preferences)

        assertEquals(null, preferences.getIfExists(BooleanKey.OApsAIMINightGrowthEnabled))
        assertEquals(NightGrowthState.ON_BY_AGE, NightGrowthActivation.read(preferences))
    }

    @Test
    fun aTapStoresTheOppositeOfTheStateOnScreenEvenWhenTheStoreChangedSince() {
        // The row showed off (no age). The person enters age 12 and taps at once to turn NGR on,
        // before the row reads the state again: the store now says ON_BY_AGE.
        val preferences = store(Case(switch = null, age = null, t3c = false))
        val shownOnScreen = AimiNightGrowthSwitch.shown(preferences)
        assertEquals(false, shownOnScreen)
        preferences.put(IntKey.OApsAIMINightGrowthAgeYears, 12)
        assertEquals(NightGrowthState.ON_BY_AGE, NightGrowthActivation.read(preferences))

        AimiNightGrowthSwitch.tap(preferences, shownOnScreen)

        // The person asked for on and gets an explicit on, not an explicit off that beats the age.
        assertEquals(true, preferences.getIfExists(BooleanKey.OApsAIMINightGrowthEnabled))
        assertEquals(NightGrowthState.ON_BY_SWITCH, NightGrowthActivation.read(preferences))
    }

    @Test
    fun theNightGrowthPageListsTheSwitchWithItsOwnRowForEveryRenderer() {
        // The page lists the switch as a BooleanKeyWithRow. The shared AdaptivePreferenceItem draws that
        // row, so the AIMI page and the page that the app search opens show the same state.
        val ngrPage = findScreen(AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems), "aimi_compose_ngr")
        val switchRows = ngrPage.items.filterIsInstance<PreferenceKey>().filter { AimiNightGrowthSwitch.isSwitchRow(it) }
        assertEquals(listOf(BooleanKey.OApsAIMINightGrowthEnabled.key), switchRows.map { it.key })
        assertIs<BooleanKeyWithRow>(switchRows.single())
        // No other row of the whole tree is drawn by an owner row.
        assertEquals(listOf(BooleanKey.OApsAIMINightGrowthEnabled.key), allKeys(AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)).filterIsInstance<BooleanKeyWithRow>().map { it.key }.distinct())
    }

    private fun allKeys(screen: PreferenceSubScreenDef): List<PreferenceKey> =
        screen.items.flatMap { item ->
            when (item) {
                is AimiScreenLink         -> listOf(item) + allKeys(item.screen)
                is PreferenceKey          -> listOf(item)
                is PreferenceSubScreenDef -> allKeys(item)
                else                      -> emptyList()
            }
        }

    private fun findScreen(screen: PreferenceSubScreenDef, key: String): PreferenceSubScreenDef {
        if (screen.key == key) return screen
        screen.items.filterIsInstance<PreferenceSubScreenDef>().forEach { child ->
            runCatching { return findScreen(child, key) }
        }
        error("no screen $key")
    }
}
