package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Minutes into the meal-absorption episode, for the meal-absorption basal overlay.
 *
 * The overlay used `MealAbsorptionMemory.lastActiveAtMs`, which the same tick has just refreshed, so its
 * runtime was always 0 min and its 0..120 min window never closed ("Meal absorption … (0 m)" on every
 * tick of every package). The runtime is now the episode age (`onsetAtMs`). That is meaningful because
 * the episode itself is bounded since 2026-10-21 (see [MealAbsorptionMemory]): it closes without meal
 * evidence, at a max age, and restarts on a new meal.
 */
object MealAbsorptionRuntime {

    data class Result(
        /** Runtime the overlay uses. */
        val runtimeMin: Int,
        /** Old value, from `lastActiveAtMs` (or the mode runtimes without an episode). Export only. */
        val legacyMin: Int,
        /** Real episode age, `null` without an episode. */
        val onsetMin: Int?,
        val usedOnset: Boolean,
    )

    fun resolve(nowMs: Long, onsetAtMs: Long, lastActiveAtMs: Long, modeRuntimesMin: List<Long>): Result {
        val modes = modeRuntimesMin.maxOrNull()?.toInt() ?: 0
        val legacy = lastActiveAtMs.takeIf { it > 0L }?.let { ageMin(nowMs, it) } ?: modes
        val onset = onsetAtMs.takeIf { it > 0L }?.let { ageMin(nowMs, it) }
        return Result(
            runtimeMin = onset ?: modes,
            legacyMin = legacy,
            onsetMin = onset,
            usedOnset = onset != null,
        )
    }

    private fun ageMin(nowMs: Long, atMs: Long): Int = ((nowMs - atMs) / 60_000L).toInt().coerceAtLeast(0)
}
