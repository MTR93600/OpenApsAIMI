package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.math.AimiTickPolicyMath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Parity with dev_OAPSAIMI for the state a tick reads before it writes it.
 *
 * Runs on the JVM and on the iOS simulator: both phones must dose the same.
 */
class AimiTickCarryOverTest {

    private fun previousTick() = AimiTickWorkingState().apply {
        pkpdThrottleIntervalAdd = 5
        pkpdPreferTbrBoost = 1.15
        activityProtectionMode = true
        hourOfDay = 9
        cob = 40f
        basalaimi = 0.85f
        mealTime = true
        snackTime = true
        lastEventualBgSnapshot = 180.0
    }

    @Test
    fun `the PKPD interval add and TBR boost of the last tick are kept`() {
        val next = AimiTickWorkingState()
        next.takeCarryOverFrom(previousTick(), hourNow = 14)

        assertEquals(5, next.pkpdThrottleIntervalAdd)
        assertEquals(1.15, next.pkpdPreferTbrBoost)
    }

    @Test
    fun `activity protection of the last tick reaches the scenario projection`() {
        val next = AimiTickWorkingState()
        next.takeCarryOverFrom(previousTick(), hourNow = 14)

        assertTrue(next.activityProtectionMode)
    }

    @Test
    fun `the hour is the real hour from the start of the tick and not zero`() {
        val next = AimiTickWorkingState()
        next.takeCarryOverFrom(previousTick(), hourNow = 14)

        // 14 h is day: the G6 BYODA check must not read the default 0 as night.
        assertEquals(14, next.hourOfDay)
    }

    @Test
    fun `the basal of the last tick reaches Harmonia instead of the 1 U per hour fallback`() {
        val next = AimiTickWorkingState()
        next.takeCarryOverFrom(previousTick(), hourNow = 14)

        assertEquals(0.85f, next.basalaimi)
    }

    @Test
    fun `meal and snack modes of the last tick reach the early PKPD meal context`() {
        val next = AimiTickWorkingState()
        next.takeCarryOverFrom(previousTick(), hourNow = 14)

        assertTrue(next.mealTime)
        assertTrue(next.snackTime)
    }

    @Test
    fun `a stale eventual BG is not carried over`() {
        val next = AimiTickWorkingState()
        next.takeCarryOverFrom(previousTick(), hourNow = 14)

        assertEquals(0.0, next.lastEventualBgSnapshot)
    }

    @Test
    fun `carbs on board are not carried over`() {
        val next = AimiTickWorkingState()
        next.takeCarryOverFrom(previousTick(), hourNow = 14)

        // dev_OAPSAIMI never reset cob, which kept a stale value: a bug, not something to copy.
        assertEquals(0f, next.cob)
    }

    @Test
    fun `a first tick starts from the neutral values`() {
        val next = AimiTickWorkingState()
        next.takeCarryOverFrom(AimiTickWorkingState(), hourNow = 3)

        assertEquals(0, next.pkpdThrottleIntervalAdd)
        assertEquals(1.0, next.pkpdPreferTbrBoost)
        assertFalse(next.activityProtectionMode)
        assertEquals(0.0f, next.basalaimi)
        assertFalse(next.mealTime)
        assertEquals(3, next.hourOfDay)
    }

    @Test
    fun `an FCL note alone is a meal context`() {
        assertTrue(AimiTickPolicyMath.isMealContextActive(manualMealMode = false, fclNote = true, mealCobG = 0.0))
    }

    @Test
    fun `meal context from a meal mode or more than 5 g of carbs and not otherwise`() {
        assertTrue(AimiTickPolicyMath.isMealContextActive(manualMealMode = true, fclNote = false, mealCobG = 0.0))
        assertTrue(AimiTickPolicyMath.isMealContextActive(manualMealMode = false, fclNote = false, mealCobG = 5.1))
        assertFalse(AimiTickPolicyMath.isMealContextActive(manualMealMode = false, fclNote = false, mealCobG = 5.0))
    }
}
