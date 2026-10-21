package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.math.max
import kotlin.math.min

/**
 * The "coffee" note: a morning rise from cortisol and caffeine, not from food.
 *
 * ## Why this exists
 *
 * In the first 30 minutes a coffee rise and an undeclared breakfast look the same on the sensor. On
 * 2026-10-21 (07:30 → 08:05, BG 102 → 155) the loop read it as a meal: the model asked about 1.9 U,
 * the autodrive floor and the restores delivered 7.6 U of SMB, the basal went to 4 then 7 U/h, and
 * glucose fell to 66 at 09:20. The person knows it is coffee; the note tells the loop.
 *
 * ## What it does while the note is live (and no meal is declared)
 *
 * - **SMB**: none while glucose is under the high-BG preference. Above it and still rising fast
 *   (Δ ≥ [MEAL_SUSPECT_DELTA_MGDL_5M]) it may be a breakfast after all, so the model may dose what it
 *   asks for, and nothing more: no floor, no restore.
 * - **Basal**: a band paid from a small budget ([BUDGET_U] over the window). The floor applies only while
 *   glucose is above `max(100, target + 10)`, rising, and the insulin on board cannot already bring it
 *   to 70. The ceiling applies on every tick, so no other writer can send 4–7 U/h again.
 * - It is **not** a declared meal: the stack caps stay active.
 *
 * Pure: the caller owns the rate and the dose.
 */
object CoffeeRiseMode {

    /** Window when the note has no duration. */
    const val DEFAULT_WINDOW_MIN = 90L

    /** Longest window, whatever duration the note carries. */
    const val MAX_WINDOW_MIN = 150L

    /** Insulin the basal floor may spend above the profile basal over the whole window. */
    const val BUDGET_U = 1.0

    /** No basal floor under this glucose, whatever the target. */
    const val MIN_FLOOR_BG_MGDL = 100.0

    /** Above the high BG, a rise this fast may be a breakfast: the model may dose. */
    const val MEAL_SUSPECT_DELTA_MGDL_5M = 4.0

    data class Verdict(
        val active: Boolean,
        /** Above the high BG and rising fast: the model's own SMB is allowed. */
        val mealSuspected: Boolean,
        val ageMin: Long?,
        val windowMin: Long,
        /** Rate the basal may not go under, `null` when the floor does not apply. */
        val basalFloorUph: Double?,
        /** Rate the basal may not go above, `null` when inactive or meal suspected. */
        val basalCeilingUph: Double?,
    )

    /** Note keywords of the mode. Not a meal keyword: a coffee note declares no meal. */
    val KEYWORDS = listOf("coffee", "cafe", "café")

    fun isCoffeeNote(note: String?): Boolean = note != null && KEYWORDS.any { note.contains(it, ignoreCase = true) }

    /** Window of a coffee note from its duration in minutes (0 = no duration). */
    fun windowMin(noteDurationMin: Long): Long =
        if (noteDurationMin <= 0L) DEFAULT_WINDOW_MIN else min(noteDurationMin, MAX_WINDOW_MIN)

    fun evaluate(
        noteAgeMin: Long?,
        windowMin: Long,
        declaredMealActive: Boolean,
        bgMgdl: Double,
        targetBgMgdl: Double,
        highBgMgdl: Double,
        deltaMgdl5m: Double,
        iobU: Double,
        isfMgdlPerU: Double?,
        profileBasalUph: Double,
        maxBasalUph: Double,
    ): Verdict {
        val active = noteAgeMin != null && noteAgeMin in 0L until windowMin && !declaredMealActive &&
            bgMgdl.isFinite() && profileBasalUph.isFinite() && profileBasalUph > 0.0
        if (!active) return Verdict(false, false, noteAgeMin, windowMin, null, null)

        val mealSuspected = bgMgdl >= highBgMgdl && deltaMgdl5m >= MEAL_SUSPECT_DELTA_MGDL_5M
        if (mealSuspected) return Verdict(true, true, noteAgeMin, windowMin, null, null)

        val windowH = windowMin / 60.0
        val maxBasal = if (maxBasalUph.isFinite() && maxBasalUph > 0.0) maxBasalUph else profileBasalUph
        val ceiling = min(profileBasalUph + 2.0 * BUDGET_U / windowH, maxBasal)
        val stackDrop = isfMgdlPerU
            ?.takeIf { it.isFinite() && it > 0.0 && iobU.isFinite() }
            ?.let { max(iobU, 0.0) * it }
        val floorApplies = bgMgdl >= max(MIN_FLOOR_BG_MGDL, targetBgMgdl + 10.0) &&
            deltaMgdl5m > 0.0 &&
            stackDrop != null &&
            stackDrop < bgMgdl - DynamicBasalStackCap.HYPO_FLOOR_MGDL
        val floor = if (floorApplies) min(profileBasalUph + BUDGET_U / windowH, ceiling) else null
        return Verdict(true, false, noteAgeMin, windowMin, floor, ceiling)
    }

    /** Most SMB allowed this tick, `null` when the mode does not apply. */
    fun smbCeilingU(v: Verdict, modelOutputU: Double?): Double? {
        if (!v.active) return null
        if (!v.mealSuspected) return 0.0
        return modelOutputU?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    }
}
