package app.aaps.plugins.aps.openAPSAIMI.pkpd

import java.util.Locale
import kotlin.math.abs

/**
 * TAP-D (Trajectory-Anchored DIA Governor) — symmetric to [TapPeakGovernor].
 * Blends profile DIA anchor with learned structural DIA and optional contextual shift.
 */
data class DiaGovernorResult(
    val effectiveDiaHours: Double,
    val diaPriorHours: Double,
    val diaContextualShiftHours: Double,
    val diaLearnedHours: Double?,
    val dominantBranch: String,
    val logLine: String?,
    val appliedGovernor: Boolean,
)

object DiaGovernor {

    fun resolve(
        profileDiaHours: Double,
        contextualDiaShiftHours: Double,
        pkpdLearnedDiaHours: Double?,
        pkpdEnabled: Boolean,
        governorEnabled: Boolean,
        diaMinBound: Double,
        diaMaxBound: Double,
        learnedBlendWeight: Double,
    ): DiaGovernorResult {
        val anchor = (profileDiaHours + contextualDiaShiftHours).coerceIn(diaMinBound, diaMaxBound)

        fun createResult(eff: Double, log: String?, applied: Boolean, dominant: String) = DiaGovernorResult(
            effectiveDiaHours = eff,
            diaPriorHours = profileDiaHours,
            diaContextualShiftHours = contextualDiaShiftHours,
            diaLearnedHours = if (pkpdEnabled && governorEnabled) pkpdLearnedDiaHours else null,
            dominantBranch = dominant,
            logLine = log,
            appliedGovernor = applied,
        )

        if (!governorEnabled || !pkpdEnabled || pkpdLearnedDiaHours == null) {
            return createResult(
                eff = anchor,
                log = null,
                applied = false,
                dominant = if (abs(contextualDiaShiftHours) > 0.01) "CONTEXT" else "PRIOR",
            )
        }
        val w = learnedBlendWeight.coerceIn(0.0, 1.0)
        val learned = pkpdLearnedDiaHours.coerceIn(diaMinBound, diaMaxBound)
        val blended = anchor * (1.0 - w) + learned * w
        val eff = blended.coerceIn(diaMinBound, diaMaxBound)
        // Label only: compare the two parts that really build `eff`.
        // eff = (profile + ctx) * (1 - w) + learned * w, so the context part is ctx * (1 - w)
        // and the learned part is (learned - profile) * w. The old rule compared |ctx| with
        // |eff - profile|, which already contains the context part, so CONTEXT was almost
        // never reported. This does not change [DiaGovernorResult.effectiveDiaHours].
        val contextContribution = abs(contextualDiaShiftHours * (1.0 - w))
        val learnedContribution = abs((learned - profileDiaHours) * w)
        val dominant = when {
            abs(contextualDiaShiftHours) > 0.15 && contextContribution > learnedContribution -> "CONTEXT"
            w > 0.3 && abs(learned - profileDiaHours) > 0.15                                 -> "LEARNED"
            else                                                                             -> "PRIOR"
        }
        val log = if (abs(eff - anchor) >= 0.05) {
            String.format(
                Locale.US,
                "DIA_GOV: prior=%.1f ctx=%+.1f anchor=%.1f learned=%.1f w=%.2f -> eff=%.1f [%s]",
                profileDiaHours,
                contextualDiaShiftHours,
                anchor,
                learned,
                w,
                eff,
                dominant,
            )
        } else {
            null
        }
        return createResult(eff, log, true, dominant)
    }
}
