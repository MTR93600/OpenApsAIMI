package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The aggressive-rise SMB floor is a prebolus, so it is served once per rise.
 *
 * It had no memory. It armed again on every tick, and at a 1.6 U ceiling on 5-minute ticks that is
 * a **19 U/h floor** for as long as the rise held.
 *
 * Measured on 2026-08-09: 1.088 U per tick for six ticks in a row, while the MPC asked for 0.000 and
 * the safety barrier allowed 0.000. Peak IOB 16.75 U against a budget of 8.11. Rescue carbs were
 * needed.
 *
 * These tests mirror the ledger in `DetermineBasalAIMI2.remainingRiseFloorBudgetU` /
 * `noteRiseFloorContribution`, which are private.
 *
 * Port of `RiseFloorEpisodeBudgetTest` from `dev_OAPSAIMI`. Truth/JUnit replaced by `kotlin.test`,
 * names in camelCase. The KMP functions (state on `causalState.`, same 90 min re-arm window) were
 * compared with dev and match.
 */
class RiseFloorEpisodeBudgetTest {

    private val rearmMs = 90L * 60L * 1000L
    private val largePrebolus = 3.0

    /** The ledger under test. */
    private class Ledger(private val rearmMs: Long) {
        var spent = 0.0
            private set
        private var lastMs = 0L

        fun remaining(budget: Double, nowMs: Long): Double {
            if (nowMs - lastMs > rearmMs) spent = 0.0
            return (budget - spent).coerceAtLeast(0.0)
        }

        fun note(contributed: Double, nowMs: Long) {
            if (contributed <= 0.0) return
            spent += contributed
            lastMs = nowMs
        }
    }

    private fun assertNear(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected but was $actual")
    }

    @Test
    fun theFloorIsCappedAtOneLargePrebolusOverARise() {
        val ledger = Ledger(rearmMs)
        var now = 1_000_000L
        var served = 0.0

        // The lunch shape: a 1.088 U floor asked for on every tick for an hour.
        repeat(12) {
            val allowed = minOf(1.088, ledger.remaining(largePrebolus, now))
            ledger.note(allowed, now)
            served += allowed
            now += 5 * 60_000L
        }

        assertNear(largePrebolus, served)
    }

    @Test
    fun withoutTheLedgerTheSameRiseWouldHaveServedFourTimesAsMuch() {
        // What the old code did, for contrast. 12 ticks at 1.088 U.
        val unbounded = 12 * 1.088

        assertTrue(unbounded > largePrebolus * 3)
    }

    @Test
    fun aSecondAbsorptionWaveOfTheSameMealDoesNotRearmTheBudget() {
        val ledger = Ledger(rearmMs)
        var now = 1_000_000L

        ledger.note(minOf(largePrebolus, ledger.remaining(largePrebolus, now)), now)
        // 60 minutes later - the second wave of the same meal, inside the re-arm window.
        now += 60 * 60_000L

        assertNear(0.0, ledger.remaining(largePrebolus, now))
    }

    @Test
    fun aGenuinelyNewMealRearmsIt() {
        val ledger = Ledger(rearmMs)
        var now = 1_000_000L

        ledger.note(largePrebolus, now)
        now += rearmMs + 60_000L

        assertNear(largePrebolus, ledger.remaining(largePrebolus, now))
    }

    @Test
    fun insulinTheModelAskedForDoesNotConsumeTheFloorBudget() {
        val ledger = Ledger(rearmMs)
        val now = 1_000_000L

        // Model 1.5 U, floor 1.0 U: the floor adds nothing, so it spends nothing.
        val model = 1.5
        val floor = 1.0
        val raw = maxOf(model, floor)
        ledger.note(raw - model, now)

        assertNear(0.0, ledger.spent)
        assertNear(largePrebolus, ledger.remaining(largePrebolus, now))
    }

    @Test
    fun theBudgetIsNeverNegative() {
        val ledger = Ledger(rearmMs)
        val now = 1_000_000L

        ledger.note(largePrebolus * 5, now)

        assertTrue(ledger.remaining(largePrebolus, now) >= 0.0)
    }
}
