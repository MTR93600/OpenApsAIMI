package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshot
import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import kotlin.math.abs

/**
 * Answers one question: is this dose-feasibility veto resting on a prediction that hit its own floor?
 *
 * ## Why this exists
 *
 * The straight-line tube advisor refuses every dose rung when the predicted minimum sits under the
 * hypoglycaemia floor. That minimum comes from curves clamped at
 * [DoseTerminalSnapshot.NUMERIC_FLOOR_MGDL] (39 mg/dL), so a curve that reached its own limit reads as
 * "a low is coming" when it only means "the curve ran out of room". Measured on 2026-10-02 between
 * 21:09 and 21:19: glucose 142, 140 and 144 mg/dL, flat, predicted minimum 40.97, 40.22 and 39.00, and
 * the bolus channel was held at 0.05 U for fifteen minutes.
 *
 * ## What it is, and is not
 *
 * It is pure, and it decides nothing about insulin: it reports a verdict, and the caller owns the dose.
 * It reuses the conditions `DoseTerminalSnapshot.shouldLiftPlateauFloorArtefact` already encodes, and
 * it invents no constant.
 *
 * [Verdict.strict] is the only answer an opt-in key may act on. [Verdict.wide] is the same test WITHOUT
 * the [DoseTerminalSnapshot.PLATEAU_BG_MGDL] band, and **nothing reads it**: the ticks that motivated
 * this rule sat at 140-144 mg/dL, below that band, so widening it would be a therapy decision. The
 * field exists so the frequency below the band can be counted from a support package first.
 */
object TubeFloorArtefactRule {

    /**
     * How far above the claimed floor real glucose must sit before a floored prediction is called an
     * artefact, in mg/dL.
     *
     * At [DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL] + this, a prediction of 39 while glucose reads
     * 85 is still treated as a real warning. The two numbers it is built from are the project's own.
     *
     * **It is inert for [Verdict.strict] today**, and deliberately so: 45 + 40 = 85 sits far below
     * [DoseTerminalSnapshot.PLATEAU_BG_MGDL] (160), which the strict answer also requires, so the band
     * decides first. It exists for [Verdict.wide], the measurement-only answer, where it is the only
     * thing keeping a railed prediction at genuinely low glucose out of the count. If anyone ever lowers
     * the plateau band, this margin becomes load-bearing — measure it before that happens.
     */
    const val GLUCOSE_MARGIN_OVER_FLOOR_MGDL: Double = 40.0

    data class Verdict(
        val strict: Boolean,
        val wide: Boolean,
        val minPredUsedMgdl: Double,
    )

    /**
     * @param feasible the advisor's own verdict. A feasible outcome is never an artefact case: there is
     *   no veto to lift.
     * @param minPredUsedMgdl the predicted minimum the advisor refused on.
     * @param bgMgdl glucose now.
     * @param deltaMgdl5m change over the last 5 minutes.
     * @param sportActive true while a sport note is live — the rule always stands down.
     * @param postHypoActive true inside a post-hypoglycaemia window — the rule always stands down.
     */
    fun evaluate(
        feasible: Boolean,
        minPredUsedMgdl: Double,
        bgMgdl: Double,
        deltaMgdl5m: Double,
        sportActive: Boolean,
        postHypoActive: Boolean,
    ): Verdict {
        val neutral = Verdict(strict = false, wide = false, minPredUsedMgdl = minPredUsedMgdl)
        if (feasible) return neutral
        if (sportActive || postHypoActive) return neutral
        if (!bgMgdl.isFinite() || !deltaMgdl5m.isFinite() || !minPredUsedMgdl.isFinite()) return neutral
        // A flat trend only. A rise is not this rule's business, and a fall is a real warning.
        if (abs(deltaMgdl5m) > DoseTerminalSnapshot.PLATEAU_FLAT_DELTA_ABS_MGDL) return neutral
        if (deltaMgdl5m <= ClampPkpdScenarioReconcile.MAX_NEG_DELTA_MGDL) return neutral
        if (minPredUsedMgdl > DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL) return neutral
        // Real glucose must be nowhere near the floor the prediction claims to be heading for.
        if (bgMgdl <= DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL + GLUCOSE_MARGIN_OVER_FLOOR_MGDL) {
            return neutral
        }
        return Verdict(
            strict = bgMgdl >= DoseTerminalSnapshot.PLATEAU_BG_MGDL,
            wide = true,
            minPredUsedMgdl = minPredUsedMgdl,
        )
    }
}
