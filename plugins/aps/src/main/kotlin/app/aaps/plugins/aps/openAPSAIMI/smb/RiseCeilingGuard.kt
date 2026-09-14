package app.aaps.plugins.aps.openAPSAIMI.smb

import java.util.Locale
import kotlin.math.abs

/**
 * Refuses a bolus that repeats the **ceiling** dose during a fast rise.
 *
 * ## What it is for
 *
 * When glucose climbs fast, the bolus that comes out of the terminal is not a computed size any
 * more: it is the configured ceiling, and the loop asks for it again on the next tick, and the next.
 * Insulin needs 20 to 40 minutes to show, so the loop can send the ceiling ten times before the
 * first one is visible, then the rise stops and everything that was sent is still to come. This is
 * dead time, not an aggressive setting: the controller sits at its maximum output with no feedback.
 *
 * The gesture cuts that repeat. It never changes the first doses of a rise — the ones that answer
 * the meal — only the ones sent while the earlier ones cannot yet be seen.
 *
 * ## What it measures
 *
 * Two conditions, both needed:
 *
 *  - the bolus has come out **exactly at a configured ceiling** for [MIN_REPEATS] ticks in a row
 *    ([MAX_GAP_MS] cuts the run, so a data hole never carries a count across it), and
 *  - glucose is rising by at least [MIN_RISE_MGDL_PER_5MIN] per 5 minutes.
 *
 * ## How it was chosen
 *
 * Replayed over 12451 ticks between 2026-09-02 and 2026-09-14. Inside one glucose band and one rise
 * band, ticks pinned at the ceiling for 3 ticks or more were followed by a reading under 70 mg/dL in
 * 90 % of cases (glucose 140-180, rise over +8) against 17 % for the rest of that same band, so the
 * signal is not "glucose is high". On the discrimination test the candidate scores 18.5: it would
 * hold back 69.6 U of decided bolus from bursts that ended under 70 against 3.8 U from bursts that
 * stayed above.
 *
 * **Read that number with its limits.** It rests on 6 distinct bad episodes. It falls to 2.0 when
 * the line is drawn at 60 mg/dL instead of 70, and to 1.9 when the rise threshold is lowered from
 * +8 to +6. Both thresholds were picked after seeing the data, which is why this ships disarmed:
 * the verdict is computed and exported on every tick so the same numbers can be built again, in
 * advance, on data nobody has looked at yet.
 *
 * ## Safety
 *
 * Reduction only — it can refuse a bolus, never enlarge one. It never touches the temporary basal
 * rate. It never acts on an explicit user action. Missing or non-finite data blocks nothing.
 */
object RiseCeilingGuard {

    /** Ticks in a row at the ceiling before the gesture can refuse anything. */
    const val MIN_REPEATS: Int = 3

    /** Rise, in mg/dL per 5 minutes, under which the gesture stays out of the way. */
    const val MIN_RISE_MGDL_PER_5MIN: Double = 8.0

    /**
     * A hole longer than this between two ticks restarts the count.
     *
     * The count only means something if the ticks it counts are next to each other in time; after a
     * hole the loop has no idea what was delivered in between, so it starts again from zero.
     */
    const val MAX_GAP_MS: Long = 15 * 60 * 1000L

    /** How close to a configured ceiling a bolus must be to count as "at the ceiling", in units. */
    const val CEILING_TOLERANCE_U: Double = 1e-3

    /** The two conditions hold: the bolus is refused. */
    const val REASON_BLOCKED: String = "rise_ceiling_repeat"

    /** The bolus did not come out at a ceiling, so there is nothing to repeat. */
    const val REASON_NOT_AT_CEILING: String = "not_at_ceiling"

    /** At the ceiling, but not yet for [MIN_REPEATS] ticks in a row. */
    const val REASON_TOO_FEW_REPEATS: String = "too_few_repeats"

    /** At the ceiling for long enough, but glucose is not climbing fast. */
    const val REASON_RISE_TOO_SMALL: String = "rise_too_small"

    /** The rise is missing or not a usable number, so nothing is refused. */
    const val REASON_NO_RISE_DATA: String = "no_rise_data"

