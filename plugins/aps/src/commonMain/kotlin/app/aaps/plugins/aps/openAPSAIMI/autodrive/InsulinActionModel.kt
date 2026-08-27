package app.aaps.plugins.aps.openAPSAIMI.autodrive

/**
 * How fast insulin already on board is lowering glucose, in mg/dL/min.
 *
 * ## Why this exists
 *
 * The same physical quantity was written three times, with three different constants and no shared
 * definition:
 *
 * | reader | expression | effective coefficient |
 * |---|---|---|
 * | `ContinuousStateEstimator` | `estimatedSI * 0.0012 * iob * bg` | 1.2e-4 |
 * | `MpcController`            | `estimatedSI * 0.05 * iob * bg`   | 5.0e-3 |
 * | `ControlBarrierShield`     | `estimatedSI * 0.05 * iob * bg`   | 5.0e-3 |
 *
 * A fourth number governed all of them without looking like it did: `AutoDriveState.createSafe` used
 * to floor `estimatedSI` at `0.1`, while the value handed to it is an ISF in mg/dL/U divided by
 * 10000 — about 0.004 for a typical profile. Every patient was therefore clamped to the same
 * sensitivity, 22 to 50 times above the value the caller passed, and the profile ISF stopped reaching
 * the controller and the barrier at all.
 *
 * Measured on a 17 068-row `autodrive_dataset.csv` (2026-02-28 to 2026-07-11): the median
 * `Estimated_SI` is exactly 0.1000, and on the 14 291 rows where the floor was active the barrier
 * intervened on 41.2 % of ticks and zeroed the whole dose on 37.7 %. On the 2 777 rows predating the
 * floor it intervened on 0.7 %. That gap is the floor, not a designed margin.
 *
 * ## The model
 *
 * Insulin on board is treated as decaying exponentially with a time constant [tauMin], and each unit
 * absorbed lowers glucose by the profile ISF. Glucose clearance is concentration-dependent, so the
 * effect scales with BG relative to [REFERENCE_BG_MGDL] — the Bergman minimal-model form the three
 * readers were already using:
 *
 * ```
 * effect = (isf / tau) * iob * (bg / REFERENCE_BG)
 * ```
 *
 * ## Calibration — read this before changing [MPC_TAU_MIN]
 *
 * [MPC_TAU_MIN] is **75 minutes**, which is not the physiological value. It is the value that
 * reproduces what the controller and the barrier did before this model existed, to within rounding,
 * at a profile ISF of 45 mg/dL/U. It was chosen so that unifying the expressions changes no dose by
 * itself; the honest range for the exponential tail of a rapid analogue is 150–200 minutes.
 *
 * Moving it to 150 halves the insulin term, which makes the barrier roughly twice as permissive.
 * That is a loosening of a safety constraint and must not be done without a replay and a measured
 * before/after on the corpus. Same measurement, from the rows above: median insulin term 1.67
 * mg/dL/min at 75 minutes, 0.83 at 150, 0.63 at 200.
 */
object InsulinActionModel {

    /** BG at which the model is calibrated, in mg/dL. */
    const val REFERENCE_BG_MGDL: Double = 120.0

    /**
     * Time constant used by [MpcController] and [ControlBarrierShield], in minutes.
     *
     * Reproduces the previous `estimatedSI * 0.05` against the old floored sensitivity at ISF 45.
     * See the calibration note on this object before changing it — it moves the dose.
     */
    const val MPC_TAU_MIN: Double = 75.0

    /**
     * Time constant used by [ContinuousStateEstimator], in minutes.
     *
     * Deliberately **not** [MPC_TAU_MIN]. Reproducing the previous `estimatedSI * 0.0012` requires
     * exactly 3125 minutes, i.e. the estimator has effectively been running with no insulin term at all
     * — which is why `Ra` behaves like `velocity + 0.015 * (bg - 100)` rather than like an absorption
     * rate.
     *
     * Aligning it with [MPC_TAU_MIN] is the right fix and it cannot be done here alone. The estimator
     * subtracts this term before forming its innovation, so raising it raises `Ra` by nearly the same
     * amount: on the corpus the term moves from a median of 0.04 to 1.67 mg/dL/min, which would put
     * `Ra` above the 0.6 / 0.7 / 0.8 gates on any tick with insulin on board and flat glucose. The
     * gates and the undeclared-COB estimator have to be re-tuned in the same change, against meal
     * data. Until then this constant keeps the estimator exactly where it was.
     */
    const val ESTIMATOR_TAU_MIN: Double = 3125.0

