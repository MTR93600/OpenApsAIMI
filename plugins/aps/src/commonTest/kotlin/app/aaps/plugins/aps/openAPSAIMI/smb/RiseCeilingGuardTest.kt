package app.aaps.plugins.aps.openAPSAIMI.smb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Traces come from the September 2026 support packages. The numbers in the "real burst" tests are
 * read off the exported ticks, not invented, so a change of threshold shows up here as a failing
 * test with a date attached to it.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `9a234151c7` (file SHA `59cbdf73ee`, unchanged on tip
 * `acdc2b118e` and on `f61474bb73`). Study source set: [RiseCeilingGuard] is commonMain and has no
 * mocks, so the tests live in `commonTest` (`kotlin.test`) — same layout as [MaxSmbLadderRiseByDeltaTest],
 * not `androidHostTest`. Assertions are the same locks as the Truth/JUnit reference corpus.
 */
class RiseCeilingGuardTest {

    private val minute = 60_000L
    private val t0 = 1_789_000_000_000L

    /** The two ceilings this patient runs: 1.25 U above the high-glucose line, 0.80 U below it. */
    private val ceiling = 0.80
    private val highCeiling = 1.25

    // ----- isAtCeiling -----

    @Test
    fun aBolusOnEitherCeilingCounts() {
        assertTrue(RiseCeilingGuard.isAtCeiling(0.80, ceiling, highCeiling))
        assertTrue(RiseCeilingGuard.isAtCeiling(1.25, ceiling, highCeiling))
    }

    @Test
    fun aBolusTheTerminalChoseTheSizeOfDoesNotCount() {
        assertFalse(RiseCeilingGuard.isAtCeiling(0.42, ceiling, highCeiling))
        assertFalse(RiseCeilingGuard.isAtCeiling(1.18, ceiling, highCeiling))
        assertFalse(RiseCeilingGuard.isAtCeiling(0.70, ceiling, highCeiling))
    }

    @Test
    fun noBolusIsNeverAtTheCeiling() {
        assertFalse(RiseCeilingGuard.isAtCeiling(0.0, ceiling, highCeiling))
        assertFalse(RiseCeilingGuard.isAtCeiling(-1.0, ceiling, highCeiling))
        assertFalse(RiseCeilingGuard.isAtCeiling(Double.NaN, ceiling, highCeiling))
    }

    @Test
    fun aCeilingThatIsNotAUsableNumberMatchesNothing() {
        assertFalse(RiseCeilingGuard.isAtCeiling(1.25, 0.0, Double.NaN))
        assertFalse(RiseCeilingGuard.isAtCeiling(1.25, -1.0, 0.0))
    }

    // ----- nextRepeatCount -----

    @Test
    fun aTickAwayFromTheCeilingClearsTheCount() {
        assertEquals(0, RiseCeilingGuard.nextRepeatCount(7, t0, t0 + minute, atCeiling = false))
    }

    @Test
    fun consecutiveTicksAtTheCeilingAddUp() {
        var count = 0
        var last = 0L
        for (i in 0 until 5) {
            val now = t0 + i * minute
            count = RiseCeilingGuard.nextRepeatCount(count, last, now, atCeiling = true)
            last = now
        }
        assertEquals(5, count)
    }

    @Test
    fun aHoleLongerThanTheGapLimitRestartsTheCount() {
        val count = RiseCeilingGuard.nextRepeatCount(9, t0, t0 + 16 * minute, atCeiling = true)
        assertEquals(1, count)
    }

    @Test
    fun aClockThatMovedBackRestartsTheCount() {
        assertEquals(1, RiseCeilingGuard.nextRepeatCount(9, t0, t0 - minute, atCeiling = true))
    }

    @Test
    fun theFirstTickEverStartsAtOne() {
        assertEquals(1, RiseCeilingGuard.nextRepeatCount(0, 0L, t0, atCeiling = true))
    }

    // ----- evaluate -----

