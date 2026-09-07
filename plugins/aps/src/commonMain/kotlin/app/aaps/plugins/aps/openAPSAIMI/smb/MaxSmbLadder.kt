package app.aaps.plugins.aps.openAPSAIMI.smb

import kotlin.math.max

/**
 * Chooses the maxSMB ceiling of a tick, and names the branch that chose it.
 *
 * The ladder answers one question: how much SMB may this tick deliver at most. It reads only the
 * numbers passed in, so the same inputs always give the same answer and the choice can be tested
 * without the 18 000-line loop class around it.
 *
 * The console lines and the BG below 120 safety clamp stay at the call site. This object only
 * decides, it does not log and it does not clamp.
 *
 * Source: `origin/dev_OAPSAIMI` @ `f03fa321a6` (file SHA `057813c03b`, unchanged on tip
 * `c5db5a0333`). Study already had this ladder **inline** in `DetermineBasalAIMI2` with the
 * slope-only rise rule. Comparison against the reference: `f03fa321a6` added a second proof
 * (`shortAvgDelta >= 8.0`) into the confirmed-rise branch. This lot takes **that** rule, then
 * extracts — it does not keep a second parallel ladder.
 *
 * Study wiring: commonMain `internal object` (pure, no instance state, one tick consumer). No Metro
 * `@Inject`. Sibling `SmbIntervalPolicy` is the same shape; a private field is for stateful meters
 * such as [app.aaps.plugins.aps.openAPSAIMI.ISF.DynIsfCache], not for a stateless decide().
 */
internal object MaxSmbLadder {

    /**
     * Stable tags naming which branch of the maxSMB ladder picked the ceiling for a tick.
     *
     * Exported as `smb_binding_trace.max_smb_ladder_branch`. The rise floor obeys the ceiling this
     * ladder picks, so the tag is what tells us afterwards whether the ladder saw a rise (a promoted
     * or partial branch) or saw nothing at all ([LADDER_STANDARD]). The two readings mean very
     * different things.
     *
     * The `_CLAMPED` twins mean the branch fired and the BG below 120 safety clamp then pulled the
     * ceiling back down to the standard preference. Without them the tag would report a promotion
     * that never reached the dose. [LADDER_STANDARD] has no twin: it already sets the standard
     * preference, so the clamp cannot lower it further.
     */
    const val LADDER_PLATEAU_CRITICAL = "PLATEAU_CRITICAL_BG250"
    const val LADDER_PLATEAU_CRITICAL_CLAMPED = "PLATEAU_CRITICAL_BG250_CLAMPED"
    const val LADDER_CONFIRMED_RISE_HIGH = "CONFIRMED_RISE_HIGH"
    const val LADDER_CONFIRMED_RISE_HIGH_CLAMPED = "CONFIRMED_RISE_HIGH_CLAMPED"

    /**
     * The confirmed rise was opened by [RISE_SHORT_DELTA_THRESHOLD], not by the deviation slope.
     *
     * Kept apart from [LADDER_CONFIRMED_RISE_HIGH] so the next support package can count exactly the
     * ticks this criterion added, and nothing else.
     */
    const val LADDER_CONFIRMED_RISE_HIGH_BY_DELTA = "CONFIRMED_RISE_HIGH_BY_DELTA"
    const val LADDER_CONFIRMED_RISE_HIGH_BY_DELTA_CLAMPED = "CONFIRMED_RISE_HIGH_BY_DELTA_CLAMPED"
    const val LADDER_SENSITIVE_85 = "SENSITIVE_85"
    const val LADDER_SENSITIVE_85_CLAMPED = "SENSITIVE_85_CLAMPED"
    const val LADDER_PLATEAU_MODERATE_75 = "PLATEAU_MODERATE_75"
    const val LADDER_PLATEAU_MODERATE_75_CLAMPED = "PLATEAU_MODERATE_75_CLAMPED"
    const val LADDER_FALLING_60 = "FALLING_60"
    const val LADDER_FALLING_60_CLAMPED = "FALLING_60_CLAMPED"
    const val LADDER_STANDARD = "STANDARD"

    /**
     * Second way into the confirmed-rise branch: the plain 15 minute average delta, mg/dL per 5 min.
     *
     * `slopeFromMinDeviation` is an oref0 carb absorption estimate, smoothed over 20 to 40 minutes,
     * and its denominator grows while BG is climbing. It was never built to answer "is BG going up
     * right now", and on 24 hours of production data it is at or above 1.0 only 10 % of the time,
     * with a median of 0.02. Asking it that question is what lost 13 minutes on the undeclared meal
     * of 2026-09-05.
     *
     * `shortAvgDelta` is an average of the deltas over the last 15 minutes. It subtracts no BGI and
     * needs no hour of history, so it answers the question directly. 8.0 mg/dL per 5 min is about
     * 96 mg/dL per hour: there is no room for doubt about the direction.
     *
     * Measured on the same 24 hours of production: this threshold opens 14 extra ticks, all of them
     * at BG at or above 141, delta at or above 7.4, and no carbs on board. None of them was falling.
     */
    const val RISE_SHORT_DELTA_THRESHOLD = 8.0

