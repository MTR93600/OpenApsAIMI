package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Decides from the glucose curve itself if a tick is clean enough to learn DIA and peak from.
 *
 * Why it exists: with COB = 0 (UAM, or meals declared with a mode and no carbs) the estimator's own
 * carb check (`carbsActiveG > 15`) cannot see a meal. `CausalKineticsModulator` refuses ticks where a
 * meal is the DOMINANT causal state, but on two UAM days the ticks that still learned were labelled
 * DAWN or STRESS, and 10–16 % of them had a rise in the hour before: an unannounced carb source made
 * insulin look slower and weaker than it is. The causal labels cannot be trusted for this: FAST_MEAL
 * stayed dominant for hours, and every other tick was labelled DAWN or STRESS.
 *
 * So this gate reads the glucose curve and the meal declarations, not the labels. It accepts a tick
 * only when:
 * - glucose has not risen for a full window and is not rising now;
 * - the meal belief is not clearly up. That belief has a background plateau at about 0.40, so the veto
 *   starts at [MEAL_VETO], never at 0.40;
 * - no meal was declared in the last [DECLARED_MEAL_BLOCK_MIN] min ([HIGH_CARB_BLOCK_MIN] for a
 *   high-carb meal). This covers the time between the declaration and the visible rise, which the
 *   curve cannot see (2026-10-06: two learning ticks 2 and 7 min after an FCL prebolus);
 * - no meal-sized rise ([RISE_DETECT_MGDL] in [RISE_DETECT_SPAN_MIN] min) started in the last
 *   [DETECTED_RISE_BLOCK_MIN] min. This is the UAM version of the declaration, and it also covers the
 *   slow end of absorption after the peak, when glucose falls but carbs still slow the fall
 *   (2026-10-06: 13 learning ticks 95–150 min after an undeclared rise).
 *
 * Few ticks pass (1–23 % of a day in the replays). That is the point: a few clean samples are better
 * than many biased ones, and the estimator's per-sample step is unchanged, so learning simply gets
 * slower. It never gets faster.
 *
 * Holds a short delta history. Feed it only from the one path that may learn ([record]), once per
 * tick. Not thread-safe by itself: the caller holds its own lock.
 */
internal class PkpdLearningWindowGate {

    data class Verdict(
        val pass: Boolean,
        /** Why the tick was refused, `null` when it passed. One of the `BLOCK_*` values. */
        val blockedBy: String?,
        /** Largest 5-min delta seen in the window before this tick, `null` with no history. */
        val maxRecentDeltaMgdl: Double?,
        /** Minutes of history the window really covers. */
        val coveredMin: Long,
        /** Minutes since the last declared meal, `null` when none within the lookback. */
        val declaredMealAgeMin: Long? = null,
        /** Minutes since the last detected meal-sized rise started, `null` when none is remembered. */
        val detectedRiseAgeMin: Long? = null,
    )

    private val history = ArrayDeque<Sample>()

    /** Start of the last meal-sized rise. Kept apart from [history], which is much shorter. */
    private var lastRiseStartMin: Long? = null

    private data class Sample(val epochMin: Long, val deltaMgDlPer5: Double, val bgMgdl: Double)

    /**
     * Adds this tick. A second call for the same minute replaces the first one. [bgMgdl] feeds the
     * rise detector; a non-finite glucose is kept for the delta tests and ignored by the detector.
     */
    fun record(epochMin: Long, deltaMgDlPer5: Double, bgMgdl: Double = Double.NaN) {
        if (!deltaMgDlPer5.isFinite()) return
        if (history.lastOrNull()?.epochMin == epochMin) history.removeLast()
        // A clock that went back makes the old history meaningless: start again.
        if (history.isNotEmpty() && epochMin < history.last().epochMin) clear()
        detectRiseStart(epochMin, bgMgdl)
        history.addLast(Sample(epochMin, deltaMgDlPer5, bgMgdl))
        while (history.isNotEmpty() && epochMin - history.first().epochMin > HISTORY_KEEP_MIN) {
            history.removeFirst()
        }
    }

    /**
     * Marks a meal start when glucose rose [RISE_DETECT_MGDL] or more over about [RISE_DETECT_SPAN_MIN]
     * min. One start per episode: a rise that goes on keeps its first start until
     * [RISE_EPISODE_GAP_MIN] min have passed, then a still-rising curve opens a new one.
     */
    private fun detectRiseStart(epochMin: Long, bgMgdl: Double) {
        if (!bgMgdl.isFinite()) return
        val then = history.lastOrNull {
            val age = epochMin - it.epochMin
            age in (RISE_DETECT_SPAN_MIN - RISE_DETECT_SLACK_MIN)..(RISE_DETECT_SPAN_MIN + RISE_DETECT_SLACK_MIN) &&
                it.bgMgdl.isFinite()
        } ?: return
        if (bgMgdl - then.bgMgdl < RISE_DETECT_MGDL) return
        val last = lastRiseStartMin
        if (last == null || epochMin - last > RISE_EPISODE_GAP_MIN) lastRiseStartMin = epochMin
    }

