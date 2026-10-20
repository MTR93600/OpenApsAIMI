package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.math.max
import kotlin.math.min

/**
 * The [DynamicBasalStackCap] rule, applied to the rate itself at the end of `setTempBasal`.
 *
 * ## Why this exists
 *
 * [DynamicBasalStackCap] only holds the controller multiplier at ×1.0. The rate that reaches
 * `setTempBasal` can already be high: the meal-absorption overlay (8–10 × profile), the engine, the
 * forced early-meal TBR. On 2026-10-20 the export said `applied = true` while 4.6 U/h went to the pump.
 * This verdict says what the same rule would do to the rate: bring it back to the profile basal when
 * `IOB × ISF ≥ BG − 70`, outside a declared meal. It never goes under the profile basal.
 *
 * ## Why it is a shadow first
 *
 * Measured on 2026-09-02 → 10-20 (17,704 ticks): the ticks it would cap are followed by a low within
 * 3 h twice as often (28 % vs 13 %), so it marks risk. But it would remove almost the same share of
 * insulin before a low as before a good outcome (discrimination 1.34), and about 6.5 U/day. So it is
 * exported on every tick, and it changes the rate only when armed
 * (`BooleanKey.OApsAIMIBasalStackTerminalCap`, off by default).
 *
 * Pure: the caller owns the rate.
 */
object BasalStackTerminalCap {

    data class Verdict(
        /** The rate would be brought back to the profile basal (armed or not). */
        val wouldCap: Boolean,
        /** It was (wouldCap AND armed). */
        val applied: Boolean,
        val rateInUph: Double,
        /** Rate to use. */
        val rateOutUph: Double,
        /** The cap, when [wouldCap]. */
        val capUph: Double?,
        /** `IOB × ISF`, `null` without inputs. */
        val stackDropMgdl: Double?,
        /** `BG − 70`, `null` when glucose is not finite. */
        val bgMinusFloorMgdl: Double?,
        val declaredMealExempt: Boolean,
    )

    fun evaluate(
        rateUph: Double,
        profileBasalUph: Double,
        bgMgdl: Double,
        iobU: Double,
        isfMgdlPerU: Double?,
        declaredMealActive: Boolean,
        armed: Boolean,
    ): Verdict {
        val stackDrop = isfMgdlPerU
            ?.takeIf { it.isFinite() && it > 0.0 && iobU.isFinite() }
            ?.let { max(iobU, 0.0) * it }
        val bgMinusFloor = bgMgdl.takeIf { it.isFinite() }?.let { it - DynamicBasalStackCap.HYPO_FLOOR_MGDL }
        // Only a rate above the profile basal can be lowered: this never raises a rate.
        val aboveProfile = rateUph.isFinite() && profileBasalUph.isFinite() && profileBasalUph > 0.0 &&
            rateUph > profileBasalUph
        val wouldCap = aboveProfile &&
            !declaredMealActive &&
            stackDrop != null &&
            bgMinusFloor != null &&
            stackDrop >= bgMinusFloor
        val applied = wouldCap && armed
        return Verdict(
            wouldCap = wouldCap,
            applied = applied,
            rateInUph = rateUph,
            rateOutUph = if (applied) min(rateUph, profileBasalUph) else rateUph,
            capUph = if (wouldCap) profileBasalUph else null,
            stackDropMgdl = stackDrop,
            bgMinusFloorMgdl = bgMinusFloor,
            declaredMealExempt = declaredMealActive,
        )
    }
}
