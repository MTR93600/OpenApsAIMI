package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngine
import kotlin.math.abs
import kotlin.math.max

/**
 * Replaces the hyper-reversion clamp value with a physical bound, for the straight-line tube only.
 *
 * ## Why this exists
 *
 * In clear hyper (BG at or above [AdvancedPredictionEngine.HYPER_REVERSION_LEVEL_MGDL]) the PK/PD
 * hyper reversion holds every soft curve at [AdvancedPredictionEngine.ENDO_REVERSION_BASELINE_MGDL]
 * (80 mg/dL). The tube then reads exactly 80 as "the predicted minimum". That number does not depend on
 * insulin on board at all, and it is wrong in both directions:
 * - **too strict on a low-IOB meal rise.** With a floor of 76 the tube has 4 mg/dL of room, and SMB is
 *   held at 0.05 U while glucose climbs to 280 (field report, 2026-10-05, 13 ticks).
 * - **too permissive with a large stack.** The clamp switches on when BG crosses 160, whatever the IOB.
 *   On 2026-10-06 the tube flipped VETO ↔ GRADED 11 times on BG 159.9 ↔ 160.4 with 9–11 U on board,
 *   and on the 21 clamped ticks of that day IOB was 3.6–10.9 U. Two of those episodes ended at 68 and
 *   79 mg/dL.
 *
 * ## What it does
 *
 * When the tube's min-pred is the clamp value, it is replaced by `BG − IOB × ISF`: the glucose that the
 * whole insulin on board would reach on its own. It ignores carbs and liver output, which only push
 * glucose up, so it is a cautious bound. The ISF is the larger (more sensitive) of the tube ISF and the
 * dose ISF, because sensitivity comes back as glucose falls. Then:
 * - the stack cannot reach the floor → the tube gets real room, and its own κ test still sizes the SMB;
 * - the stack can reach the floor → the bound is under the floor and the tube vetoes.
 *
 * It never touches the prediction curves, so basal, eventual BG and every other reader are unchanged.
 * Pure: the caller owns the dose.
 */
object HyperClampTubeBound {

    /** A min-pred this close to the clamp value, in clear hyper, is the clamp. */
    const val CLAMP_MATCH_TOLERANCE_MGDL = 0.5

    data class Verdict(
        /** The tube min-pred is the hyper-reversion clamp value. */
        val clampDetected: Boolean,
        /** The bound replaced the clamp value (detected AND armed). */
        val applied: Boolean,
        /** What the tube must use: the bound when [applied], else the unchanged min-pred. */
        val minPredForTubeMgdl: Double,
        /** `BG − IOB × ISF`, `null` when the clamp was not detected. Exported armed or not. */
        val physicalMinMgdl: Double? = null,
        /** The ISF used for [physicalMinMgdl]. */
        val isfUsedMgdlPerU: Double? = null,
        /** The insulin on board can bring glucose under the tube floor on its own. */
        val stackCanBreachFloor: Boolean? = null,
        /**
         * Export only: the same bound with the dose ISF alone. The rule uses the larger ISF to stay
         * cautious; this value shows how much a less cautious choice would have released.
         */
        val physicalMinDoseIsfMgdl: Double? = null,
    )

    /**
     * @param minPredMgdl the min-pred the tube would use.
     * @param bgMgdl glucose now.
     * @param iobU insulin on board now.
     * @param tubeIsfMgdlPerU the ISF the tube reasons with.
     * @param doseIsfMgdlPerU the ISF the dose path uses, `null` when unknown.
     * @param hypoFloorMgdl the tube floor.
     * @param clampPossible both PK/PD reversions are on, so the engine can hold curves at 80.
     * @param armed the key: when false the verdict is computed and exported, but not applied.
     */
    fun evaluate(
        minPredMgdl: Double,
        bgMgdl: Double,
        iobU: Double,
        tubeIsfMgdlPerU: Double,
        doseIsfMgdlPerU: Double?,
        hypoFloorMgdl: Double,
        clampPossible: Boolean,
        armed: Boolean,
    ): Verdict {
        val unchanged = Verdict(clampDetected = false, applied = false, minPredForTubeMgdl = minPredMgdl)
        if (!clampPossible) return unchanged
        if (!minPredMgdl.isFinite() || !bgMgdl.isFinite() || !hypoFloorMgdl.isFinite()) return unchanged
        if (bgMgdl < AdvancedPredictionEngine.HYPER_REVERSION_LEVEL_MGDL) return unchanged
        if (abs(minPredMgdl - AdvancedPredictionEngine.ENDO_REVERSION_BASELINE_MGDL) > CLAMP_MATCH_TOLERANCE_MGDL) {
            return unchanged
        }
        val isf = listOfNotNull(tubeIsfMgdlPerU, doseIsfMgdlPerU)
            .filter { it.isFinite() && it > 0.0 }
            .maxOrNull()
        // Fail closed: without a usable IOB or ISF the bound cannot be built. Keep the clamp value,
        // which is what the tube used before this rule.
        if (isf == null || !iobU.isFinite()) {
            return unchanged.copy(clampDetected = true)
        }
        val physicalMin = bgMgdl - max(iobU, 0.0) * isf
        return Verdict(
            clampDetected = true,
            applied = armed,
            minPredForTubeMgdl = if (armed) physicalMin else minPredMgdl,
            physicalMinMgdl = physicalMin,
            isfUsedMgdlPerU = isf,
            stackCanBreachFloor = physicalMin < hypoFloorMgdl,
            physicalMinDoseIsfMgdl = doseIsfMgdlPerU
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { bgMgdl - max(iobU, 0.0) * it },
        )
    }
}
