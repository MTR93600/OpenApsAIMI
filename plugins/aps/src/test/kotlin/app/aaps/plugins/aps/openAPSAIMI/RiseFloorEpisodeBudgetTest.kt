package app.aaps.plugins.aps.openAPSAIMI

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The aggressive-rise SMB floor is a prebolus, so it is served once per rise.
 *
 * It had no memory. It re-armed on every tick, and at a 1.6 U ceiling on 5-minute ticks that is a
 * **19 U/h floor** for as long as the rise held.
 *
 * Measured on 2026-08-09: 1.088 U per tick for six consecutive ticks from 14:41, while the MPC asked
 * for exactly 0.000 and the safety barrier permitted exactly 0.000. Peak IOB 16.75 U against a
 * physiological budget of 8.11. Rescue carbs were needed.
 *
 * These tests mirror the ledger in `DetermineBasalAIMI2.remainingRiseFloorBudgetU` /
 * `noteRiseFloorContribution`, which are private to a 17 000-line class.
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

    @Test
    fun `the floor is capped at one large prebolus over a rise`() {
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

        assertThat(served).isWithin(1e-9).of(largePrebolus)
    }

    @Test
    fun `without the ledger the same rise would have served four times as much`() {
        // What the old code did, for contrast. 12 ticks at 1.088 U.
        val unbounded = 12 * 1.088

        assertThat(unbounded).isGreaterThan(largePrebolus * 3)
    }

    @Test
    fun `a second absorption wave of the same meal does not re-arm the budget`() {
        val ledger = Ledger(rearmMs)
        var now = 1_000_000L

        ledger.note(minOf(largePrebolus, ledger.remaining(largePrebolus, now)), now)
        // 60 minutes later — the second wave of the same meal, inside the re-arm window.
        now += 60 * 60_000L

        assertThat(ledger.remaining(largePrebolus, now)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `a genuinely new meal re-arms it`() {
        val ledger = Ledger(rearmMs)
        var now = 1_000_000L

        ledger.note(largePrebolus, now)
        now += rearmMs + 60_000L

        assertThat(ledger.remaining(largePrebolus, now)).isWithin(1e-9).of(largePrebolus)
    }

    @Test
    fun `insulin the model asked for does not consume the floor budget`() {
        val ledger = Ledger(rearmMs)
        val now = 1_000_000L

        // Model 1.5 U, floor 1.0 U: the floor adds nothing, so it spends nothing.
        val model = 1.5
        val floor = 1.0
        val raw = maxOf(model, floor)
        ledger.note(raw - model, now)

        assertThat(ledger.spent).isWithin(1e-9).of(0.0)
        assertThat(ledger.remaining(largePrebolus, now)).isWithin(1e-9).of(largePrebolus)
    }

    @Test
    fun `the budget is never negative`() {
        val ledger = Ledger(rearmMs)
        val now = 1_000_000L

        ledger.note(largePrebolus * 5, now)

        assertThat(ledger.remaining(largePrebolus, now)).isAtLeast(0.0)
    }
}