    /**
     * Lowest sensitivity accepted from a caller, as ISF in mg/dL/U.
     *
     * Replaces the `coerceAtLeast(0.1)` that used to sit in `AutoDriveState.createSafe` in the wrong
     * units. Bounds a nonsensical profile without silently substituting one patient's physiology for
     * another's.
     */
    const val MIN_ISF_MGDL_PER_U: Double = 5.0

    /** Highest sensitivity accepted from a caller, as ISF in mg/dL/U. */
    const val MAX_ISF_MGDL_PER_U: Double = 400.0

    /**
     * Rate at which [iobU] is lowering glucose, in mg/dL/min.
     *
     * @param isfMgdlPerU profile sensitivity in mg/dL per unit.
     * @param tauMin insulin action time constant — see the calibration note on this object.
     */
    fun effectMgdlPerMin(isfMgdlPerU: Double, iobU: Double, bgMgdl: Double, tauMin: Double): Double {
        if (!isfMgdlPerU.isFinite() || !iobU.isFinite() || !bgMgdl.isFinite()) return 0.0
        if (tauMin <= 0.0) return 0.0
        return (isfMgdlPerU / tauMin) * iobU * (bgMgdl / REFERENCE_BG_MGDL)
    }

    /**
     * The `siMetabolic` coefficient the barrier and the controller multiply by `iob * bg`.
     *
     * Kept as a separate entry point because `ControlBarrierShield` needs the coefficient on its own
     * for `lgh = -siMetabolic * bg`, not just the finished rate.
     */
    fun metabolicCoefficient(isfMgdlPerU: Double, tauMin: Double): Double {
        if (!isfMgdlPerU.isFinite() || tauMin <= 0.0) return 0.0
        return isfMgdlPerU / (tauMin * REFERENCE_BG_MGDL)
    }

    /**
     * Effective coefficient of the pre-unification code: `estimatedSI * 0.05` against a sensitivity
     * floored at 0.1. Equals [metabolicCoefficient] at ISF 45 with [MPC_TAU_MIN].
     */
    const val LEGACY_CONTROL_COEFFICIENT: Double = 0.005

    /**
     * Coefficient for the controller and the safety barrier — proportional to ISF, but never looser
     * than the behaviour it replaces.
     *
     * ## Why the floor is still here
     *
     * Making the coefficient proportional to the profile ISF is the correct physiology: a resistant
     * patient (low ISF) should be allowed more insulin than a sensitive one, and the clamp this
     * replaces gave everybody the same barrier. But proportionality necessarily **loosens** the
     * barrier for every patient below the reference ISF, and the barrier is a hypoglycaemia
     * constraint.
     *
     * Measured on 1 135 exported ticks from this deployment: the median profile ISF is 30, not 45, so
     * the unfloored coefficient would be 0.67× — a 33 % more permissive barrier on the median tick.
     * That is not a change to ship on arithmetic alone.
     *
     * So the floor keeps the guarantee that this refactor can only ever tighten: above the reference
     * ISF the barrier gets stricter, below it stays exactly where it was. Removing this floor is the
     * next step and it needs a replay with a measured before/after, not a one-line edit.
     */
    fun controlCoefficient(isfMgdlPerU: Double, tauMin: Double): Double =
        maxOf(metabolicCoefficient(isfMgdlPerU, tauMin), LEGACY_CONTROL_COEFFICIENT)

    /**
     * Converts the sensitivity carried on `AutoDriveState` back to an ISF in mg/dL/U.
     *
     * `estimatedSI` is historically `isf / 10000`. The scaling stays here rather than being repeated
     * at each reader, so there is one place to change when the field itself is renamed to carry the
     * ISF directly.
     */
    fun isfFromStateSi(stateSi: Double): Double = stateSi * 10000.0
}