    /**
     * One tick's answer.
     *
     * @param block true when the bolus must be refused, if the gesture is armed.
     * @param reason the reason code followed by the live numbers, for reading in the export.
     * @param repeats how many ticks in a row the bolus has come out at a ceiling, this one included.
     */
    data class Verdict(
        val block: Boolean,
        val reason: String,
        val repeats: Int,
        val deltaMgdl5m: Double?,
    )

    /**
     * Counts one more tick at the ceiling, or starts the count again.
     *
     * Call this with the bolus the terminal produced **before** this gesture refused anything,
     * otherwise a refused tick would look like a tick that was never at the ceiling and the count
     * would fall back to zero on every second tick.
     *
     * @param previous the count carried from the last tick.
     * @param previousMs the clock of the last tick that was counted, or 0 when there is none.
     * @param nowMs this tick's clock.
     * @param atCeiling whether this tick's bolus came out at a configured ceiling.
     */
    fun nextRepeatCount(previous: Int, previousMs: Long, nowMs: Long, atCeiling: Boolean): Int {
        if (!atCeiling) return 0
        val gap = nowMs - previousMs
        if (previousMs <= 0L || gap < 0L || gap > MAX_GAP_MS) return 1
        return previous + 1
    }

    /**
     * True when [units] sits on one of the configured ceilings.
     *
     * Both ceilings are offered because the loop uses the high-glucose one only on some ticks, and a
     * bolus that lands on either of them is a bolus the terminal did not choose the size of.
     */
    fun isAtCeiling(units: Double, ceilingU: Double, highGlucoseCeilingU: Double): Boolean {
        if (!units.isFinite() || units <= 0.0) return false
        val onNormal = ceilingU.isFinite() && ceilingU > 0.0 && abs(units - ceilingU) <= CEILING_TOLERANCE_U
        val onHigh = highGlucoseCeilingU.isFinite() && highGlucoseCeilingU > 0.0 &&
            abs(units - highGlucoseCeilingU) <= CEILING_TOLERANCE_U
        return onNormal || onHigh
    }

    /**
     * Weighs one tick. Pure: it reads no clock and keeps no state, so the same inputs always give
     * the same answer and the test can replay a real trace tick by tick.
     */
    fun evaluate(atCeiling: Boolean, repeats: Int, deltaMgdl5m: Double?): Verdict {
        if (!atCeiling) {
            return Verdict(false, REASON_NOT_AT_CEILING, repeats, deltaMgdl5m)
        }
        if (deltaMgdl5m == null || !deltaMgdl5m.isFinite()) {
            return Verdict(false, "$REASON_NO_RISE_DATA repeats=$repeats", repeats, deltaMgdl5m)
        }
        // Locale.US on purpose: the reason string is parsed from the exported data, and a locale
        // that writes a comma for the decimal point would break every reader of it.
        val numbers = "repeats=$repeats delta=${"%.1f".format(Locale.US, deltaMgdl5m)}"
        if (repeats < MIN_REPEATS) {
            return Verdict(false, "$REASON_TOO_FEW_REPEATS $numbers", repeats, deltaMgdl5m)
        }
        if (deltaMgdl5m < MIN_RISE_MGDL_PER_5MIN) {
            return Verdict(false, "$REASON_RISE_TOO_SMALL $numbers", repeats, deltaMgdl5m)
        }
        return Verdict(true, "$REASON_BLOCKED $numbers", repeats, deltaMgdl5m)
    }

    /**
     * Whether the caller must really drop the bolus.
     *
     * Split from [evaluate] on purpose: the verdict is computed on every tick so it can be counted
     * in the exported data, while only this function knows whether the opt-in key is on. With
     * [armed] false the answer is always false, so a tick is the same as it was before the gesture
     * existed.
     */
    fun shouldWithhold(
        verdict: Verdict,
        armed: Boolean,
        isExplicitUserAction: Boolean,
        proposedUnits: Double,
    ): Boolean = armed && verdict.block && !isExplicitUserAction && proposedUnits > 0.0
}
