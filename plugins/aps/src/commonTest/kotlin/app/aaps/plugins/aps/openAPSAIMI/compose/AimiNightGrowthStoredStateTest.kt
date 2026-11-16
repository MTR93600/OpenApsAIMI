package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthActivation
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Night growth support (NGR) depends on whether the age and the switch are stored at all, not only
 * on their values (step D1). These tests use a store that knows the difference between "stored 14"
 * and "nothing stored", which `MapPreferences` alone does not.
 */
class AimiNightGrowthStoredStateTest {

    /** [MapPreferences] plus real storage for int and boolean keys, with "not stored" kept apart. */
    private class StoredPreferences(private val base: MapPreferences = MapPreferences()) : Preferences by base {

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

    private val preferences = StoredPreferences()

    @Test
    fun nothingStoredMeansAnAdultAndNightGrowthOff() {
        assertEquals(NightGrowthState.OFF_NO_AGE, NightGrowthActivation.read(preferences))
    }

    @Test
    fun storingTheDefaultAgeTurnsNightGrowthOn() {
        // The page shows 14 (the key default) before anything is stored. Storing that same 14 does
        // not change the value a page observes, but it changes the state: the loop now reads a
        // stored age under 18.
        preferences.put(IntKey.OApsAIMINightGrowthAgeYears, IntKey.OApsAIMINightGrowthAgeYears.defaultValue)
        assertEquals(NightGrowthState.ON_BY_AGE, NightGrowthActivation.read(preferences))
    }

    @Test
    fun aStoredSwitchWinsAndT3cStillTurnsItOn() {
        preferences.put(BooleanKey.OApsAIMINightGrowthEnabled, true)
        assertEquals(NightGrowthState.ON_BY_SWITCH, NightGrowthActivation.read(preferences))
        preferences.put(BooleanKey.OApsAIMINightGrowthEnabled, false)
        preferences.put(IntKey.OApsAIMINightGrowthAgeYears, 10)
        assertEquals(NightGrowthState.OFF_BY_SWITCH, NightGrowthActivation.read(preferences))
        preferences.put(BooleanKey.OApsAIMIT3cBrittleMode, true)
        assertEquals(NightGrowthState.ON_BY_T3C, NightGrowthActivation.read(preferences))
    }

    @Test
    fun theAuditResetOfAnAdultAgeGoesBackToNotStoredAndKeepsNightGrowthOff() {
        preferences.put(IntKey.OApsAIMINightGrowthAgeYears, 30)
        assertEquals(NightGrowthState.OFF_BY_AGE, NightGrowthActivation.read(preferences))

        val row = AimiSettingsAudit.build(preferences, AimiSettingsAuditResourceStrings).rows
            .single { it.keyString == IntKey.OApsAIMINightGrowthAgeYears.key }
        row.resetToDefault(preferences)

        // Writing the key default 14 here would turn NGR on for an adult.
        assertEquals(null, preferences.getIfExists(IntKey.OApsAIMINightGrowthAgeYears))
        assertEquals(NightGrowthState.OFF_NO_AGE, NightGrowthActivation.read(preferences))
    }

    @Test
    fun theAuditResetOfTheSwitchGoesBackToNotStored() {
        preferences.put(BooleanKey.OApsAIMINightGrowthEnabled, true)

        val row = AimiSettingsAudit.build(preferences, AimiSettingsAuditResourceStrings).rows
            .single { it.keyString == BooleanKey.OApsAIMINightGrowthEnabled.key }
        row.resetToDefault(preferences)

        assertEquals(null, preferences.getIfExists(BooleanKey.OApsAIMINightGrowthEnabled))
        assertEquals(NightGrowthState.OFF_NO_AGE, NightGrowthActivation.read(preferences))
    }
}
