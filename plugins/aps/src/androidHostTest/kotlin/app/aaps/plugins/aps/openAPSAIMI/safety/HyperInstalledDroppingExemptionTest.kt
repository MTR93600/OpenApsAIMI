package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HyperInstalledDroppingExemptionTest {

    private fun input(
        enabled: Boolean = true,
        bg: Double = 213.0,
        target: Double = 100.0,
        delta: Double = -4.0,
        hypo: Double = 70.0,
        meal: Boolean = false,
        cob: Double = 0.0,
    ) = HyperInstalledDroppingExemption.Input(
        enabled = enabled,
        bgMgdl = bg,
        targetBgMgdl = target,
        deltaMgdl5m = delta,
        hypoThresholdMgdl = hypo,
        mealContextActive = meal,
        cobG = cob,
    )

    @Test
    fun bypasses_episode_plateau_down_tick_undeclared_deep_hyper() {
        // 15:02 style: BG=213 Δ=-7, no declared meal/COB — deep hyper path.
        assertThat(HyperInstalledDroppingExemption.shouldBypass(input(delta = -7.0))).isTrue()
    }

    @Test
    fun bypasses_mild_at_high_drop_delta_minus_2() {
        // 15:12: BG=227 Δ=-2 → only AtHigh + droppingFast family, not freefall.
        assertThat(HyperInstalledDroppingExemption.shouldBypass(input(bg = 227.0, delta = -2.0))).isTrue()
    }

    @Test
    fun bypasses_when_meal_clock_or_cob_corroborates_even_below_deep_hyper() {
        assertThat(
            HyperInstalledDroppingExemption.shouldBypass(
                input(bg = 190.0, delta = -3.0, meal = true)
            )
        ).isTrue()
        assertThat(
            HyperInstalledDroppingExemption.shouldBypass(
                input(bg = 190.0, delta = -3.0, cob = 8.0)
            )
        ).isTrue()
    }

    @Test
    fun pref_off_is_fail_safe_legacy() {
        assertThat(HyperInstalledDroppingExemption.shouldBypass(input(enabled = false))).isFalse()
    }

    @Test
    fun does_not_bypass_near_target_or_below_180() {
        assertThat(
            HyperInstalledDroppingExemption.shouldBypass(input(bg = 170.0, delta = -3.0, meal = true))
        ).isFalse()
        assertThat(
            HyperInstalledDroppingExemption.shouldBypass(
                input(bg = 185.0, target = 160.0, delta = -3.0, meal = true)
            )
        ).isFalse()
    }

    @Test
    fun does_not_bypass_rising_or_flat() {
        assertThat(HyperInstalledDroppingExemption.shouldBypass(input(delta = 0.0))).isFalse()
        assertThat(HyperInstalledDroppingExemption.shouldBypass(input(delta = 4.0))).isFalse()
    }

    @Test
    fun does_not_bypass_freefall_delta() {
        assertThat(
            HyperInstalledDroppingExemption.shouldBypass(input(bg = 240.0, delta = -16.0))
        ).isFalse()
    }

    @Test
    fun does_not_bypass_when_10min_projection_nears_hypo() {
        // BG=185 Δ=-12 → projected10 = 161, still ok; need projection under hypo+40.
        // BG=120 is below MIN_BG. Use BG=181, Δ=-14 → projected = 153; hypo+40=110 → still ok.
        // Force projection breach: BG=181, Δ=-14 → 153; raise hypo to 120 → buffer 160 → fail.
        assertThat(
            HyperInstalledDroppingExemption.shouldBypass(
                input(bg = 181.0, delta = -14.0, hypo = 120.0, meal = true)
            )
        ).isFalse()
    }

    @Test
    fun does_not_bypass_without_meal_or_deep_hyper() {
        assertThat(
            HyperInstalledDroppingExemption.shouldBypass(
                input(bg = 190.0, delta = -3.0, meal = false, cob = 0.0)
            )
        ).isFalse()
    }
}
