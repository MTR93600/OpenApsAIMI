package app.aaps.plugins.aps.openAPSAIMI.smb

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

/**
 * Blocks a **bolus** re-dose that lands inside the descent of an excursion that is already covered.
 *
 * ## What it is for
 *
 * After a big excursion the BG comes down and a large amount of insulin is still active. Adding one
 * more bolus at that moment does not shorten the excursion, it only moves the landing point below
 * range. This guard refuses the bolus while the tick is still inside the basin of that descent.
 *
 * The rule is an **episode** test, not an instant test. An instant test ("BG is falling") does not
 * see the case it was built for: on the night of 08/09 the re-dose happened on a *local bump*
 * (170.2 mg/dL then 186.8 mg/dL fifteen minutes later, so +17), and at that instant BG was rising,
 * not falling. Only a window that still remembers the peak sees it.
 *
 * ## Why the rebound guard (condition e) is not an extra
 *
 * A bare episode test cannot tell a *bump inside a descent* from a *new meal that starts within two
 * hours of the previous peak*. Both look like "the peak is behind us and BG is under it". Without
 * condition (e) this guard removed 6.39 U (62 %) from a burst that had a good outcome, and the
 * cost/gain ratio of the whole gesture went from 0.04 to 0.43. Condition (e) keeps the block inside
 * the basin of the descent: as soon as BG has climbed more than [REBOUND_MGDL] above the lowest
 * point reached since the peak, the guard steps aside and calls it a new rise.
 *
 * ## Limits (measured, not guessed)
 *
 *  - **Bolus channel only.** On the night of 08/09 it removes 1.64 U out of about 4.2 U of the
 *    episode; the 2.52 U that the temporary basal command integrates over the same window are not
 *    touched. The basal channel is separate work.
 *  - **The five thresholds are fitted on one corpus and were never checked out of sample**
 *    (8420 ticks / 271 boluses / 48 classified bursts). A stricter setting touched a single bad
 *    burst; this setting touches 4 out of 12. That is still few.
 *  - **8 bad bursts are not touched at all.** Their failure mechanism is not descent re-dosing, so
 *    this guard is not the answer for them and must not be widened until that is understood.
 *
 * The object is pure on purpose: no Android type, no singleton state, every input a parameter. That
 * is what makes the reference episodes replayable in a unit test.
 *
 * Source: `origin/dev_OAPSAIMI` @ `fe96b64f12`. Study wiring: commonMain `internal object` (pure, no
 * instance state, one tick consumer). No Metro `@Inject`. Sibling [MaxSmbLadder] is the same shape.
 * KMP adaptation only: [aimiFmt0] / [aimiFmt1] / [aimiFmt2] instead of `String.format(Locale.US)`.
 * Clinical formulas, thresholds, and reason tokens are unchanged.
 */
internal object DescentRedoseGuard {

    /** One glucose reading. [bgMgdl] is the recalculated (smoothed) value, [timeMs] its epoch time. */
    data class Reading(val timeMs: Long, val bgMgdl: Double)

    /**
     * The verdict plus everything needed to explain it.
     *
     * The measured values are exported as they are, so a tick that did **not** block can still be
     * counted and read afterwards. Fields are null when the window was too poor to compute them.
     */
    data class Verdict(
        /** True when all five conditions hold and the bolus should be refused. */
        val block: Boolean,
        /** Stable token, one of the `REASON_*` constants. Safe to count on. */
        val reasonCode: String,
        /** [reasonCode] followed by the live numbers, for reading a log or an export. */
        val reason: String,
        /** Highest BG of the usable window, mg/dL. */
        val peakMgdl: Double?,
        /** Minutes between the peak and now. */
        val peakAgeMinutes: Double?,
        /** How far current BG sits under the peak, mg/dL. */
        val dropFromPeakMgdl: Double?,
        /** Lowest BG reached since the peak, mg/dL. */
        val troughMgdl: Double?,
        /** How far current BG sits above that lowest point, mg/dL. */
        val reboundFromTroughMgdl: Double?,
        /** Current BG, mg/dL. */
        val currentBgMgdl: Double?,
        /** IOB handed to the guard, U. */
        val iobU: Double,
    )

    /** All five conditions hold: this is a re-dose inside a covered descent. */
    const val REASON_BLOCKED = "descent_redose"

