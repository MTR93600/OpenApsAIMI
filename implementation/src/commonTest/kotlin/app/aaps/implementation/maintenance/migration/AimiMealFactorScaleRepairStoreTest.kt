package app.aaps.implementation.maintenance.migration

import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.DoubleKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step T2: the meal factor repair over a raw store, the path start-up (Android) and an import of a
 * backup file take. A file holds every value as text.
 */
class AimiMealFactorScaleRepairStoreTest {

    private val flag = BooleanNonKey.OApsAIMIMealFactorScaleRepaired.key
    private val lunch = DoubleKey.OApsAIMILunchFactor.key
    private val dinner = DoubleKey.OApsAIMIDinnerFactor.key
    private val sleep = DoubleKey.OApsAIMIsleepFactor.key

    @Test
    fun anOldBackupWithATpoValueIsRepaired() {
        val file = FileKeyValueStore(mapOf(lunch to "1.15", dinner to "1.0", sleep to "1.15"))
        val repairs = repairAimiMealFactorScale(file)
        assertEquals(50.0, file.getDouble(lunch, 0.0), 1e-9)
        assertEquals(50.0, file.getDouble(dinner, 0.0), 1e-9)
        assertEquals(60.0, file.getDouble(sleep, 0.0), 1e-9)
        assertEquals(3, repairs.size)
        // The flag is written by the AIMI plugin start, not here, so an import counts no extra change.
        assertFalse(file.contains(flag))
    }

    @Test
    fun otherValuesAreKept() {
        val file = FileKeyValueStore(mapOf(lunch to "2.5", dinner to "50.0"))
        assertTrue(repairAimiMealFactorScale(file).isEmpty())
        assertEquals("2.5", file.getAll()[lunch])
        assertEquals("50.0", file.getAll()[dinner])
    }

    @Test
    fun aBackupMadeAfterTheRepairKeepsItsValues() {
        val file = FileKeyValueStore(mapOf(lunch to "1.5", flag to "true"))
        assertTrue(repairAimiMealFactorScale(file).isEmpty())
        assertEquals("1.5", file.getAll()[lunch])
    }

    @Test
    fun aLiveStoreWhereTheRepairRanKeepsALowValueSetByHand() {
        val store = FileKeyValueStore(mapOf(lunch to 1.15, flag to true))
        assertTrue(repairAimiMealFactorScale(store).isEmpty())
        assertEquals(1.15, store.getDouble(lunch, 0.0), 1e-9)
    }

    @Test
    fun aStoreWithNoMealFactorIsNotTouched() {
        val store = FileKeyValueStore(mapOf("some_other_key" to "1"))
        assertTrue(repairAimiMealFactorScale(store).isEmpty())
        assertEquals(mapOf<String, Any?>("some_other_key" to "1"), store.getAll())
    }

    @Test
    fun aValueThatIsNotANumberIsLeftInPlace() {
        val file = FileKeyValueStore(mapOf(lunch to "abc"))
        assertTrue(repairAimiMealFactorScale(file).isEmpty())
        assertEquals("abc", file.getAll()[lunch])
    }
}
