package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.DoubleKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Owner decision of step D1: the meal factors keep their default (50, sleep 60), and every label says
 * clearly that the value is a percent of the SMB: 100 = the full SMB, 50 = half. No dosing change.
 */
class AimiMealFactorLabelsTest {

    private val mealFactors = listOf(
        DoubleKey.OApsAIMIMealFactor,
        DoubleKey.OApsAIMIBFFactor,
        DoubleKey.OApsAIMILunchFactor,
        DoubleKey.OApsAIMIDinnerFactor,
        DoubleKey.OApsAIMIHCFactor,
        DoubleKey.OApsAIMISnackFactor,
        DoubleKey.OApsAIMIsleepFactor,
    )

    @Test
    fun `every meal factor has a summary that explains the percent`() {
        mealFactors.forEach { key -> assertNotNull(key.summary, key.name) }
    }

    @Test
    fun `the help sheet of every meal factor starts with the default and then the percent text`() {
        mealFactors.forEach { key ->
            val lines = aimiSettingSheetLines(key, unit = null, warnings = emptyList())
            assertTrue(lines.first() is AimiSettingSheetLine.Default, key.name)
            assertEquals(AimiSettingSheetLine.Detail(key.summary!!), lines[1], key.name)
        }
    }

    @Test
    fun `the defaults do not change`() {
        mealFactors.filter { it != DoubleKey.OApsAIMIsleepFactor }.forEach { assertEquals(50.0, it.defaultValue, it.name) }
        assertEquals(60.0, DoubleKey.OApsAIMIsleepFactor.defaultValue)
    }

    @Test
    fun `the sleep factor has its own text because its default is 60`() {
        assertTrue(DoubleKey.OApsAIMIsleepFactor.summary != DoubleKey.OApsAIMILunchFactor.summary)
        mealFactors.filter { it != DoubleKey.OApsAIMIsleepFactor }.forEach { assertEquals(DoubleKey.OApsAIMILunchFactor.summary, it.summary, it.name) }
    }
}
