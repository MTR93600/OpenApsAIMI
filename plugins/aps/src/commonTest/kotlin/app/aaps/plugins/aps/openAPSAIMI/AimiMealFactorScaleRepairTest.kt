package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.keys.AimiMealFactorScaleRepair
import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step T2: a meal factor that the old TPO wrote on the 1.0 scale (for example 1.15) goes back to
 * its default once. The keys are percent, so a value below 2 turns the meal SMB almost off.
 */
class AimiMealFactorScaleRepairTest {

    @Test
    fun aValueTheOldTpoWroteGoesBackToTheDefault() {
        val preferences = MapPreferences().apply {
            doubles[DoubleKey.OApsAIMILunchFactor] = 1.15
            doubles[DoubleKey.OApsAIMIDinnerFactor] = 1.0
            doubles[DoubleKey.OApsAIMIsleepFactor] = 1.15
        }
        val repairs = AimiMealFactorScaleRepair.repairOnce(preferences)
        assertEquals(50.0, preferences.get(DoubleKey.OApsAIMILunchFactor), 1e-9)
        assertEquals(50.0, preferences.get(DoubleKey.OApsAIMIDinnerFactor), 1e-9)
        assertEquals(60.0, preferences.get(DoubleKey.OApsAIMIsleepFactor), 1e-9)
        assertEquals(3, repairs.size)
    }

    @Test
    fun everyMealFactorKeyIsRepaired() {
        val keys = listOf(
            DoubleKey.OApsAIMIMealFactor, DoubleKey.OApsAIMIBFFactor, DoubleKey.OApsAIMILunchFactor,
            DoubleKey.OApsAIMIDinnerFactor, DoubleKey.OApsAIMIHCFactor, DoubleKey.OApsAIMISnackFactor,
            DoubleKey.OApsAIMIsleepFactor,
        )
        val preferences = MapPreferences().apply { keys.forEach { doubles[it] = 1.2 } }
        AimiMealFactorScaleRepair.repairOnce(preferences)
        keys.forEach { assertEquals(it.defaultValue, preferences.get(it), 1e-9, it.key) }
    }

    @Test
    fun anyOtherValueIsKept() {
        val preferences = MapPreferences().apply {
            doubles[DoubleKey.OApsAIMILunchFactor] = 2.5
            doubles[DoubleKey.OApsAIMIDinnerFactor] = 50.0
            doubles[DoubleKey.OApsAIMIBFFactor] = 120.0
        }
        val repairs = AimiMealFactorScaleRepair.repairOnce(preferences)
        assertTrue(repairs.isEmpty())
        assertEquals(2.5, preferences.get(DoubleKey.OApsAIMILunchFactor), 1e-9)
        assertEquals(50.0, preferences.get(DoubleKey.OApsAIMIDinnerFactor), 1e-9)
        assertEquals(120.0, preferences.get(DoubleKey.OApsAIMIBFFactor), 1e-9)
        assertTrue(preferences.doublePuts.isEmpty(), "nothing is written when nothing is broken")
    }

    @Test
    fun aKeyThatWasNeverStoredIsNotWritten() {
        val preferences = MapPreferences()
        AimiMealFactorScaleRepair.repairOnce(preferences)
        assertTrue(preferences.doublePuts.isEmpty())
        assertTrue(preferences.get(BooleanNonKey.OApsAIMIMealFactorScaleRepaired))
    }

    @Test
    fun itRunsOnlyOnce() {
        val preferences = MapPreferences().apply { doubles[DoubleKey.OApsAIMILunchFactor] = 1.15 }
        AimiMealFactorScaleRepair.repairOnce(preferences)
        assertTrue(preferences.get(BooleanNonKey.OApsAIMIMealFactorScaleRepaired))
        // A low value set after the repair (by hand) is the user's choice and stays.
        preferences.doubles[DoubleKey.OApsAIMILunchFactor] = 1.5
        val second = AimiMealFactorScaleRepair.repairOnce(preferences)
        assertTrue(second.isEmpty())
        assertEquals(1.5, preferences.get(DoubleKey.OApsAIMILunchFactor), 1e-9)
    }

    @Test
    fun theDoneFlagIsExported() {
        // An exported backup made after the repair carries the flag, so an import keeps its values.
        assertTrue(BooleanNonKey.OApsAIMIMealFactorScaleRepaired.exportable)
        assertFalse(BooleanNonKey.OApsAIMIMealFactorScaleRepaired.defaultValue)
    }
}
