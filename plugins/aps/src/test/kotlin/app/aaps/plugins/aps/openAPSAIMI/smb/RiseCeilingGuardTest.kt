package app.aaps.plugins.aps.openAPSAIMI.smb

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Traces come from the September 2026 support packages. The numbers in the "real burst" tests are
 * read off the exported ticks, not invented, so a change of threshold shows up here as a failing
 * test with a date attached to it.
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
        assertThat(RiseCeilingGuard.isAtCeiling(0.80, ceiling, highCeiling)).isTrue()
        assertThat(RiseCeilingGuard.isAtCeiling(1.25, ceiling, highCeiling)).isTrue()
    }

    @Test
    fun aBolusTheTerminalChoseTheSizeOfDoesNotCount() {
        assertThat(RiseCeilingGuard.isAtCeiling(0.42, ceiling, highCeiling)).isFalse()
        assertThat(RiseCeilingGuard.isAtCeiling(1.18, ceiling, highCeiling)).isFalse()
        assertThat(RiseCeilingGuard.isAtCeiling(0.70, ceiling, highCeiling)).isFalse()
    }

    @Test
    fun noBolusIsNeverAtTheCeiling() {
        assertThat(RiseCeilingGuard.isAtCeiling(0.0, ceiling, highCeiling)).isFalse()
        assertThat(RiseCeilingGuard.isAtCeiling(-1.0, ceiling, highCeiling)).isFalse()
        assertThat(RiseCeilingGuard.isAtCeiling(Double.NaN, ceiling, highCeiling)).isFalse()
    }

    @Test
    fun aCeilingThatIsNotAUsableNumberMatchesNothing() {
        assertThat(RiseCeilingGuard.isAtCeiling(1.25, 0.0, Double.NaN)).isFalse()
        assertThat(RiseCeilingGuard.isAtCeiling(1.25, -1.0, 0.0)).isFalse()
    }

    // ----- nextRepeatCount -----

    @Test
    fun aTickAwayFromTheCeilingClearsTheCount() {
        assertThat(RiseCeilingGuard.nextRepeatCount(7, t0, t0 + minute, atCeiling = false)).isEqualTo(0)
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
        assertThat(count).isEqualTo(5)
    }

    @Test
    fun aHoleLongerThanTheGapLimitRestartsTheCount() {
        val count = RiseCeilingGuard.nextRepeatCount(9, t0, t0 + 16 * minute, atCeiling = true)
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun aClockThatMovedBackRestartsTheCount() {
        assertThat(RiseCeilingGuard.nextRepeatCount(9, t0, t0 - minute, atCeiling = true)).isEqualTo(1)
    }

    @Test
    fun theFirstTickEverStartsAtOne() {
        assertThat(RiseCeilingGuard.nextRepeatCount(0, 0L, t0, atCeiling = true)).isEqualTo(1)
    }

    // ----- evaluate -----

    @Test
    fun theFirstDosesOfARiseAreNeverRefused() {
        for (repeats in 1 until RiseCeilingGuard.MIN_REPEATS) {
            val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = repeats, deltaMgdl5m = 16.2)
            assertThat(verdict.block).isFalse()
            assertThat(verdict.reason).startsWith(RiseCeilingGuard.REASON_TOO_FEW_REPEATS)
        }
    }

    @Test
    fun aSlowRiseIsNeverRefusedHoweverLongTheRepeat() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 20, deltaMgdl5m = 5.3)
        assertThat(verdict.block).isFalse()
        assertThat(verdict.reason).startsWith(RiseCeilingGuard.REASON_RISE_TOO_SMALL)
    }

    @Test
    fun aRepeatedCeilingDuringAFastRiseIsRefused() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 3, deltaMgdl5m = 16.2)
        assertThat(verdict.block).isTrue()
        assertThat(verdict.reason).startsWith(RiseCeilingGuard.REASON_BLOCKED)
        assertThat(verdict.repeats).isEqualTo(3)
    }

    @Test
    fun aMissingRiseRefusesNothing() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 20, deltaMgdl5m = null)
        assertThat(verdict.block).isFalse()
        assertThat(verdict.reason).startsWith(RiseCeilingGuard.REASON_NO_RISE_DATA)
    }

    @Test
    fun aRiseThatIsNotAUsableNumberRefusesNothing() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 20, deltaMgdl5m = bad)
            assertThat(verdict.block).isFalse()
        }
    }

    @Test
    fun aBolusBelowTheCeilingIsNeverRefused() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = false, repeats = 0, deltaMgdl5m = 20.0)
        assertThat(verdict.block).isFalse()
        assertThat(verdict.reason).isEqualTo(RiseCeilingGuard.REASON_NOT_AT_CEILING)
    }

    @Test
    fun theReasonCarriesTheLiveNumbers() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 4, deltaMgdl5m = 16.2)
        assertThat(verdict.reason).contains("repeats=4")
        assertThat(verdict.reason).contains("delta=16.2")
    }

    // ----- shouldWithhold -----

    @Test
    fun withTheKeyOffNothingIsEverHeldBack() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 9, deltaMgdl5m = 16.2)
        assertThat(verdict.block).isTrue()
        assertThat(
            RiseCeilingGuard.shouldWithhold(verdict, armed = false, isExplicitUserAction = false, proposedUnits = 1.25)
        ).isFalse()
    }

    @Test
    fun anExplicitUserActionIsNeverHeldBack() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 9, deltaMgdl5m = 16.2)
        assertThat(
            RiseCeilingGuard.shouldWithhold(verdict, armed = true, isExplicitUserAction = true, proposedUnits = 1.25)
        ).isFalse()
    }

    @Test
    fun aBolusOfZeroIsNotHeldBack() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 9, deltaMgdl5m = 16.2)
        assertThat(
            RiseCeilingGuard.shouldWithhold(verdict, armed = true, isExplicitUserAction = false, proposedUnits = 0.0)
        ).isFalse()
    }

    @Test
    fun armedAndBlockingHoldsTheBolusBack() {
        val verdict = RiseCeilingGuard.evaluate(atCeiling = true, repeats = 9, deltaMgdl5m = 16.2)
        assertThat(
            RiseCeilingGuard.shouldWithhold(verdict, armed = true, isExplicitUserAction = false, proposedUnits = 1.25)
        ).isTrue()
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
        assertThat(replay(bolus, delta)).isEqualTo(8)
    }

    /**
     * 2026-09-14, 12:40 onward. Same shape: glucose 143 to 176, bolus at 1.25, low of 57 afterwards.
     */
    @Test
    fun theMiddayBurstOf0914IsCutAfterTheFirstTwoTicks() {
        val bolus = List(6) { 1.25 }
        val delta = listOf(16.2, 9.7, 8.6, 10.9, 9.3, 8.1)
        assertThat(replay(bolus, delta)).isEqualTo(4)
    }

    /**
     * 2026-09-12, 23:10 onward: 24 ticks at the ceiling but a slow rise, and the episode ended at
     * 79 mg/dL. The gesture must stay out of this one — it is the kind of burst that did no harm.
     */
    @Test
    fun aLongBurstOnASlowRiseIsLeftAlone() {
        val bolus = List(12) { 0.80 }
        val delta = List(12) { 4.5 }
        assertThat(replay(bolus, delta)).isEqualTo(0)
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
        assertThat(blocked).isEqualTo(2)
    }
}
