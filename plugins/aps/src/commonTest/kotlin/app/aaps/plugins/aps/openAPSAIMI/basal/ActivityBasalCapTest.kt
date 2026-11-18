package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The activity ceiling bounds the rate that goes to the pump, and only ever lowers it. */
class ActivityBasalCapTest {

    @Test
    fun theFieldCaseIsBoundToProfileTimesTheFactor() {
        // SPORT_INTENSE, profile 1.3 U/h, factor 1.3: the pump got 4.0 U/h, the ceiling is 1.69 U/h
        val r = ActivityBasalCap.apply(rateUph = 4.0, profileBasalUph = 1.3, activityLockoutActive = true, factor = 1.3)
        assertTrue(r.applied)
        assertEquals(1.69, r.rateUph, 1e-9)
    }

    @Test
    fun aRateBelowTheCeilingIsKept() {
        val r = ActivityBasalCap.apply(rateUph = 1.0, profileBasalUph = 1.3, activityLockoutActive = true, factor = 1.3)
        assertFalse(r.applied)
        assertEquals(1.0, r.rateUph, 1e-9)
    }

    @Test
    fun theHyperExerciseOverrideFitsUnderTheDefaultCeiling() {
        // ExerciseHyperOverridePolicy allows ×1.28 at most: the default ×1.3 ceiling leaves it alone
        val r = ActivityBasalCap.apply(rateUph = 1.3 * 1.28, profileBasalUph = 1.3, activityLockoutActive = true, factor = 1.3)
        assertFalse(r.applied)
    }

    @Test
    fun aZeroRateStaysZero() {
        assertEquals(0.0, ActivityBasalCap.apply(0.0, 1.3, activityLockoutActive = true, factor = 1.3).rateUph, 1e-9)
    }

    @Test
    fun withoutADeclaredActivityNothingChanges() {
        val r = ActivityBasalCap.apply(rateUph = 4.0, profileBasalUph = 1.3, activityLockoutActive = false, factor = 1.3)
        assertFalse(r.applied)
        assertNull(r.capUph)
        assertEquals(4.0, r.rateUph, 1e-9)
    }

    @Test
    fun noProfileOrABrokenFactorChangesNothing() {
        assertEquals(4.0, ActivityBasalCap.apply(4.0, 0.0, activityLockoutActive = true, factor = 1.3).rateUph, 1e-9)
        assertEquals(4.0, ActivityBasalCap.apply(4.0, 1.3, activityLockoutActive = true, factor = Double.NaN).rateUph, 1e-9)
    }
}