    /** No usable reading inside the window. */
    const val REASON_NO_DATA = "no_data"

    /** The newest reading is older than [MAX_GAP_MINUTES]; the window cannot describe "now". */
    const val REASON_STALE_DATA = "stale_data"

    /** (a) failed: the window never went high enough for this to be a real excursion. */
    const val REASON_PEAK_TOO_LOW = "peak_too_low"

    /** (b) failed: the peak is too close, the descent has not had time to show itself. */
    const val REASON_PEAK_TOO_RECENT = "peak_too_recent"

    /** (c) failed: BG has not actually come down from the peak. */
    const val REASON_DROP_TOO_SMALL = "drop_too_small"

    /** (d) failed: not enough insulin still active for the descent to be considered covered. */
    const val REASON_IOB_TOO_LOW = "iob_too_low"

    /** (e) failed: BG has climbed back well above the lowest point, so this reads as a new rise. */
    const val REASON_REBOUND_ABOVE_TROUGH = "rebound_above_trough"

    /**
     * Window looked at, minutes.
     *
     * 120 min is the V4 setting. It is long enough to still hold the peak of the 08/09 night when
     * the third re-dose arrives 98 min after it, and short enough that a peak from the previous
     * meal does not govern the current one.
     */
    const val WINDOW_MINUTES: Int = 120

    /**
     * (a) Lowest peak that counts as a real excursion, mg/dL.
     *
     * 180 keeps the guard out of ordinary in-range wobble. On the corpus every burst it should
     * catch peaked well above this (232.5 on the 08/09 night).
     */
    const val PEAK_MIN_MGDL: Double = 180.0

    /**
     * (b) How long the peak must be behind us, minutes.
     *
     * 30 min is about six CGM readings, enough for a descent to be a fact rather than one noisy
     * point. The three blocked boluses of the 08/09 night sat 57, 63 and 98 min after the peak.
     */
    const val PEAK_AGE_MINUTES: Double = 30.0

    /**
     * (c) How far BG must have come down from the peak, mg/dL.
     *
     * 25 is what keeps the good 09/09 lunch out: there BG was 240.2 for a peak of 240.4, a fall of
     * 0.2 mg/dL, so the excursion had not turned yet and its boluses were legitimate.
     */
    const val DROP_MGDL: Double = 25.0

    /**
     * (d) Insulin that must still be active for the descent to be considered covered, U.
     *
     * 6.0 U is heavy for this corpus; the 08/09 night carried 11.3 to 14.3 U. Below this the
     * descent may well need help and the guard must not interfere.
     */
    const val IOB_MIN_U: Double = 6.0

    /**
     * (e) How far BG may climb above the lowest point and still count as the same descent, mg/dL.
     *
     * See the class KDoc: this is the condition that separates a bump inside a descent from a new
     * meal. 25 mg/dL was the value that took the cost/gain ratio from 0.43 back to 0.04.
     */
    const val REBOUND_MGDL: Double = 25.0

    /**
     * Largest hole between two readings the window may contain, minutes.
     *
     * Walking back in time stops at the first hole wider than this. The corpus holds a 21.7 h hole;
     * without this rule the "peak" on the far side of it would be a ghost and the guard would block
     * on an excursion that ended the day before.
     */
    const val MAX_GAP_MINUTES: Double = 10.0

    private const val MS_PER_MINUTE = 60_000.0

