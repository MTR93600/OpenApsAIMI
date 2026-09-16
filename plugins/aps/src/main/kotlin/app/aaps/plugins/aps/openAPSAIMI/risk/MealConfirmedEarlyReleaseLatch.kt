package app.aaps.plugins.aps.openAPSAIMI.risk

/**
 * Keeps the meal-confirmed early release (MCER) off once the post-peak tail of **its own episode**
 * has shown itself.
 *
 * ## The problem this solves
 *
 * MCER's promise is that it "reverts to the insulin-only floor as soon as the post-peak tail risk
 * appears, so it cannot set up a post-peak hypo". The three breakers that carry that promise are read
 * fresh on every tick and remember nothing, so a single tick where the absorption phase flips away
 * from `PEAK_CORRECTION` and the sensor reports a step up was enough to re-arm the release with a
 * large stack of insulin still working.
 *
 * Seen on 2026-09-15: peak at 13:52, then at 14:38 the phase left `PEAK_CORRECTION` for exactly one
 * tick while the sensor stepped from 216 to 240, all three breakers released together, and the loop
 * went back to the ceiling basal with 8.87 U on board.
 *
 * ## Why an episode, and not just a tail
 *
 * The first version of this latch tripped on **any** falling tick while MCER was merely enabled. A
 * fall is not a post-peak tail unless there was a peak, and there is no peak unless MCER armed. On
 * 2026-09-16 it therefore latched on an ordinary pre-meal descent — glucose drifting down from a
 * morning high at 1.71 U on board — and then held the release off for the whole of the next meal,
 * because neither way out is reachable while a meal rises: glucose never comes back under
 * `target + ` [BG_MARGIN_MGDL], and insulin on board only grows. Replayed over 14755 ticks, that
 * version was latched on 36 % of them, with episodes up to 165 minutes, and it would have blocked an
 * otherwise-armable release on 215 ticks. The field report measured the consequence: the bolus
 * channel starved to 0.1–0.5 U caps while the correction moved into the slow basal channel.
 *
 * So the latch is now bound to an episode. It arms nothing by itself: it only remembers that MCER
 * **armed**, and only then does a tail trip mean anything. A release ends the episode and forgets
 * both facts.
 *
 * ## The gesture
 *
 * Once the phase breaker or the falling breaker trips **inside an episode where MCER armed**, the
 * release stays off until the episode is genuinely over, which is either of:
 *
 *  - glucose back under `target + ` [BG_MARGIN_MGDL] — below that MCER could not arm anyway, so the
 *    episode is closed by MCER's own definition, or
 *  - insulin on board down to [IOB_RELEASE_FRACTION] of what it was when the latch tripped — the
 *    stack that made the tail dangerous has largely gone.
 *
 * The remembered stack is floored at [MIN_TRIP_IOB_U] so the second way out stays reachable: a trip
 * at 1.7 U used to set the threshold at 0.86 U, under the insulin any meal correction immediately
 * creates.
 *
 * The insulin breaker itself is deliberately **not** latched: its own headroom comes back on its own
 * as insulin decays, so latching it would keep MCER off for the rest of the day.
 *
 * ## Safety
 *
 * This can only keep an opt-in escalation switched off; it never raises a dose and never touches a
 * path that runs when the MCER key is off. [IOB_RELEASE_FRACTION] and [MIN_TRIP_IOB_U] are judgement,
 * not measurement: they were not fitted on data, and both are only reachable in the direction of less
 * insulin being withheld.
 */
object MealConfirmedEarlyReleaseLatch {

    /**
     * Glucose margin over target that closes the episode.
     *
     * Must stay equal to MCER's own arming margin: under `target + ` this value MCER cannot arm, so
     * there is nothing left to hold off.
     */
    const val BG_MARGIN_MGDL: Double = 20.0

    /** Share of the insulin on board at the trip that still counts as "the stack is working". */
    const val IOB_RELEASE_FRACTION: Double = 0.5

    /**
     * Smallest stack the latch will remember, in units.
     *
     * Without it a trip at a small stack sets a release threshold below the insulin that any meal
     * correction immediately creates, and the way out is closed for the rest of the excursion.
     */
    const val MIN_TRIP_IOB_U: Double = 3.0

    /**
     * What the caller carries from one tick to the next.
     *
     * @param latched true while MCER must stay off.
     * @param iobAtTripU insulin on board when the latch tripped, in units, floored at [MIN_TRIP_IOB_U].
     * @param armedSeen true once MCER has armed in this episode. Nothing can latch before it.
     */
    data class State(
        val latched: Boolean = false,
        val iobAtTripU: Double = 0.0,
        val armedSeen: Boolean = false,
    )

    /**
     * The state for this tick.
     *
     * Pure: it reads no clock and keeps nothing, so a test can replay a real trace tick by tick.
     *
     * `armedThisTick` and `tailTripped` cannot both be true: MCER's own `armed` requires
     * `!tailBreaker`, and the breaker contains both tail tests.
     *
     * @param previous the state carried from the last tick.
     * @param armedThisTick whether MCER armed on this tick.
     * @param tailTripped whether the phase breaker or the falling breaker fired on this tick.
     * @param bgMgdl glucose now.
     * @param targetBgMgdl the target this tick.
     * @param iobU insulin on board now.
     */
    fun next(
        previous: State,
        armedThisTick: Boolean = false,
        tailTripped: Boolean,
        bgMgdl: Double,
        targetBgMgdl: Double,
        iobU: Double,
    ): State {
        if (!previous.latched) {
            if (armedThisTick) return previous.copy(armedSeen = true)
            // A fall with no earlier arm is not a post-peak tail: there was no peak.
            if (!previous.armedSeen || !tailTripped) return previous
            val stack = if (iobU.isFinite()) iobU.coerceAtLeast(0.0) else 0.0
            return State(
                latched = true,
                iobAtTripU = stack.coerceAtLeast(MIN_TRIP_IOB_U),
                armedSeen = true,
            )
        }
        if (releases(previous, bgMgdl, targetBgMgdl, iobU)) return State()
        return previous
    }

    /** True when the episode that armed the latch is over. Split out so a test can name each way. */
    fun releases(
        state: State,
        bgMgdl: Double,
        targetBgMgdl: Double,
        iobU: Double,
    ): Boolean {
        if (!state.latched) return true
        val backNearTarget = bgMgdl.isFinite() && targetBgMgdl.isFinite() &&
            bgMgdl < targetBgMgdl + BG_MARGIN_MGDL
        if (backNearTarget) return true
        if (state.iobAtTripU <= 0.0) return false
        return iobU.isFinite() && iobU <= state.iobAtTripU * IOB_RELEASE_FRACTION
    }
}
