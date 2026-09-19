package app.aaps.plugins.aps.openAPSAIMI.basal

/**
 * The FCL mode: a declared meal that forces the meal basal ceiling, and nothing else.
 *
 * ## What the person asked for
 *
 * The declared meal modes ("meal", "lunch", "dinner", "bfast", "highcarb") do two things at once:
 * they force the meal basal ceiling for thirty minutes **and** they send a prebolus whose size cannot
 * be set to zero. Someone who wants the basal without the bolus has no way to ask for it.
 *
 * FCL is that way. An "fcl" note plus a low temp target ask for the meal ceiling, and the prebolus
 * path is never touched, because the prebolus is keyed on the meal-mode keywords and "fcl" is not one
 * of them.
 *
 * ## Why the temp target is part of the gate
 *
 * The temp target is what **ends** the mode. A note window would need a duration, and a scenario that
 * writes a note with no duration would arm nothing — that is exactly how the first attempt failed on
 * 2026-09-17, when the only thing that reached the loop was the temp target itself. Binding the mode
 * to the temp target instead means the mode lasts as long as the target the person can see in the app,
 * and cancelling the target cancels the mode at once.
 *
 * It also makes the gate two independent manual acts. Neither a stale note nor a temp target set for
 * some other reason can force the ceiling on its own.
 *
 * ## Why it is a floor and not a mode branch
 *
 * The declared meal modes force their ceiling from a branch that **ends the tick**, so no bolus is
 * decided while they run: they send their prebolus first and then live on basal alone. FCL is applied
 * at the last point where the basal rate can still be raised, after the bolus stage has already run,
 * so **SMB stays active** for the whole window. That was asked for explicitly.
 *
 * The two channels do not talk to each other inside one tick: the bolus is decided before this floor
 * is applied, so it cannot know the basal is about to be raised. The coupling is real but late — the
 * insulin this floor delivers shows up as insulin on board on the next tick, which the bolus gate does
 * read. Together with a low temp target this is the most insulin the engine can be asked for, and
 * nothing here subtracts one channel from the other.
 *
 * The target must be **low**: over [MAX_TEMP_TARGET_MGDL] it is a target for eating soon or for
 * exercise, and those must never receive the meal ceiling. The ceiling is set a little over 80 mg/dL
 * so that a target written as 80 mg/dL, or as the mmol/L values around it, all count.
 *
 * ## Where it stands down
 *
 * The same two states as [AnticipationBasalFloor], for the same reason — this gesture adds insulin,
 * so a conservative interlock costs little:
 *
 * - glucose under [MIN_GLUCOSE_MGDL] — one does not push extra insulin into someone already low,
 *   whatever they have just declared;
 * - glucose falling at [MAX_FALL_MGDL_PER_5MIN] or faster — the meal is not arriving the way the
 *   person expected, so the declaration has stopped being true.
 *
 * And one more, which the declared-meal floor does not need: a live **sport** note. Two manual notes
 * that disagree are not a tie, and the one that withholds insulin is the one to trust.
 *
 * Any input that is not a usable number stands it down too: missing data never starts a dose.
 */
object FclMealBasal {

    /**
     * Highest temp target, in mg/dL, that counts as an FCL target.
     *
     * A little over 80 so that 80 mg/dL and the mmol/L values near it (4.4 = 79, 4.5 = 81, 4.6 = 83)
     * all count, while a plain 90 mg/dL target does not. Judgement, not measurement.
     */
    const val MAX_TEMP_TARGET_MGDL: Double = 85.0

    /** Under this glucose the mode stands down, whatever has been declared. */
    const val MIN_GLUCOSE_MGDL: Double = 80.0

    /** At this fall or faster the mode stands down: the declaration has stopped being true. */
    const val MAX_FALL_MGDL_PER_5MIN: Double = -3.0

    /**
     * Is an FCL meal declared right now: an "fcl" note, a low temp target, and no sport note.
     *
     * This is the **arming half** of the gate and nothing more — it says the person has declared the
     * meal, not that a dose is safe. Four places need it and they must not each re-spell it:
     *
     *  - [rateUph], which then adds its own glucose and fall stand-downs;
     *  - the terminal-invariants exemption, so a declared mode is not pulled back to the profile rate
     *    (`BasalTerminalInvariants` exempts declared meal modes by its own contract, and FCL is one);
     *  - the Autodrive gate, so its meal channel opens on a declared meal instead of waiting for a
     *    rise it cannot see yet;
     *  - the one-shot prebolus.
     *
     * Pure: it reads no clock and keeps no state.
     *
     * @param fclNoteActive whether an "fcl" note is live.
     * @param sportNoteActive whether a "sport" note is live. Two manual notes that disagree are not a
     *   tie: the one that withholds insulin wins, so this undeclares the meal.
     * @param tempTargetSet whether a temp target is running right now.
     * @param targetBgMgdl the target in force this tick, temp target included.
     */
    fun declared(
        fclNoteActive: Boolean,
        sportNoteActive: Boolean,
        tempTargetSet: Boolean,
        targetBgMgdl: Double,
    ): Boolean {
        if (!fclNoteActive) return false
        if (sportNoteActive) return false
        if (!tempTargetSet) return false
        if (!targetBgMgdl.isFinite()) return false
        return targetBgMgdl <= MAX_TEMP_TARGET_MGDL
    }

    /**
     * The basal rate FCL asks for this tick, in U/h, or null when the mode does not apply.
     *
     * This is a **floor**: the caller must never let it lower a rate the loop had already chosen for
     * its own reasons. The pump and the settings still cap it downstream, so the number returned here
     * is a request, not a promise.
     *
     * Pure: it reads no clock and keeps no state.
     *
     * @param fclNoteActive whether an "fcl" note is live.
     * @param sportNoteActive whether a "sport" note is live.
     * @param tempTargetSet whether a temp target is running right now.
     * @param targetBgMgdl the target in force this tick, temp target included.
     * @param mealModesMaxBasalUph the meal-mode basal ceiling from the settings.
     * @param profileMaxBasalUph the profile maximum, used when the meal ceiling is unset.
     * @param profileBasalUph the profile rate for this hour.
     * @param bgMgdl glucose now.
     * @param deltaMgdl5m change over the last 5 minutes.
     */
    fun rateUph(
        fclNoteActive: Boolean,
        sportNoteActive: Boolean,
        tempTargetSet: Boolean,
        targetBgMgdl: Double,
        mealModesMaxBasalUph: Double,
        profileMaxBasalUph: Double,
        profileBasalUph: Double,
        bgMgdl: Double,
        deltaMgdl5m: Double,
    ): Double? {
        if (!declared(fclNoteActive, sportNoteActive, tempTargetSet, targetBgMgdl)) return null
        if (!mealModesMaxBasalUph.isFinite() || !profileMaxBasalUph.isFinite() ||
            !profileBasalUph.isFinite() || !bgMgdl.isFinite() || !deltaMgdl5m.isFinite()
        ) {
            return null
        }
        if (bgMgdl < MIN_GLUCOSE_MGDL) return null
        if (deltaMgdl5m <= MAX_FALL_MGDL_PER_5MIN) return null

        // The meal ceiling is the number the person set for exactly this purpose. When it is unset the
        // profile maximum is the next honest bound.
        val ceiling = if (mealModesMaxBasalUph > 0.1) mealModesMaxBasalUph else profileMaxBasalUph
        // A badly set ceiling can sit under the profile rate; a floor must never pull a rate down.
        val requested = ceiling.coerceAtLeast(profileBasalUph)
        return requested.takeIf { it > 0.0 }
    }
}
