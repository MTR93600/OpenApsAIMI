package app.aaps.plugins.aps.openAPSAIMI.prediction

import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import kotlin.math.roundToLong

/**
 * PR_NegativeIobEbgInflation: when AAPS [iobUnits] drifts negative, the legacy formula
 * `bg - iob * sens` inverts sign and inflates naive eventual BG.  Two collapse paths:
 *
 * 1. **Peak-activity path** (original): fires when PKPD reports ≥ [MIN_PKPD_RELATIVE_ACTIVITY]
 *    and stage is PEAK/RISING — structural disagreement between the bilinear IOB model and PKPD.
 *
 * 2. **Post-hypo path** (added): fires when [minBgLookback75mMgdl] < [POST_HYPO_BG_THRESHOLD].
 *    The negative-IOB artifact is most pronounced exactly when PKPD activity is LOW (TAIL/EXHAUSTED,
 *    ~1–4 %) after a recent hypo, and the guard's peak-activity precondition is false — so path 1
 *    cannot fire even though the sign-flip is real and inflates the eventual to 300+ mg/dL.
 *    In this window, collapsing naive eventual to `bg` is always safer than letting the inflate stand.
 *
 * Rounding matches `DetermineBasalAIMI2.round` with digits = 0 (nearest long).
 */
internal data class NaiveEventualBgResolution(
    val naiveEventualBgMgdl: Double,
    val signGuardApplied: Boolean,
    val rawNaiveRoundedMgdl: Double,
    /** Human-readable reason for the guard firing, null if guard did not apply. */
    val collapseReason: String? = null,
)

internal object NaiveEventualBgSignGuard {

    const val MIN_PKPD_RELATIVE_ACTIVITY: Double = 0.70

    /** Min BG in the lookback window below which the post-hypo collapse path activates (mg/dL). */
    const val POST_HYPO_BG_THRESHOLD: Double = 75.0

    fun resolve(
        bgMgdl: Double,
        iobUnits: Double,
        sensMgDlPerU: Double,
        pkpdRelativeActivity: Double?,
        pkpdStage: InsulinActivityStage?,
        /** Minimum BG observed in the last ~75 min; null if unavailable (guard path 2 is skipped). */
        minBgLookback75mMgdl: Double? = null,
    ): NaiveEventualBgResolution {
        val rawRounded = (bgMgdl - (iobUnits * sensMgDlPerU)).roundToLong().toDouble()
        val activity = pkpdRelativeActivity ?: 0.0
        val peakish =
            pkpdStage == InsulinActivityStage.PEAK ||
                pkpdStage == InsulinActivityStage.RISING

        // Path 1 — original: negative IOB at peak PKPD activity (structural disagreement)
        val peakActivityCollapse =
            iobUnits < 0.0 &&
                activity >= MIN_PKPD_RELATIVE_ACTIVITY &&
                peakish

        // Path 2 — post-hypo: negative IOB while PKPD is in TAIL/EXHAUSTED after a recent low.
        // Activity precondition intentionally omitted: the artifact persists regardless of
        // PKPD stage when counter-regulation drives a steep rebound.
        val postHypoCollapse =
            iobUnits < 0.0 &&
                minBgLookback75mMgdl != null &&
                minBgLookback75mMgdl < POST_HYPO_BG_THRESHOLD

        val shouldCollapse = peakActivityCollapse || postHypoCollapse
        val collapseReason = when {
            !shouldCollapse -> null
            peakActivityCollapse -> "peak_activity iob=${iobUnits} act=${activity} stage=${pkpdStage}"
            else -> "post_hypo minBg75=${minBgLookback75mMgdl?.toInt()} iob=${iobUnits} act=${activity} stage=${pkpdStage}"
        }

        val naive = if (shouldCollapse) bgMgdl.roundToLong().toDouble() else rawRounded
        return NaiveEventualBgResolution(
            naiveEventualBgMgdl = naive,
            signGuardApplied = shouldCollapse,
            rawNaiveRoundedMgdl = rawRounded,
            collapseReason = collapseReason,
        )
    }
}
