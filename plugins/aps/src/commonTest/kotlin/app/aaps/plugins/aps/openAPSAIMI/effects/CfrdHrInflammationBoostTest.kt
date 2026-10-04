package app.aaps.plugins.aps.openAPSAIMI.effects

import kotlin.test.Test
import kotlin.test.assertEquals

class CfrdHrInflammationBoostTest {

    @Test
    fun strongRiseIs035() {
        assertEquals(0.35, cfrdHrInflammationBoostOf(hrNow = 110, rhr = 80))
    }

    @Test
    fun moderateRiseIs020() {
        assertEquals(0.20, cfrdHrInflammationBoostOf(hrNow = 100, rhr = 80))
    }

    @Test
    fun mildRiseIs010() {
        assertEquals(0.10, cfrdHrInflammationBoostOf(hrNow = 90, rhr = 80))
    }

    @Test
    fun smallRiseIsZero() {
        assertEquals(0.0, cfrdHrInflammationBoostOf(hrNow = 85, rhr = 80))
    }

    @Test
    fun missingHeartRateIsZero() {
        assertEquals(0.0, cfrdHrInflammationBoostOf(hrNow = 0, rhr = 60))
        assertEquals(0.0, cfrdHrInflammationBoostOf(hrNow = 90, rhr = 0))
    }
}
