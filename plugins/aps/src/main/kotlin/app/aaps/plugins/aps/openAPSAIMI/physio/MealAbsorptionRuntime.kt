package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Minutes into the meal-absorption episode, for the meal-absorption basal overlay.
 *
 * The overlay used `MealAbsorptionMemory.lastActiveAtMs`, which the same tick has just refreshed, so its
 * runtime was always 0 min and its 0..120 min window never closed ("Meal absorption … (0 m)" on every
 * tick of every package). The episode age is `onsetAtMs`.
 *
 * Using it is behind `BooleanKey.OApsAIMIMealAbsorptionOnsetRuntime`, off by default: the episode itself
 * almost never closes today (median age 42 h on 2026-09-22 → 10-20), so the real age would remove ~96 %
 * of the overlays at once. Both values are exported until the episode is bounded.
 */
object MealAbsorptionRuntime {

    data class Result(
        /** Runtime the overlay uses. */
        val runtimeMin: Int,
        /** Old value, from `lastActiveAtMs` (or the mode runtimes without an episode). */
        val legacyMin: Int,
        /** Real episode age, `null` without an episode. */
        val onsetMin: Int?,
        val usedOnset: Boolean,
    )

    fun resolve(nowMs: Long, onsetAtMs: Long, lastActiveAtMs: Long, modeRuntimesMin: List<Long>, useOnset: Boolean): Result {
        val legacy = lastActiveAtMs.takeIf { it > 0L }?.let { ageMin(nowMs, it) }
            ?: modeRuntimesMin.maxOrNull()?.toInt() ?: 0
        val onset = onsetAtMs.takeIf { it > 0L }?.let { ageMin(nowMs, it) }
        val usedOnset = useOnset && onset != null
        return Result(
            runtimeMin = if (usedOnset) onset!! else legacy,
            legacyMin = legacy,
            onsetMin = onset,
            usedOnset = usedOnset,
        )
    }

    private fun ageMin(nowMs: Long, atMs: Long): Int = ((nowMs - atMs) / 60_000L).toInt().coerceAtLeast(0)
}
