package app.aaps.plugins.aps.openAPSAIMI.basal

/**
 * Spends a fixed budget of insulin as a basal floor after the user has declared a meal.
 *
 * ## Why it exists
 *
 * The loop waits for evidence before it opens its meal authority: `strongMealConfirmed` needs tree
 * meal evidence, a delivery priority, a phase that forces a rise, or a meal probability of 0.80.
 * That wait is real and it is the reason the early release exists at all. When the person says "I am
 * eating", the wait is pointless: the meal is a fact, not a hypothesis, and the rise of the next
 * ninety minutes is its consequence.
 *
 * This is the one gesture in the project that **raises** a dose. Every number here is therefore a
 * bound, and the gesture is opt-in, off by default, and cancelled by deleting the note.
 *
 * ## The shape
 *
 * The budget is spread **evenly** over [WINDOW_MINUTES], so the rate does not change while the window
 * runs down and the whole window delivers exactly the budget above profile — nothing accumulates and
 * nothing is left to spend. A flat rate is also what makes this a *front-loaded anticipation* rather
 * than a chase: one decision whose effect the person can watch, not twenty chances to re-dose before
 * the first one is visible.
 *
 * The result is a **floor**: the caller takes the larger of the loop's own rate and this one, so the
 * gesture never lowers what the loop had decided for its own reasons.
 *
 * ## Where it stands down
 *
 * Two states, and both are judgement rather than measurement — they are stated as such because this
 * gesture adds insulin and a conservative interlock costs little:
 *
 * - glucose under [MIN_GLUCOSE_MGDL] — one does not push extra insulin into someone already low,
 *   whatever they have just declared;
 * - glucose falling at [MAX_FALL_MGDL_PER_5MIN] or faster — the meal is not arriving the way the
 *   person expected, so the declaration has stopped being true.
 *
 * Any input that is not a usable number stands it down too: missing data never starts a dose.
 */
object AnticipationBasalFloor {

    /** How long the declared-meal floor lasts, in minutes. */
    const val WINDOW_MINUTES: Double = 30.0

    /** Under this glucose the floor stands down, whatever has been declared. */
    const val MIN_GLUCOSE_MGDL: Double = 80.0

    /** At this fall or faster the floor stands down: the declaration has stopped being true. */
    const val MAX_FALL_MGDL_PER_5MIN: Double = -3.0

    /**
     * The basal floor for this tick, in U/h, or null when the gesture does not apply.
     *
     * The caller must take `max(itsOwnRate, thisRate)` — this is a floor, never a cap.
     *
     * @param budgetU insulin to spend above profile over the whole window, in units.
     * @param elapsedMinutes minutes since the declaration.
     * @param profileBasalUph the profile rate for this hour.
     * @param maxBasalUph the ceiling the pump and the settings allow.
     * @param bgMgdl glucose now.
     * @param deltaMgdl5m change over the last 5 minutes.
     */
    fun floorRateUph(
        budgetU: Double,
        elapsedMinutes: Double,
        profileBasalUph: Double,
        maxBasalUph: Double,
        bgMgdl: Double,
        deltaMgdl5m: Double,
    ): Double? {
        if (!budgetU.isFinite() || !elapsedMinutes.isFinite() || !profileBasalUph.isFinite() ||
            !maxBasalUph.isFinite() || !bgMgdl.isFinite() || !deltaMgdl5m.isFinite()
        ) {
            return null
        }
        if (budgetU <= 0.0) return null
        if (elapsedMinutes < 0.0 || elapsedMinutes > WINDOW_MINUTES) return null
        if (bgMgdl < MIN_GLUCOSE_MGDL) return null
        if (deltaMgdl5m <= MAX_FALL_MGDL_PER_5MIN) return null

        val windowHours = WINDOW_MINUTES / 60.0
        val extraUph = budgetU / windowHours
        val requested = profileBasalUph + extraUph
        // The ceiling may sit under the profile on a badly set pump; a floor must never pull a rate
        // down, so the profile wins in that case.
        return requested.coerceAtMost(maxBasalUph).coerceAtLeast(profileBasalUph)
    }
}
