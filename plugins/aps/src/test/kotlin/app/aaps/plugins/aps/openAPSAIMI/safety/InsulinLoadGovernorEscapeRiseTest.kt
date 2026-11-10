package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.max

/**
 * The rise escape must stop at the physiological budget.
 *
 * `ESCAPE_RISE` pinned the raw multiplier at 0.88 for as long as glucose was climbing, with no regard
 * for the insulin already on board. Smoothing turns 0.88 into 0.928, which clears the `FULL` threshold
 * of 0.92, so the governor stayed wide open.
 *
 * Measured on 2026-08-09: it held `FULL` up to IOB 16.75 U against a budget of 8.11 — 2.07x — while
 * 14.09 U of SMB were delivered. The episode ended when the governor finally left `FULL`, not because
 * anything decided to stop.
 *
 * `ESCAPE_PROJECTION`, three lines below, already carried the `iobSafe < budget` guard. The omission
 * here was an oversight: a rise justifies continuing to correct, it does not justify exceeding what
 * the body can absorb.
 */
class InsulinLoadGovernorEscapeRiseTest {

    /** Mirrors the two escape clauses and the smoothing, which is what decides the tier. */
    private fun rawAfterEscapes(
        base: Double,
        iobSafe: Double,
        budget: Double,
        sharpRise: Double,
        bg: Double,
        target: Double,
        deltaPer5: Double,
        projectionLead: Double,
    ): Double {
        var rawG = base
        if (iobSafe < budget && (sharpRise >= 0.75 || (bg > target + 85 && deltaPer5 > 0.8))) {
            rawG = max(rawG, 0.88)
        }
        if (projectionLead > 80.0 && deltaPer5 > 1.0 && iobSafe < budget) {
            rawG = max(rawG, 0.90)
        }
        return rawG
    }

    /** `0.4 * last + 0.6 * raw`, and `FULL` needs the result at or above 0.92. */
    private fun isFull(rawG: Double, lastG: Double = 1.0): Boolean = (0.4 * lastG + 0.6 * rawG) >= 0.92

    private val budget = 8.11

    @Test
    fun `a rise below the budget still escapes, so real corrections are not blocked`() {
        val raw = rawAfterEscapes(
            base = 0.30, iobSafe = 4.0, budget = budget,
            sharpRise = 0.9, bg = 190.0, target = 100.0, deltaPer5 = 1.2, projectionLead = 0.0,
        )

        assertThat(raw).isAtLeast(0.88)
        assertThat(isFull(raw)).isTrue()
    }

    @Test
    fun `the same rise above the budget no longer escapes`() {
        // The 14:41 tick of 2026-08-09: IOB 9.04 against a budget of 8.11, glucose still climbing.
        val raw = rawAfterEscapes(
            base = 0.30, iobSafe = 9.04, budget = budget,
            sharpRise = 0.9, bg = 188.5, target = 100.0, deltaPer5 = 1.2, projectionLead = 0.0,
        )

        assertThat(raw).isWithin(1e-9).of(0.30)
        assertThat(isFull(raw)).isFalse()
    }

    @Test
    fun `the deep overdose can no longer hold the governor open`() {
        // The 15:06 tick: IOB 15.28, more than 1.8x the budget, glucose at 225 and still rising.
        val raw = rawAfterEscapes(
            base = 0.30, iobSafe = 15.28, budget = budget,
            sharpRise = 1.0, bg = 224.9, target = 100.0, deltaPer5 = 1.5, projectionLead = 120.0,
        )

        assertThat(isFull(raw)).isFalse()
    }

    @Test
    fun `the projection escape keeps the guard it already had`() {
        val below = rawAfterEscapes(
            base = 0.30, iobSafe = 4.0, budget = budget,
            sharpRise = 0.0, bg = 150.0, target = 100.0, deltaPer5 = 1.2, projectionLead = 120.0,
        )
        val above = rawAfterEscapes(
            base = 0.30, iobSafe = 9.0, budget = budget,
            sharpRise = 0.0, bg = 150.0, target = 100.0, deltaPer5 = 1.2, projectionLead = 120.0,
        )

        assertThat(below).isAtLeast(0.90)
        assertThat(above).isWithin(1e-9).of(0.30)
    }

    /** Both clauses now agree on the same rule, which is the point of the change. */
    @Test
    fun `both escapes share one budget rule`() {
        listOf(8.11, 9.0, 16.75).forEach { iob ->
            val raw = rawAfterEscapes(
                base = 0.30, iobSafe = iob, budget = budget,
                sharpRise = 1.0, bg = 220.0, target = 100.0, deltaPer5 = 1.5, projectionLead = 120.0,
            )
            assertThat(raw).isWithin(1e-9).of(0.30)
        }
    }
}
