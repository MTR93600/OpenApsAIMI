package app.aaps.plugins.aps.openAPSAIMI.risk

/**
 * Keeps the meal-confirmed early release (MCER) off once the post-peak tail has shown itself.
 *
 * ## The problem this solves
 *
 * MCER's own promise is that it "reverts to the insulin-only floor as soon as the post-peak tail
 * risk appears, so it cannot set up a post-peak hypo". The three breakers that carry that promise
 * are read fresh on every tick and remember nothing, so a single tick where the absorption phase
 * flips away from `PEAK_CORRECTION` and the sensor reports a step up is enough to re-arm the release
 * with a large stack of insulin still working.
 *
 * Seen on 2026-09-15: peak at 13:52, then at 14:38 the phase left `PEAK_CORRECTION` for exactly one
 * tick while the sensor stepped from 216 to 240, all three breakers released together, and the loop
 * went back to the ceiling basal with 8.87 U on board.
 *
 * ## The gesture
 *
 * The tail breaker becomes a **latch**. Once the phase breaker or the falling breaker has tripped,
 * MCER stays off until the episode is genuinely over, which is either of:
 *
 *  - glucose back under `target + ` [BG_MARGIN_MGDL] — below that MCER could not arm anyway, so the
 *    episode is closed by MCER's own definition, or
 *  - insulin on board down to [IOB_RELEASE_FRACTION] of what it was when the latch tripped — the
 *    stack that made the tail dangerous has largely gone.
 *
 * The insulin-on-board breaker is deliberately **not** latched: its own headroom comes back on its
 * own as insulin decays, so latching it would keep MCER off for the rest of the day.
 *
 * ## Safety
 *
 * This can only keep an opt-in escalation switched off; it never raises a dose and never touches a
 * path that runs when the MCER key is off. [IOB_RELEASE_FRACTION] is a judgement, not a measurement:
 * it was not fitted on data, and it is only reachable in the direction of less insulin.
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
     * What the caller carries from one tick to the next.
     *
     * @param latched true while MCER must stay off.
     * @param iobAtTripU insulin on board when the latch tripped, in units.
     */
    data class State(
        val latched: Boolean = false,
        val iobAtTripU: Double = 0.0,
    )

    /**
     * The state for this tick.
     *
     * Pure: it reads no clock and keeps nothing, so a test can replay a real trace tick by tick.
     *
     * @param previous the state carried from the last tick.
     * @param tailTripped whether the phase breaker or the falling breaker fired on this tick.
     * @param bgMgdl glucose now.
     * @param targetBgMgdl the target this tick.
     * @param iobU insulin on board now.
     */
    fun next(
        previous: State,
        tailTripped: Boolean,
        bgMgdl: Double,
        targetBgMgdl: Double,
        iobU: Double,
    ): State {
        if (!previous.latched) {
            // A trip only means something with numbers behind it; missing data latches nothing.
            if (!tailTripped) return previous
            if (!iobU.isFinite()) return State(latched = true, iobAtTripU = 0.0)
            return State(latched = true, iobAtTripU = iobU.coerceAtLeast(0.0))
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
