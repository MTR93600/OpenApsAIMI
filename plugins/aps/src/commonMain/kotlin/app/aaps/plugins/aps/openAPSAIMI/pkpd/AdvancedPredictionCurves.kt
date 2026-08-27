package app.aaps.plugins.aps.openAPSAIMI.pkpd

/**
 * Distinct IOB / COB / UAM / ZT prediction paths for graphing and safety composite (Phase 4A).
 * [hybrid] matches legacy [AdvancedPredictionEngine.predict] — insulin + COB + UAM momentum combined.
 *
 * Wave4 H3: when endogenous reversion is enabled, insulin-only series share EGP with hybrid.
 * [insulinPathMinRawMgdl] / [insulinPathMinSoftMgdl] support JSON study (pre-EGP vs published).
 * Field correction 2026-07-22: Guard A caps the EGP anchor at current BG; Guard B suspends EGP on
 * hard falls ([endogenousReversionSuppressedByTrend]).
 */
data class AdvancedPredictionCurves(
    val iob: List<Double>,
    val cob: List<Double>,
    val uam: List<Double>,
    val zt: List<Double>,
    val hybrid: List<Double>,
    /** Min of insulin-only points before EGP lift (null if not tracked). */
    val insulinPathMinRawMgdl: Double? = null,
    /** Min of published insulin-only points after EGP (null if not tracked). */
    val insulinPathMinSoftMgdl: Double? = null,
    /** True when EGP actually raised at least one insulin-only step. */
    val endogenousReversionOnInsulinCurves: Boolean = false,
    /** Guard B — true when EGP was suspended for this tick because BG was falling hard. */
    val endogenousReversionSuppressedByTrend: Boolean = false,
    /**
     * Minimum of the insulin-only path **with the numeric floor removed**, so a published minimum of
     * 39 can be told apart from a genuine forecast of 39.
     *
     * The published paths clip every step into `[NUMERIC_FLOOR, NUMERIC_CEILING]`, and that clip is
     * absorbing: once a step lands on the floor the path stays there for the rest of the horizon. A
     * consumer reading the published minimum therefore cannot distinguish "this patient is predicted
     * to reach 39" from "the arithmetic ran off the bottom of the scale and stopped". Those two need
     * opposite responses, and the second is common during an undeclared meal, where the insulin-only
     * path subtracts `IOB x ISF` from the current BG with no appearance term on the other side.
     *
     * This value is diagnostic only. Nothing doses on it.
     */
    val insulinPathMinUnclippedMgdl: Double? = null,
    /** How many horizon steps were clipped at the numeric floor. 0 means the published min is real. */
    val numericFloorClippedSteps: Int = 0,
    /** The sensitivity (mg/dL/U) the engine actually integrated. Not always the commanded value. */
    val effectiveSensitivityUsedMgdlPerU: Double? = null,
) {
    val uamTerminal: Double? get() = uam.lastOrNull()?.takeIf { it.isFinite() }
    val cobTerminal: Double? get() = cob.lastOrNull()?.takeIf { it.isFinite() }
    val hybridTerminal: Double? get() = hybrid.lastOrNull()?.takeIf { it.isFinite() }
}