    /**
     * The ceiling this ladder picked, and the name of the branch that picked it.
     *
     * @property ceilingU maxSMB ceiling for the tick, in units.
     * @property branch one of the `LADDER_*` tags above.
     */
    data class Decision(val ceilingU: Double, val branch: String)

    /**
     * Picks the maxSMB ceiling for one tick.
     *
     * The branches are tried in order and the first match wins:
     *  1. BG at or above 250 and not falling fast — full high-BG ceiling.
     *  2. Confirmed rise at high BG, seen by the deviation slope or by the 15 min average
     *     delta — full high-BG ceiling.
     *  3. Confirmed rise between 120 and 140 — 85 % of the high-BG ceiling.
     *  4. Plateau between 200 and 250 with a small delta — 75 % of the high-BG ceiling.
     *  5. High BG falling moderately — 60 % of the high-BG ceiling.
     *  6. Anything else — the standard preference.
     *
     * @param bgMgdl current glucose, mg/dL.
     * @param combinedDelta the tick's blended glucose delta, mg/dL per 5 min.
     * @param slopeFromMinDeviation the oref0 carb absorption estimate.
     * @param shortAvgDeltaMgdl5m average of the deltas over the last 15 min, mg/dL per 5 min.
     * @param honeymoon true when the user set the honeymoon preference.
     * @param maxSmb the standard maxSMB preference, in units.
     * @param maxSmbHighBg the high-BG maxSMB preference, in units.
     */
    fun decide(
        bgMgdl: Double,
        combinedDelta: Double,
        slopeFromMinDeviation: Double,
        shortAvgDeltaMgdl5m: Double,
        honeymoon: Boolean,
        maxSmb: Double,
        maxSmbHighBg: Double,
    ): Decision = when {

        // CRITICAL PLATEAU: BG >= 250, regardless of slope.
        // Absolute emergency if BG catastrophic, even with low delta.
        // Protection: do not apply if rapid fall (delta <= -5).
        bgMgdl >= 250 && combinedDelta > -5.0 ->
            Decision(maxSmbHighBg, LADDER_PLATEAU_CRITICAL)

        // ACTIVE RISE HIGH: BG >= 140 (meal interception zone).
        // Full high-BG ceiling for a confirmed meal or resistance in the elevated range.
        // The combinedDelta check confirms the rise is real.
        // Two ways in, on purpose: the deviation slope, and the plain 15 min average delta. See
        // [RISE_SHORT_DELTA_THRESHOLD] for why the slope alone is not enough.
        (bgMgdl >= 140 && !honeymoon &&
            (slopeFromMinDeviation >= 1.0 || shortAvgDeltaMgdl5m >= RISE_SHORT_DELTA_THRESHOLD) &&
            combinedDelta > 0.5) ||
            (bgMgdl >= 180 && honeymoon && slopeFromMinDeviation >= 1.4 && combinedDelta > 0.5) -> {
            // Attribution must be exact: only the ticks the new criterion opened may carry the new
            // tag, or the next support package cannot measure what the change did.
            val openedByDeltaOnly = !honeymoon && slopeFromMinDeviation < 1.0
            val branch =
                if (openedByDeltaOnly) LADDER_CONFIRMED_RISE_HIGH_BY_DELTA
                else LADDER_CONFIRMED_RISE_HIGH
            Decision(maxSmbHighBg, branch)
        }

        // ACTIVE RISE SENSITIVE: BG 120-140 (near target zone).
        // 85 % of the high-BG ceiling for extra caution close to target.
        bgMgdl >= 120 && bgMgdl < 140 && !honeymoon && slopeFromMinDeviation >= 1.0 && combinedDelta > 0.5 ->
            Decision(max(maxSmb, maxSmbHighBg * 0.85), LADDER_SENSITIVE_85)

        // MODERATE PLATEAU: BG 200-250, stable delta.
        // Compromise: 75 % of the high-BG ceiling for elevated but not critical BG.
        bgMgdl >= 200 && bgMgdl < 250 && combinedDelta > -3.0 && combinedDelta < 3.0 ->
            Decision(max(maxSmb, maxSmbHighBg * 0.75), LADDER_PLATEAU_MODERATE_75)

        // FALLING PROTECTION: BG elevated but falling moderately.
        // Partial limit to avoid over-correction while still allowing some action.
        bgMgdl > 180 && combinedDelta <= -3.0 && combinedDelta > -8.0 ->
            Decision(max(maxSmb, maxSmbHighBg * 0.6), LADDER_FALLING_60)

        // STANDARD: normal or low BG conditions.
        else ->
            Decision(maxSmb, LADDER_STANDARD)
    }
}
