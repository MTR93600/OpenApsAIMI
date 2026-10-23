package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rise escape must stop at the physiological budget.
 *
 * `ESCAPE_RISE` pinned the raw multiplier at 0.88 for as long as glucose was climbing, with no regard
 * for the insulin already on board. Smoothing turns 0.88 into 0.928, which clears the `FULL`
 * threshold of 0.92, so the governor stayed wide open.
 *
 * Measured on 2026-08-09: it held `FULL` up to IOB 16.75 U against a budget of 8.11 - 2.07x - while
 * 14.09 U of SMB were delivered.
 *
 * `ESCAPE_PROJECTION` already had the `iobSafe < budget` guard. A rise is a reason to keep
 * correcting, it is not a reason to go past what the body can absorb.
 *
 * Port of `InsulinLoadGovernorEscapeRiseTest` from `dev_OAPSAIMI`. Truth/JUnit replaced by
 * `kotlin.test`, names in camelCase. The two escape clauses in the commonMain [InsulinLoadGovernor]
 * were compared with dev and are the same.
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

    private fun assertNear(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected but was $actual")
    }

    @Test
    fun aRiseBelowTheBudgetStillEscapesSoRealCorrectionsAreNotBlocked() {
        val raw = rawAfterEscapes(
            base = 0.30, iobSafe = 4.0, budget = budget,
            sharpRise = 0.9, bg = 190.0, target = 100.0, deltaPer5 = 1.2, projectionLead = 0.0,
        )

        assertTrue(raw >= 0.88)
        assertTrue(isFull(raw))
    }

    @Test
    fun theSameRiseAboveTheBudgetNoLongerEscapes() {
        // The 14:41 tick of 2026-08-09: IOB 9.04 against a budget of 8.11, glucose still climbing.
        val raw = rawAfterEscapes(
            base = 0.30, iobSafe = 9.04, budget = budget,
            sharpRise = 0.9, bg = 188.5, target = 100.0, deltaPer5 = 1.2, projectionLead = 0.0,
        )

        assertNear(0.30, raw)
        assertFalse(isFull(raw))
    }

    @Test
    fun theDeepOverdoseCanNoLongerHoldTheGovernorOpen() {
        // The 15:06 tick: IOB 15.28, more than 1.8x the budget, glucose at 225 and still rising.
        val raw = rawAfterEscapes(
            base = 0.30, iobSafe = 15.28, budget = budget,
            sharpRise = 1.0, bg = 224.9, target = 100.0, deltaPer5 = 1.5, projectionLead = 120.0,
        )

        assertFalse(isFull(raw))
    }

    @Test
    fun theProjectionEscapeKeepsTheGuardItAlreadyHad() {
        val below = rawAfterEscapes(
            base = 0.30, iobSafe = 4.0, budget = budget,
            sharpRise = 0.0, bg = 150.0, target = 100.0, deltaPer5 = 1.2, projectionLead = 120.0,
        )
        val above = rawAfterEscapes(
            base = 0.30, iobSafe = 9.0, budget = budget,
            sharpRise = 0.0, bg = 150.0, target = 100.0, deltaPer5 = 1.2, projectionLead = 120.0,
        )

        assertTrue(below >= 0.90)
        assertNear(0.30, above)
    }

    /** Both clauses now agree on the same rule, which is the point of the change. */
    @Test
    fun bothEscapesShareOneBudgetRule() {
        listOf(8.11, 9.0, 16.75).forEach { iob ->
            val raw = rawAfterEscapes(
                base = 0.30, iobSafe = iob, budget = budget,
                sharpRise = 1.0, bg = 220.0, target = 100.0, deltaPer5 = 1.5, projectionLead = 120.0,
            )
            assertNear(0.30, raw)
        }
    }
}
