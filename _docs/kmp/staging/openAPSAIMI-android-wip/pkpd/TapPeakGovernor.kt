package app.aaps.plugins.aps.openAPSAIMI.pkpd

import java.util.Locale
import kotlin.math.abs

/**
 * TAP-G (Trajectory-Anchored Peak Governor) — v1.
 *
 * Blends the **insulin profile anchor** (peak minutes + optional physio shift) with the **learned PKPD peak**
 * when PKPD and the governor preference are enabled. Optional [trajectoryMinutesNudge] comes from
 * [TrajectoryPeakBias] (bounded geometry-based nudge; RFC Phase D v1). The blended result is **always**
 * applied to the loop model when the governor path is active (no shadow-only branch).
 *
 * @see docs/research/TAP_G_PEAK_GOVERNOR_RFC.md
 */
data class TapPeakGovernorResult(
    val effectivePeakMinutes: Double,
    val peakPrior: Double,
    val peakPhysio: Double,
    val peakSite: Double,
    val peakTrajectory: Double,
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
    ): TapPeakGovernorResult {
        val anchor = (insulinPeakMinutes + physioPeakShiftMinutes + sitePeakShiftMinutes).coerceAtLeast(35.0)

        fun createResult(eff: Double, log: String?, applied: Boolean, dominant: String) = TapPeakGovernorResult(
            effectivePeakMinutes = eff,
            peakPrior = insulinPeakMinutes.toDouble(),
            peakPhysio = physioPeakShiftMinutes.toDouble(),
            peakSite = sitePeakShiftMinutes,
            peakTrajectory = trajectoryMinutesNudge,
            peakLearned = if (pkpdEnabled && governorEnabled) pkpdLearnedPeak else null,
            dominantBranch = dominant,
            logLine = log,
            appliedGovernor = applied,
        )

        if (!governorEnabled || !pkpdEnabled || pkpdLearnedPeak == null) {
            return createResult(
                eff = (anchor + trajectoryMinutesNudge).coerceIn(peakMinBound, peakMaxBound),
                log = null,
                applied = false,
                dominant = if (physioPeakShiftMinutes != 0) "PHYSIO" else if (abs(trajectoryMinutesNudge) > 1.0) "TRAJECTORY" else "PRIOR",
            )
        }
        val w = learnedBlendWeight.coerceIn(0.0, 1.0)
        val learned = pkpdLearnedPeak.coerceIn(peakMinBound, peakMaxBound)
        val blended = anchor * (1.0 - w) + learned * w + trajectoryMinutesNudge
        val eff = blended.coerceIn(peakMinBound, peakMaxBound)

        val dominant = when {
            abs(trajectoryMinutesNudge) > 10.0 -> "TRAJECTORY"
            physioPeakShiftMinutes != 0 && abs(physioPeakShiftMinutes) > abs(eff - anchor) -> "PHYSIO"
            w > 0.3 && abs(learned - anchor) > 5.0 -> "LEARNED"
            else -> "PRIOR"
        }

        val log = if (abs(eff - anchor) >= 0.25 || abs(trajectoryMinutesNudge) >= 0.05) {
            String.format(
                Locale.US,
                "PEAK_GOV: prior=%d physio=%d site=%.1f anchor=%.1f learned=%.1f w=%.2f traj=%+.1f -> eff=%.1f [%s]",
                insulinPeakMinutes,
                physioPeakShiftMinutes,
                sitePeakShiftMinutes,
                anchor,
                learned,
                w,
                trajectoryMinutesNudge,
                eff,
                dominant,
            )
        } else {
            null
        }
        return createResult(eff, log, true, dominant)
    }
}
