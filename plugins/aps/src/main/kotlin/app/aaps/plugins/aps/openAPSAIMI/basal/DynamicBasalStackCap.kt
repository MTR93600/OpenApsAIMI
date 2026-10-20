package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.math.max

/**
 * Stops the dynamic basal controller from multiplying the basal up when the insulin already on board
 * can bring glucose under [HYPO_FLOOR_MGDL] on its own.
 *
 * ## Why this exists
 *
 * `DynamicBasalController` raises the basal with a multiplier of up to ×10 from the glucose error and
 * trend only. Neither its fallback path (`compute`, the "PI-Projected … Mult=" line of the narrative)
 * nor `calculateDynamicRate` reads insulin on board against glucose. Measured on 2026-09-01 → 10-15
 * (17,550 ticks), on the 596 ticks with a multiplier above ×1.05:
 * - when `IOB × ISF ≥ BG − 70` (214 ticks): glucose went under 70 within 3 h after 23 % of them;
 * - otherwise (382 ticks): after 3 %.
 * The first group holds 49 of the 59 lows and only 28 of the 138 real rises. This is a correlation, not
 * proof that the multiplier caused those lows.
 *
 * ## What it does, and what it never touches
 *
 * In that case the multiplier is held at ×1.0: the basal is not raised, it is not cut either. A
 * multiplier at or below 1.0 is never changed, so every brake of the controller stays as it is.
 *
 * Holding the multiplier does NOT bound a rate that was already high when it reached `setTempBasal`
 * (meal-absorption overlay, engine branches, forced early-meal TBR): `applied = true` can go with 4–6 U/h
 * at the pump. [BasalStackTerminalCap] applies the same rule to that final rate; it is a shadow by
 * default.
 *
 * It never acts while a meal is declared (an active meal mode, or a meal note within the last 3 h, 4 h
 * for high-carb). Measured on the same data: inside FCL meals it would have touched 13 ticks, with no low
 * after any of them, so there it would only remove insulin from a meal. The FCL basal floor is applied
 * after this controller anyway, and is never lowered by it.
 *
 * Pure: the caller owns the rate.
 */
object DynamicBasalStackCap {

    /** Glucose the insulin on board is compared with. Same value as the measurement above. */
    const val HYPO_FLOOR_MGDL = 70.0

    /** No cap this long after a declared meal (FCL, lunch, …). */
    const val DECLARED_MEAL_EXEMPT_MIN = 180L

    /** Same, after a meal declared high-carb. */
    const val HIGH_CARB_EXEMPT_MIN = 240L

    data class Verdict(
        /** The multiplier would be held at ×1.0 (armed or not). */
        val wouldCap: Boolean,
        /** It was held (wouldCap AND armed). */
        val applied: Boolean,
        /** Multiplier asked for, before the cap. */
        val multiplierIn: Double,
        /** Multiplier to use. */
        val multiplierOut: Double,
        /** `IOB × ISF`: how far the insulin on board can bring glucose down by itself. `null` without inputs. */
        val stackDropMgdl: Double?,
        /** A declared meal exempted this tick. */
        val declaredMealExempt: Boolean,
    )

    /**
     * True while a meal is declared: a meal mode is active now, or the last meal note is younger than
     * [DECLARED_MEAL_EXEMPT_MIN] ([HIGH_CARB_EXEMPT_MIN] for high-carb).
     */
    fun declaredMealActive(anyMealModeActive: Boolean, lastDeclaredMealAgeMin: Long?, lastDeclaredMealHighCarb: Boolean): Boolean {
        if (anyMealModeActive) return true
        val age = lastDeclaredMealAgeMin?.takeIf { it >= 0L } ?: return false
        return age < if (lastDeclaredMealHighCarb) HIGH_CARB_EXEMPT_MIN else DECLARED_MEAL_EXEMPT_MIN
    }

    fun evaluate(
        multiplier: Double,
        bgMgdl: Double,
        iobU: Double,
        isfMgdlPerU: Double?,
        declaredMealActive: Boolean,
        armed: Boolean,
    ): Verdict {
        val stackDrop = isfMgdlPerU
            ?.takeIf { it.isFinite() && it > 0.0 && iobU.isFinite() }
            ?.let { max(iobU, 0.0) * it }
        val wouldCap = multiplier > 1.0 &&
            !declaredMealActive &&
            bgMgdl.isFinite() &&
            stackDrop != null &&
            stackDrop >= bgMgdl - HYPO_FLOOR_MGDL
        val applied = wouldCap && armed
        return Verdict(
            wouldCap = wouldCap,
            applied = applied,
            multiplierIn = multiplier,
            multiplierOut = if (applied) 1.0 else multiplier,
            stackDropMgdl = stackDrop,
            declaredMealExempt = declaredMealActive,
        )
    }
}