    /**
     * Applies the five conditions to [readings] and says whether the bolus should be refused.
     *
     * @param readings glucose readings in any order; only those inside the window are used.
     * @param nowMs current time, epoch ms.
     * @param iobU insulin still active, U.
     */
    fun evaluate(
        readings: List<Reading>,
        nowMs: Long,
        iobU: Double,
        windowMinutes: Int = WINDOW_MINUTES,
        peakMinMgdl: Double = PEAK_MIN_MGDL,
        peakAgeMinutes: Double = PEAK_AGE_MINUTES,
        dropMgdl: Double = DROP_MGDL,
        iobMinU: Double = IOB_MIN_U,
        reboundMgdl: Double = REBOUND_MGDL,
        maxGapMinutes: Double = MAX_GAP_MINUTES,
    ): Verdict {
        val window = usableWindow(readings, nowMs, windowMinutes, maxGapMinutes)
            ?: return empty(REASON_NO_DATA, iobU)

        val current = window.last()
        val staleMinutes = (nowMs - current.timeMs) / MS_PER_MINUTE
        if (staleMinutes > maxGapMinutes) return empty(REASON_STALE_DATA, iobU)

        // The LAST reading that holds the maximum is taken as the peak. On a plateau this is the
        // conservative choice: it makes the peak as young as possible, so condition (b) blocks less.
        var peak = window.first()
        for (reading in window) if (reading.bgMgdl >= peak.bgMgdl) peak = reading

        var trough = peak
        for (reading in window) {
            if (reading.timeMs >= peak.timeMs && reading.bgMgdl < trough.bgMgdl) trough = reading
        }

        val ageMinutes = (nowMs - peak.timeMs) / MS_PER_MINUTE
        val drop = peak.bgMgdl - current.bgMgdl
        val rebound = current.bgMgdl - trough.bgMgdl

        val code = when {
            peak.bgMgdl < peakMinMgdl -> REASON_PEAK_TOO_LOW
            ageMinutes < peakAgeMinutes -> REASON_PEAK_TOO_RECENT
            drop < dropMgdl             -> REASON_DROP_TOO_SMALL
            iobU < iobMinU              -> REASON_IOB_TOO_LOW
            rebound > reboundMgdl       -> REASON_REBOUND_ABOVE_TROUGH
            else                        -> REASON_BLOCKED
        }

        return Verdict(
            block = code == REASON_BLOCKED,
            reasonCode = code,
            reason = "$code peak=${aimiFmt1(peak.bgMgdl)} age=${aimiFmt0(ageMinutes)} drop=${aimiFmt1(drop)} trough=${aimiFmt1(trough.bgMgdl)} rebound=${aimiFmt1(rebound)} bg=${aimiFmt1(current.bgMgdl)} iob=${aimiFmt2(iobU)}",
            peakMgdl = peak.bgMgdl,
            peakAgeMinutes = ageMinutes,
            dropFromPeakMgdl = drop,
            troughMgdl = trough.bgMgdl,
            reboundFromTroughMgdl = rebound,
            currentBgMgdl = current.bgMgdl,
            iobU = iobU,
        )
    }

    /**
     * Whether the dose must actually be withheld this tick.
     *
     * [evaluate] is the measurement, this is the actuation. Keeping the two apart is what lets the
     * verdict be exported on every tick while the dose is only touched when [armed] is true. With
     * [armed] false this always returns false, so the tick behaves exactly as it did before the
     * guard existed.
     *
     * A user-initiated bolus is never withheld: the guard reads an automatic re-dose, and a person
     * asking for insulin is not that.
     */
    fun shouldWithhold(
        verdict: Verdict,
        armed: Boolean,
        isExplicitUserAction: Boolean,
        proposedUnits: Double,
    ): Boolean = verdict.block && armed && !isExplicitUserAction && proposedUnits > 0.0

    /**
     * Readings of `[nowMs - windowMinutes, nowMs]` in time order, cut at the first hole wider than
     * [maxGapMinutes] when walking back from the newest one. Null when nothing is usable.
     */
    private fun usableWindow(
        readings: List<Reading>,
        nowMs: Long,
        windowMinutes: Int,
        maxGapMinutes: Double,
    ): List<Reading>? {
        val start = nowMs - (windowMinutes * MS_PER_MINUTE).toLong()
        val inWindow = readings
            .filter { it.timeMs in start..nowMs && it.bgMgdl.isFinite() }
            .sortedBy { it.timeMs }
        if (inWindow.isEmpty()) return null

        var firstKept = inWindow.size - 1
        for (i in inWindow.size - 1 downTo 1) {
            val gap = (inWindow[i].timeMs - inWindow[i - 1].timeMs) / MS_PER_MINUTE
            if (gap > maxGapMinutes) break
            firstKept = i - 1
        }
        return inWindow.subList(firstKept, inWindow.size)
    }

    private fun empty(code: String, iobU: Double) = Verdict(
        block = false,
        reasonCode = code,
        reason = "$code iob=${aimiFmt2(iobU)}",
        peakMgdl = null,
        peakAgeMinutes = null,
        dropFromPeakMgdl = null,
        troughMgdl = null,
        reboundFromTroughMgdl = null,
        currentBgMgdl = null,
        iobU = iobU,
    )
}