    @Test
    fun theFirstDosesOfARiseAreNeverRefused() {
        for (repeats in 1 until RiseCeilingGuard.MIN_REPEATS) {
            val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = repeats, deltaMgdl5m = 16.2)
            assertFalse(verdict.block)
            assertTrue(verdict.reason.startsWith(RiseCeilingGuard.REASON_TOO_FEW_REPEATS))
        }
    }

    @Test
    fun aSlowRiseIsNeverRefusedHoweverLongTheRepeat() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 20, deltaMgdl5m = 5.3)
        assertFalse(verdict.block)
        assertTrue(verdict.reason.startsWith(RiseCeilingGuard.REASON_RISE_TOO_SMALL))
    }

    @Test
    fun aRepeatedCeilingDuringAFastRiseIsRefused() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 3, deltaMgdl5m = 16.2)
        assertTrue(verdict.block)
        assertTrue(verdict.reason.startsWith(RiseCeilingGuard.REASON_BLOCKED))
        assertEquals(3, verdict.repeats)
    }

    @Test
    fun aMissingRiseRefusesNothing() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 20, deltaMgdl5m = null)
        assertFalse(verdict.block)
        assertTrue(verdict.reason.startsWith(RiseCeilingGuard.REASON_NO_RISE_DATA))
    }

    @Test
    fun aRiseThatIsNotAUsableNumberRefusesNothing() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 20, deltaMgdl5m = bad)
            assertFalse(verdict.block)
        }
    }

    @Test
    fun aBolusBelowTheCeilingIsNeverRefused() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = false, repeats = 0, deltaMgdl5m = 20.0)
        assertFalse(verdict.block)
        assertEquals(RiseCeilingGuard.REASON_NOT_AT_CEILING, verdict.reason)
    }

    @Test
    fun theReasonCarriesTheLiveNumbers() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 4, deltaMgdl5m = 16.2)
        assertTrue(verdict.reason.contains("repeats=4"))
        assertTrue(verdict.reason.contains("delta=16.2"))
    }

    // ----- shouldWithhold -----

    @Test
    fun withTheKeyOffNothingIsEverHeldBack() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 9, deltaMgdl5m = 16.2)
        assertTrue(verdict.block)
        assertFalse(
            RiseCeilingGuard.shouldWithhold(verdict, armed = false, isExplicitUserAction = false, proposedUnits = 1.25)
        )
    }

    @Test
    fun anExplicitUserActionIsNeverHeldBack() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 9, deltaMgdl5m = 16.2)
        assertFalse(
            RiseCeilingGuard.shouldWithhold(verdict, armed = true, isExplicitUserAction = true, proposedUnits = 1.25)
        )
    }

    @Test
    fun aBolusOfZeroIsNotHeldBack() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 9, deltaMgdl5m = 16.2)
        assertFalse(
            RiseCeilingGuard.shouldWithhold(verdict, armed = true, isExplicitUserAction = false, proposedUnits = 0.0)
        )
    }

    @Test
    fun armedAndBlockingHoldsTheBolusBack() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 9, deltaMgdl5m = 16.2)
        assertTrue(
            RiseCeilingGuard.shouldWithhold(verdict, armed = true, isExplicitUserAction = false, proposedUnits = 1.25)
        )
    }

    // ----- real bursts -----

    /**
     * Replays one tick after another the way `DetermineBasalAIMI2` does, and returns how many ticks
     * the gesture would have refused.
     */
    private fun replay(bolus: List<Double>, delta: List<Double>): Int {
        var count = 0
        var last = 0L
        var blocked = 0
        for (i in bolus.indices) {
            val now = t0 + i * minute
            val atCeiling = RiseCeilingGuard.isAtCeiling(bolus[i], ceiling, highCeiling)
            count = RiseCeilingGuard.nextRepeatCount(count, last, now, atCeiling)
            if (atCeiling) last = now
            if (RiseCeilingGuard.evaluate(atCeiling, count, delta[i]).block) blocked++
        }
        return blocked
    }

    /**
     * 2026-09-13, 20:50 onward. Glucose 171 climbing to 227, the bolus pinned at 1.25 and the rise
     * well over +8. Insulin on board went from 6.98 U to 14.21 U and glucose reached 48 mg/dL two
     * hours later. The first two ticks must still get through; everything after is a repeat.
     */
    @Test
    fun theWorstBurstOf0913IsCutAfterTheFirstTwoTicks() {
        val bolus = List(10) { 1.25 }
        val delta = listOf(13.9, 15.2, 12.4, 10.7, 9.1, 11.3, 15.6, 14.2, 9.8, 10.1)
        assertEquals(8, replay(bolus, delta))
    }

    /**
     * 2026-09-14, 12:40 onward. Same shape: glucose 143 to 176, bolus at 1.25, low of 57 afterwards.
     */
    @Test
    fun theMiddayBurstOf0914IsCutAfterTheFirstTwoTicks() {
        val bolus = List(6) { 1.25 }
        val delta = listOf(16.2, 9.7, 8.6, 10.9, 9.3, 8.1)
        assertEquals(4, replay(bolus, delta))
    }

    /**
     * 2026-09-12, 23:10 onward: 24 ticks at the ceiling but a slow rise, and the episode ended at
     * 79 mg/dL. The gesture must stay out of this one — it is the kind of burst that did no harm.
     */
    @Test
    fun aLongBurstOnASlowRiseIsLeftAlone() {
        val bolus = List(12) { 0.80 }
        val delta = List(12) { 4.5 }
        assertEquals(0, replay(bolus, delta))
    }

    /**
     * A hole in the data in the middle of a burst restarts the count, so the two halves each need
     * their own three ticks before anything is refused.
     */
    @Test
    fun aHoleInTheMiddleOfABurstMakesTheCountStartAgain() {
        var count = 0
        var last = 0L
        var blocked = 0
        val offsetsMinutes = listOf(0L, 1L, 20L, 21L, 22L, 23L)
        for (offset in offsetsMinutes) {
            val now = t0 + offset * minute
            count = RiseCeilingGuard.nextRepeatCount(count, last, now, atCeiling = true)
            last = now
            if (RiseCeilingGuard.evaluate(true, count, 16.0).block) blocked++
        }
        // Without the hole the run would be 6 long and refuse 4. The hole costs it two more ticks.
        assertEquals(2, blocked)
    }
}
