package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cases from the morning of 2026-10-21 (profile basal 0.4 U/h, max basal 7, high BG 140).
 *
 * Source: `dev_OAPSAIMI` working tree 2026-10-21 `CoffeeRiseModeTest`, same cases in `kotlin.test`.
 */
class CoffeeRiseModeTest {

    private fun eval(
        ageMin: Long? = 20L,
        windowMin: Long = 90L,
        declaredMeal: Boolean = false,
        bg: Double = 133.0,
        target: Double = 100.0,
        highBg: Double = 140.0,
        delta: Double = 6.0,
        iob: Double = 0.5,
        isf: Double? = 40.0,
        profile: Double = 0.4,
        maxBasal: Double = 7.0,
    ) = CoffeeRiseMode.evaluate(ageMin, windowMin, declaredMeal, bg, target, highBg, delta, iob, isf, profile, maxBasal)

    @Test
    fun windowIsNinetyMinutesByDefaultAndCappedAt150() {
        assertEquals(90L, CoffeeRiseMode.windowMin(0L))
        assertEquals(45L, CoffeeRiseMode.windowMin(45L))
        assertEquals(150L, CoffeeRiseMode.windowMin(600L))
    }

    @Test
    fun coffeeKeywordsMatchWithoutCase() {
        assertTrue(CoffeeRiseMode.isCoffeeNote("Coffee"))
        assertTrue(CoffeeRiseMode.isCoffeeNote("café du matin"))
        assertTrue(CoffeeRiseMode.isCoffeeNote("CAFE"))
        assertFalse(CoffeeRiseMode.isCoffeeNote("lunch"))
        assertFalse(CoffeeRiseMode.isCoffeeNote(null))
    }

    @Test
    fun noNoteMeansInactive() {
        val v = eval(ageMin = null)
        assertFalse(v.active)
        assertNull(CoffeeRiseMode.smbCeilingU(v, 1.5))
        assertNull(v.basalCeilingUph)
    }

    @Test
    fun noteOlderThanTheWindowIsInactive() {
        assertFalse(eval(ageMin = 91L).active)
    }

    /** A declared meal wins: coffee never brakes a meal the person announced. */
    @Test
    fun declaredMealSwitchesCoffeeOff() {
        assertFalse(eval(declaredMeal = true).active)
    }

    /** 07:50: BG 133 rising, model asked 0.61 U, 1.53 U went out. Under the high BG: no SMB at all. */
    @Test
    fun belowHighBgNoSmb() {
        val v = eval()
        assertTrue(v.active)
        assertFalse(v.mealSuspected)
        assertEquals(0.0, CoffeeRiseMode.smbCeilingU(v, 0.61))
    }

    /** Above the high BG and still climbing fast: maybe a breakfast after all, so the model may dose, nothing more. */
    @Test
    fun fastRiseAboveHighBgLetsOnlyTheModelDose() {
        val v = eval(bg = 155.0, delta = 6.0)
        assertTrue(v.mealSuspected)
        assertEquals(0.43, CoffeeRiseMode.smbCeilingU(v, 0.43))
        assertEquals(0.0, CoffeeRiseMode.smbCeilingU(v, null))
        assertNull(v.basalCeilingUph)
    }

    /** Budget 1 U over 90 min on 0.4 U/h: floor 0.4 + 0.67, ceiling 0.4 + 1.33. */
    @Test
    fun basalBandIsBoundedByTheBudget() {
        val v = eval()
        assertEquals(0.4 + 1.0 / 1.5, v.basalFloorUph!!, absoluteTolerance = 1e-9)
        assertEquals(0.4 + 2.0 / 1.5, v.basalCeilingUph!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun noFloorWhenFallingLowOrStackAlreadyEnough() {
        assertNull(eval(delta = -1.0).basalFloorUph)
        assertNull(eval(bg = 98.0).basalFloorUph)
        assertNull(eval(bg = 105.0, target = 100.0).basalFloorUph) // under target + 10
        assertNull(eval(iob = 3.0, isf = 40.0).basalFloorUph) // 120 ≥ 133 − 70
        assertNotNull(eval(delta = -1.0).basalCeilingUph) // the ceiling stays
    }

    @Test
    fun bandNeverAboveMaxBasal() {
        val v = eval(profile = 2.0, maxBasal = 2.5)
        assertTrue(v.basalFloorUph!! <= 2.5)
        assertTrue(v.basalCeilingUph!! <= 2.5)
    }
}
