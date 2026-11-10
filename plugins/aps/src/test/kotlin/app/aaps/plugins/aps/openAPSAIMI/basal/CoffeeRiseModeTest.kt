package app.aaps.plugins.aps.openAPSAIMI.basal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Cases from the morning of 2026-10-21 (profile basal 0.4 U/h, max basal 7, high BG 140). */
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
        assertThat(CoffeeRiseMode.windowMin(0L)).isEqualTo(90L)
        assertThat(CoffeeRiseMode.windowMin(45L)).isEqualTo(45L)
        assertThat(CoffeeRiseMode.windowMin(600L)).isEqualTo(150L)
    }

    @Test
    fun coffeeKeywordsMatchWithoutCase() {
        assertThat(CoffeeRiseMode.isCoffeeNote("Coffee")).isTrue()
        assertThat(CoffeeRiseMode.isCoffeeNote("café du matin")).isTrue()
        assertThat(CoffeeRiseMode.isCoffeeNote("CAFE")).isTrue()
        assertThat(CoffeeRiseMode.isCoffeeNote("lunch")).isFalse()
        assertThat(CoffeeRiseMode.isCoffeeNote(null)).isFalse()
    }

    @Test
    fun noNoteMeansInactive() {
        val v = eval(ageMin = null)
        assertThat(v.active).isFalse()
        assertThat(CoffeeRiseMode.smbCeilingU(v, 1.5)).isNull()
        assertThat(v.basalCeilingUph).isNull()
    }

    @Test
    fun noteOlderThanTheWindowIsInactive() {
        assertThat(eval(ageMin = 91L).active).isFalse()
    }

    /** A declared meal wins: coffee never brakes a meal the person announced. */
    @Test
    fun declaredMealSwitchesCoffeeOff() {
        assertThat(eval(declaredMeal = true).active).isFalse()
    }

    /** 07:50: BG 133 rising, model asked 0.61 U, 1.53 U went out. Under the high BG: no SMB at all. */
    @Test
    fun belowHighBgNoSmb() {
        val v = eval()
        assertThat(v.active).isTrue()
        assertThat(v.mealSuspected).isFalse()
        assertThat(CoffeeRiseMode.smbCeilingU(v, 0.61)).isEqualTo(0.0)
    }

    /** Above the high BG and still climbing fast: maybe a breakfast after all, so the model may dose, nothing more. */
    @Test
    fun fastRiseAboveHighBgLetsOnlyTheModelDose() {
        val v = eval(bg = 155.0, delta = 6.0)
        assertThat(v.mealSuspected).isTrue()
        assertThat(CoffeeRiseMode.smbCeilingU(v, 0.43)).isEqualTo(0.43)
        assertThat(CoffeeRiseMode.smbCeilingU(v, null)).isEqualTo(0.0)
        assertThat(v.basalCeilingUph).isNull()
    }

    /** Budget 1 U over 90 min on 0.4 U/h: floor 0.4 + 0.67, ceiling 0.4 + 1.33. */
    @Test
    fun basalBandIsBoundedByTheBudget() {
        val v = eval()
        assertThat(v.basalFloorUph!!).isWithin(1e-9).of(0.4 + 1.0 / 1.5)
        assertThat(v.basalCeilingUph!!).isWithin(1e-9).of(0.4 + 2.0 / 1.5)
    }

    @Test
    fun noFloorWhenFallingLowOrStackAlreadyEnough() {
        assertThat(eval(delta = -1.0).basalFloorUph).isNull()
        assertThat(eval(bg = 98.0).basalFloorUph).isNull()
        assertThat(eval(bg = 105.0, target = 100.0).basalFloorUph).isNull() // under target + 10
        assertThat(eval(iob = 3.0, isf = 40.0).basalFloorUph).isNull()     // 120 ≥ 133 − 70
        assertThat(eval(delta = -1.0).basalCeilingUph).isNotNull()           // the ceiling stays
    }

    @Test
    fun bandNeverAboveMaxBasal() {
        val v = eval(profile = 2.0, maxBasal = 2.5)
        assertThat(v.basalFloorUph!!).isAtMost(2.5)
        assertThat(v.basalCeilingUph!!).isAtMost(2.5)
    }
}
