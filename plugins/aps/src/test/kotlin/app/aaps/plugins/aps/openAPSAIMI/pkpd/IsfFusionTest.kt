package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.DoubleKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class IsfFusionTest {

    // Median logic, split out of the old `test fused logic`: each case now gets its own fresh
    // IsfFusion, because the four cases shared one instance and the slew limiter of case 1 was
    // bounding cases 2 to 4.

    @Test
    fun `median selection picks the middle of profile tdd and pkpd`() {
        // All three inputs equal -> median is that value.
        assertEquals(50.0, medianFusion().fused(50.0, 50.0, 1.0, nowMs = T0, authoritative = true), 0.01)

        // candidates: profile 40, tdd 50, pkpd 50 * 1.2 = 60 -> median 50
        assertEquals(50.0, medianFusion().fused(40.0, 50.0, 1.2, nowMs = T0, authoritative = true), 0.01)

        // candidates: profile 10, tdd 50, pkpd 100 -> median 50, still inside the safe band
        assertEquals(50.0, medianFusion().fused(10.0, 50.0, 2.0, nowMs = T0, authoritative = true), 0.01)
    }

    @Test
    fun `median is clamped to maxSafeIsf`() {
        // maxSafeIsf = tdd * maxFactor * 1.5 = 50 * 1.5 * 1.5 = 112.5
        // candidates: profile 200, tdd 50, pkpd 200 -> median 200 -> clamped to 112.5
        assertEquals(112.5, medianFusion().fused(200.0, 50.0, 4.0, nowMs = T0, authoritative = true), 0.01)
    }

    @Test
    fun `rising flag caps median at profile isf`() {
        // candidates: profile 40, tdd 50, pkpd 60 -> median 50, but a rising BG caps it at the profile ISF
        assertEquals(
            40.0,
            medianFusion().fused(40.0, 50.0, 1.2, nowMs = T0, authoritative = true, isRising = true),
            0.01
        )
    }

    /** Fresh fusion for the median cases: wide factors, so only the safe band can bind. */
    private fun medianFusion(): IsfFusion =
        IsfFusion(IsfFusionBounds(minFactor = 0.5, maxFactor = 1.5, maxChangePer5Min = 0.1))

    @Test
    fun `test fused smoothing`() {
        val fusion = IsfFusion(IsfFusionBounds(maxChangePer5Min = 0.1))
        
        // Initial: 50
        assertEquals(50.0, fusion.fused(50.0, 50.0, 1.0, nowMs = 0L, authoritative = true), 0.01)
        
        // Next: Target 100. Max change 10% -> 55.
        // candidates: 100, 100, 100 -> median 100.
        // clamped to 50 * 1.1 = 55.
        assertEquals(55.0, fusion.fused(100.0, 100.0, 1.0, nowMs = 300_000L, authoritative = true), 0.01)
    }

    // ---------------------------------------------------------------------------------------
    // Slew limiter: the budget must follow the clock, not the number of calls.
    // Every test below builds a FRESH IsfFusion so one case cannot pollute the next.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `zero max change per tick freezes isf instead of ratcheting down`() {
        val fusion = anchoredAt50(maxChangePerTick = 0.0)

        // With a zero budget the ISF must stay where it is, in BOTH directions.
        assertEquals(50.0, fusion.fused(10.0, 10.0, 1.0, nowMs = T0 + TICK_MS, authoritative = true), 0.01)
        assertEquals(50.0, fusion.fused(10.0, 10.0, 1.0, nowMs = T0 + 2 * TICK_MS, authoritative = true), 0.01)
        assertEquals(50.0, fusion.fused(10.0, 10.0, 1.0, nowMs = T0 + 3 * TICK_MS, authoritative = true), 0.01)
    }

    @Test
    fun `non authoritative call does not consume the budget`() {
        val fusion = anchoredAt50(maxChangePerTick = 0.1)

        // Read-only call one tick later: bounded, but it must not move the anchor.
        assertEquals(55.0, fusion.fused(200.0, 200.0, 1.0, nowMs = T0 + TICK_MS, authoritative = false), 0.01)
        // Same instant, this time the owner of the anchor: still a full tick of budget.
        assertEquals(55.0, fusion.fused(200.0, 200.0, 1.0, nowMs = T0 + TICK_MS, authoritative = true), 0.01)
    }

    @Test
    fun `budget is prorated by elapsed time`() {
        val fusion = anchoredAt50(maxChangePerTick = 0.1)

        // Half a nominal tick -> half the budget: 50 * (1 + 0.05) = 52.5
        assertEquals(52.5, fusion.fused(200.0, 200.0, 1.0, nowMs = T0 + TICK_MS / 2, authoritative = true), 0.01)
    }

    @Test
    fun `negative elapsed freezes the step and re anchors the timestamp`() {
        val fusion = IsfFusion(IsfFusionBounds(minFactor = 0.5, maxFactor = 1.5, maxChangePer5Min = 0.1))
        assertEquals(50.0, fusion.fused(50.0, 50.0, 1.0, nowMs = 1_000_000L, authoritative = true), 0.01)

        // Clock jumped backwards: zero budget, so the value freezes.
        assertEquals(50.0, fusion.fused(200.0, 200.0, 1.0, nowMs = 900_000L, authoritative = true), 0.01)
        // The anchor was re-stamped at 900 s, so 1200 s gives one full tick again.
        assertEquals(55.0, fusion.fused(200.0, 200.0, 1.0, nowMs = 1_200_000L, authoritative = true), 0.01)
    }

    @Test
    fun `catch up budget is capped after a long gap`() {
        val fusion = anchoredAt50(maxChangePerTick = 0.1)

        // Two hours of gap, but the catch-up is capped at two ticks: 50 * (1 + 0.2) = 60
        assertEquals(60.0, fusion.fused(200.0, 200.0, 1.0, nowMs = T0 + 2 * 3_600_000L, authoritative = true), 0.01)
    }

    @Test
    fun `legacy wide settings reproduce the historical single tick envelope`() {
        // Upper bound: 50 * 1.40
        val up = anchoredAt50(maxChangePerTick = LEGACY_WIDE_MAX_CHANGE_PER_TICK)
        assertEquals(70.0, up.fused(200.0, 200.0, 1.0, nowMs = T0 + TICK_MS, authoritative = true), 0.01)

        // Lower bound: 50 * 0.45
        val down = anchoredAt50(maxChangePerTick = LEGACY_WIDE_MAX_CHANGE_PER_TICK)
        assertEquals(22.5, down.fused(10.0, 10.0, 1.0, nowMs = T0 + TICK_MS, authoritative = true), 0.01)
    }

    @Test
    fun `max down branch limits the fall to the configured budget in one nominal tick`() {
        val fusion = IsfFusion(
            IsfFusionBounds(minFactor = 0.5, maxFactor = 1.5, maxChangePer5Min = LEGACY_WIDE_MAX_CHANGE_PER_TICK)
        )
        assertEquals(100.0, fusion.fused(100.0, 100.0, 1.0, nowMs = T0, authoritative = true), 0.01)

        // Target is 20, but one nominal tick can only take the ISF down to 100 * 0.45
        assertEquals(45.0, fusion.fused(20.0, 20.0, 1.0, nowMs = T0 + TICK_MS, authoritative = true), 0.01)
    }

    @Test
    fun `first call is not rate limited`() {
        val fusion = IsfFusion(IsfFusionBounds(minFactor = 0.5, maxFactor = 1.5, maxChangePer5Min = 0.1))

        assertEquals(200.0, fusion.fused(200.0, 200.0, 1.0, nowMs = T0, authoritative = true), 0.01)
    }

    // ---------------------------------------------------------------------------------------
    // FIX A — the preference default must say the same thing as the algorithm it drives.
    // ---------------------------------------------------------------------------------------

    /**
     * Pins the two defaults together so they cannot drift apart again. The preference used to ship
     * at 0.4 while the code asked for 0.03, and the device ran the preference.
     */
    @Test
    fun `preference default equals the IsfFusion bounds default`() {
        assertEquals(
            IsfFusionBounds().maxChangePer5Min,
            DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.defaultValue,
            1e-9,
        )
    }

    @Test
    fun `shipped default budget allows only a small step in one tick`() {
        val shipped = DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.defaultValue

        // Up: 50 * (1 + 0.03) = 51.5
        val up = anchoredAt(50.0, maxChangePerTick = shipped)
        assertEquals(51.5, up.fused(200.0, 200.0, 1.0, nowMs = T0 + TICK_MS, authoritative = true), 0.01)

        // Down: the budget is multiplied by DOWN_SLEW_GAIN 1.375 ONCE, so 0.03 -> 4.125 %,
        // i.e. 50 * 0.95875 = 47.94. (The spec's "-5.6 %" applies the gain twice.)
        val down = anchoredAt(50.0, maxChangePerTick = shipped)
        assertEquals(47.9375, down.fused(10.0, 10.0, 1.0, nowMs = T0 + TICK_MS, authoritative = true), 0.01)
    }

    /**
     * The measured 2026-10-01 23:59 step, 29.1 -> 50.5 mg/dL/U in one tick.
     *
     * Neither budget permits that step on its own: even the old wide 0.4 stops at 40.74, which is
     * why the measurement also needed the unlimited factors downstream of the fusion. What the new
     * default does change is the size of what the fusion can contribute: 0.87 instead of 11.64.
     */
    @Test
    fun `shipped default bounds the measured 29_1 to 50_5 step much harder than the old one`() {
        val legacy = anchoredAt(29.1, maxChangePerTick = LEGACY_WIDE_MAX_CHANGE_PER_TICK)
        assertEquals(40.74, legacy.fused(50.5, 50.5, 1.0, nowMs = T0 + TICK_MS, authoritative = true), 0.01)

        val shipped = anchoredAt(29.1, maxChangePerTick = DoubleKey.OApsAIMIIsfFusionMaxChangePerTick.defaultValue)
        assertEquals(29.973, shipped.fused(50.5, 50.5, 1.0, nowMs = T0 + TICK_MS, authoritative = true), 0.01)
    }

    /** Fresh fusion whose anchor is set to 50 at [T0], with wide min/max factors so only the slew limiter binds. */
    private fun anchoredAt50(maxChangePerTick: Double): IsfFusion = anchoredAt(50.0, maxChangePerTick)

    /** Fresh fusion whose anchor is set to [isf] at [T0], with wide min/max factors so only the slew limiter binds. */
    private fun anchoredAt(isf: Double, maxChangePerTick: Double): IsfFusion {
        val fusion = IsfFusion(
            IsfFusionBounds(minFactor = 0.5, maxFactor = 1.5, maxChangePer5Min = maxChangePerTick)
        )
        assertEquals(isf, fusion.fused(isf, isf, 1.0, nowMs = T0, authoritative = true), 0.01)
        return fusion
    }

    private companion object {

        const val T0 = 1_000_000L
        const val TICK_MS = 300_000L

        /**
         * The OLD default of the user preference `OApsAIMIIsfFusionMaxChangePerTick`, kept so the
         * historical one-tick envelope stays pinned. The shipped default is now the code default,
         * [IsfFusionBounds.maxChangePer5Min].
         */
        const val LEGACY_WIDE_MAX_CHANGE_PER_TICK = 0.4
    }
}
