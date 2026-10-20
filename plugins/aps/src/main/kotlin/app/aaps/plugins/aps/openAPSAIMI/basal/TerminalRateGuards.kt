package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.math.max
import kotlin.math.min

/**
 * Two rate writers that skip every bound of `setTempBasal`. Both guards can only lower a rate.
 *
 * - The AI Auditor writes its TBR into the final result **after** `setTempBasal`, so no stack cap,
 *   maxSafe or terminal invariant ever saw it. It may still brake the loop; it may no longer raise it.
 *   On 2026-10-20 its six TBRs were echoes of the loop rate, so this changed nothing there.
 * - `forceExact` (legacy meal modes) returns before max_basal. Its only caller already asks for
 *   `meal_modes_MaxBasal` or max_basal, so the ceiling below only stops a future caller from going higher.
 */
object TerminalRateGuards {

    /** Rate to send: the auditor can lower the loop rate, never raise it or create one. */
    fun auditorTbr(loopRateUph: Double?, auditorRateUph: Double): Double? {
        if (loopRateUph == null) return null
        if (!auditorRateUph.isFinite() || auditorRateUph < 0.0) return loopRateUph
        return min(loopRateUph, auditorRateUph)
    }

    /** Ceiling of the legacy meal-mode rate: the meal-mode max basal when set, else max_basal. */
    fun forceExactCeiling(requestedUph: Double, profileMaxBasalUph: Double, mealModesMaxBasalUph: Double): Double {
        val ceiling = if (mealModesMaxBasalUph > 0.0) max(profileMaxBasalUph, mealModesMaxBasalUph) else profileMaxBasalUph
        return requestedUph.coerceIn(0.0, max(ceiling, 0.0))
    }
}
