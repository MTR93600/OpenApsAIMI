package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T3C Safety Guard 2 (resistance to sensitivity transition) behind its key.
 *
 * The guard never acted before the stable band average was wired: the average was always 0. The
 * key keeps it off by default, so the T3C basal must stay exactly what it was with the key off.
 */
class T3cTransitionGuardTest {

    /** A long high (band average 180 above the threshold 140) that now falls at -1 mg/dL per 5 min. */
    private fun rate(guardOn: Boolean, duraMinutes: Double, duraAverage: Double = 180.0): Double =
        DynamicBasalController.computeT3c(
            bg = 180.0,
            targetBg = 100.0,
            delta = -1.0f,
            shortAvgDelta = -1.0,
            longAvgDelta = 0.0,
            accel = 0.0,
            iob = 1.0,
            maxIob = 10.0,
            profileBasal = 1.0,
            isf = 50.0,
            duraISFminutes = duraMinutes,
            duraISFaverage = duraAverage,
            transitionGuardEnabled = guardOn,
            eventualBg = 170.0,
            activationThreshold = 140.0,
            aggressiveness = 1.0,
            maxBasalCap = 3.0,
        )

    @Test
    fun keyOffGivesTheSameBasalAsAGuardThatCannotFire() {
        val armedButOff = rate(guardOn = false, duraMinutes = 30.0)
        val neverArmed = rate(guardOn = false, duraMinutes = 0.0, duraAverage = 0.0)
        assertTrue(armedButOff > 0.0, "T3C basal should be above zero, was $armedButOff")
        assertEquals(neverArmed, armedButOff)
    }

    @Test
    fun keyOnCutsTheBasalByTheGuardMultiplier() {
        val off = rate(guardOn = false, duraMinutes = 30.0)
        val on = rate(guardOn = true, duraMinutes = 30.0)
        assertEquals(off * 0.2, on, 1e-6)
    }

    @Test
    fun keyOnDoesNothingWhenGlucoseWasNotHighForLong() {
        assertEquals(rate(guardOn = false, duraMinutes = 15.0), rate(guardOn = true, duraMinutes = 15.0))
        assertEquals(
            rate(guardOn = false, duraMinutes = 30.0, duraAverage = 120.0),
            rate(guardOn = true, duraMinutes = 30.0, duraAverage = 120.0),
        )
    }

    @Test
    fun guardFiresOnlyAfterALongHighThatFalls() {
        val falling = DynamicBasalController.t3cTransitionGuard(
            delta = -1.0f, shortAvgDelta = -1.0, duraISFminutes = 30.0, duraISFaverage = 180.0, activationThreshold = 140.0,
        )
        assertTrue(falling.wouldFire)
        assertEquals(0.2, falling.multiplier, 1e-6)

        val fastFall = DynamicBasalController.t3cTransitionGuard(
            delta = -2.0f, shortAvgDelta = -2.0, duraISFminutes = 30.0, duraISFaverage = 180.0, activationThreshold = 140.0,
        )
        assertTrue(fastFall.wouldFire)
        assertEquals(0.0, fastFall.multiplier)

        val flat = DynamicBasalController.t3cTransitionGuard(
            delta = 0.0f, shortAvgDelta = 0.0, duraISFminutes = 30.0, duraISFaverage = 180.0, activationThreshold = 140.0,
        )
        assertFalse(flat.wouldFire)
        assertEquals(1.0, flat.multiplier)

        val shortBand = DynamicBasalController.t3cTransitionGuard(
            delta = -1.0f, shortAvgDelta = -1.0, duraISFminutes = 20.0, duraISFaverage = 180.0, activationThreshold = 140.0,
        )
        assertFalse(shortBand.wouldFire)
        assertEquals(1.0, shortBand.multiplier)
    }
}