    /**
     * Judges this tick against the history recorded BEFORE it. Does not change the history.
     *
     * @param declaredMealAgeMin minutes since the last declared meal note, `null` when none.
     * @param declaredMealHighCarb that meal was declared high-carb: its block lasts longer.
     */
    fun evaluate(
        epochMin: Long,
        deltaMgDlPer5: Double,
        mealConfidence: Double?,
        declaredMealAgeMin: Long? = null,
        declaredMealHighCarb: Boolean = false,
    ): Verdict {
        val window = history.filter { it.epochMin < epochMin && epochMin - it.epochMin <= WINDOW_MIN }
        val coveredMin = window.firstOrNull()?.let { epochMin - it.epochMin } ?: 0L
        val maxRecent = window.maxOfOrNull { it.deltaMgDlPer5 }
        val declaredBlockMin = if (declaredMealHighCarb) HIGH_CARB_BLOCK_MIN else DECLARED_MEAL_BLOCK_MIN
        val declaredAge = declaredMealAgeMin?.takeIf { it >= 0L }
        val riseAge = lastRiseStartMin?.let { epochMin - it }?.takeIf { it >= 0L }
        val blockedBy = when {
            !deltaMgDlPer5.isFinite()                    -> BLOCK_NO_DELTA
            declaredAge != null && declaredAge < declaredBlockMin -> BLOCK_DECLARED_MEAL
            (mealConfidence ?: 0.0) >= MEAL_VETO         -> BLOCK_MEAL_BELIEF
            deltaMgDlPer5 > MAX_DELTA_NOW_MGDL5          -> BLOCK_RISING_NOW
            window.size < MIN_SAMPLES || coveredMin < MIN_COVERED_MIN -> BLOCK_HISTORY_SHORT
            maxRecent != null && maxRecent > MAX_RECENT_RISE_MGDL5 -> BLOCK_RECENT_RISE
            riseAge != null && riseAge < DETECTED_RISE_BLOCK_MIN -> BLOCK_DETECTED_RISE
            else                                         -> null
        }
        return Verdict(
            pass = blockedBy == null,
            blockedBy = blockedBy,
            maxRecentDeltaMgdl = maxRecent,
            coveredMin = coveredMin,
            declaredMealAgeMin = declaredAge,
            detectedRiseAgeMin = riseAge,
        )
    }

    fun clear() {
        history.clear()
        lastRiseStartMin = null
    }

    companion object {

        /** Glucose must not have risen during this many minutes before the tick. */
        const val WINDOW_MIN = 60L

        /** The window must really be covered: a loop gap is not a quiet hour. */
        const val MIN_COVERED_MIN = 50L
        const val MIN_SAMPLES = 6

        /** History older than this is dropped. A bit more than [WINDOW_MIN] so the edge is kept. */
        private const val HISTORY_KEEP_MIN = WINDOW_MIN + 10L

        /** Largest 5-min delta allowed in the window (mg/dL per 5 min). Above it a carb source was active. */
        const val MAX_RECENT_RISE_MGDL5 = 2.0

        /** Largest 5-min delta allowed on the tick itself. */
        const val MAX_DELTA_NOW_MGDL5 = 1.0

        /** Meal belief at or above this blocks. Above the ~0.40 background plateau on purpose. */
        const val MEAL_VETO = 0.50

        /**
         * Causal learning quality needed when this gate is armed. Lower than
         * `CausalStatePosterior.LEARNING_QUALITY_MIN` because the curve test above now does the
         * work that score could not do; the score only still removes a really poor sensor.
         */
        const val QUALITY_MIN_WHEN_ARMED = 0.30

        const val BLOCK_NO_DELTA = "no_delta"
        const val BLOCK_MEAL_BELIEF = "meal_belief"
        const val BLOCK_RISING_NOW = "rising_now"
        const val BLOCK_HISTORY_SHORT = "history_short"
        const val BLOCK_RECENT_RISE = "recent_rise"
        const val BLOCK_DECLARED_MEAL = "declared_meal"
        const val BLOCK_DETECTED_RISE = "detected_rise"

        /** No learning this long after a declared meal (FCL, lunch, …): a usual meal acts about 3 h. */
        const val DECLARED_MEAL_BLOCK_MIN = 180L

        /** Same, for a meal declared high-carb: absorption lasts longer. */
        const val HIGH_CARB_BLOCK_MIN = 240L

        /** A rise of this size over [RISE_DETECT_SPAN_MIN] min is taken as a meal start. */
        const val RISE_DETECT_MGDL = 30.0
        const val RISE_DETECT_SPAN_MIN = 30L
        private const val RISE_DETECT_SLACK_MIN = 5L

        /** A rise still going on after this long opens a new episode, which extends the block. */
        const val RISE_EPISODE_GAP_MIN = 90L

        /** No learning this long after a detected rise started: covers the slow end of absorption. */
        const val DETECTED_RISE_BLOCK_MIN = 180L
    }
}
