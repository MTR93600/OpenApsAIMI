package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiFmtSigned1
import kotlin.math.abs

/**
 * TAP-G (Trajectory-Anchored Peak Governor) — v1.
 *
 * Blends the **insulin profile anchor** (peak minutes + optional physio shift) with the **learned PKPD peak**
 * when PKPD and the governor preference are enabled. Optional [trajectoryMinutesNudge] comes from
 * `TrajectoryPeakBias` (bounded geometry-based nudge; RFC Phase D v1). [contextMinutesShift] is the peak
 * shift of the causal context (previous tick posterior, see [CausalKineticsModulator]). Both are added in
 * the same way; they are kept apart only so the log and the PK/PD screen can show which is which.
 * The switch `OApsAIMIPeakGovernorEnabled` (passed as `governorEnabled`) is also the field off switch of
 * the causal context: when it is off, [contextMinutesShift] is ignored (0). The trajectory nudge is
 * already 0 then, because the plugin does not compute it with the switch off.
 * The blended result is **always**
 * applied to the loop model when the governor path is active (no shadow-only branch).
 *
 * @see docs/research/TAP_G_PEAK_GOVERNOR_RFC.md
 */
data class TapPeakGovernorResult(
    val effectivePeakMinutes: Double,
    val peakPrior: Double,
    val peakPhysio: Double,
    val peakSite: Double,
    /** Trajectory nudge only (`TrajectoryPeakBias`), without the causal context. */
    val peakTrajectory: Double,
    /** Peak shift of the causal context of the previous tick. */
    val peakContext: Double,
    val peakLearned: Double?,
    val dominantBranch: String,
    /** Log line when the effective peak differs meaningfully from the anchor. */
    val logLine: String?,
    val appliedGovernor: Boolean,
)

object TapPeakGovernor {

    fun resolve(
        insulinPeakMinutes: Int,
        physioPeakShiftMinutes: Int,
        sitePeakShiftMinutes: Double = 0.0,
        pkpdLearnedPeak: Double?,
        pkpdEnabled: Boolean,
        governorEnabled: Boolean,
        peakMinBound: Double,
        peakMaxBound: Double,
        learnedBlendWeight: Double,
        trajectoryMinutesNudge: Double = 0.0,
        contextMinutesShift: Double = 0.0,
    ): TapPeakGovernorResult {
        // Switch off = field off switch of the causal context: no shift at all.
        val peakShiftMinutes = if (governorEnabled) contextMinutesShift else 0.0
        val nudge = trajectoryMinutesNudge + peakShiftMinutes
        val anchor = (insulinPeakMinutes + physioPeakShiftMinutes + sitePeakShiftMinutes).coerceAtLeast(35.0)

        fun createResult(eff: Double, log: String?, applied: Boolean, dominant: String) = TapPeakGovernorResult(
            effectivePeakMinutes = eff,
            peakPrior = insulinPeakMinutes.toDouble(),
            peakPhysio = physioPeakShiftMinutes.toDouble(),
            peakSite = sitePeakShiftMinutes,
            peakTrajectory = trajectoryMinutesNudge,
            peakContext = peakShiftMinutes,
            peakLearned = if (pkpdEnabled && governorEnabled) pkpdLearnedPeak else null,
            dominantBranch = dominant,
            logLine = log,
            appliedGovernor = applied,
        )

        if (!governorEnabled || !pkpdEnabled || pkpdLearnedPeak == null) {
            return createResult(
                eff = (anchor + nudge).coerceIn(peakMinBound, peakMaxBound),
                log = null,
                applied = false,
                dominant = when {
                    physioPeakShiftMinutes != 0          -> "PHYSIO"
                    abs(trajectoryMinutesNudge) > 1.0    -> "TRAJECTORY"
                    abs(peakShiftMinutes) > 1.0       -> "CONTEXT"
                    else                                 -> "PRIOR"
                },
            )
        }
        val w = learnedBlendWeight.coerceIn(0.0, 1.0)
        val learned = pkpdLearnedPeak.coerceIn(peakMinBound, peakMaxBound)
        val blended = anchor * (1.0 - w) + learned * w + nudge
        val eff = blended.coerceIn(peakMinBound, peakMaxBound)

        val dominant = when {
            abs(trajectoryMinutesNudge) > 10.0 -> "TRAJECTORY"
            abs(peakShiftMinutes) > 5.0 -> "CONTEXT"
            physioPeakShiftMinutes != 0 && abs(physioPeakShiftMinutes) > abs(eff - anchor) -> "PHYSIO"
            w > 0.3 && abs(learned - anchor) > 5.0 -> "LEARNED"
            else -> "PRIOR"
        }

        val log = if (abs(eff - anchor) >= 0.25 || abs(nudge) >= 0.05) {
            "PEAK_GOV: prior=$insulinPeakMinutes physio=$physioPeakShiftMinutes " +
                "site=${aimiFmt1(sitePeakShiftMinutes)} anchor=${aimiFmt1(anchor)} " +
                "learned=${aimiFmt1(learned)} w=${aimiFmt2(w)} traj=${aimiFmtSigned1(trajectoryMinutesNudge)} " +
                "ctx=${aimiFmtSigned1(peakShiftMinutes)} " +
                "-> eff=${aimiFmt1(eff)} [$dominant]"
        } else {
            null
        }
        return createResult(eff, log, true, dominant)
    }
}
