package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.model.VerdictType
import kotlin.math.abs

/**
 * Downgrades over-confident OK_CONFIRM when glycemia is stable but proposed TBR is far above profile.
 */
object AuditorStableContextGuard {

    private const val STABLE_BG_MIN_MGDL = 95.0
    private const val STABLE_BG_MAX_MGDL = 140.0
    private const val STABLE_DELTA_ABS_MAX = 1.5
    private const val HIGH_TBR_PROFILE_RATIO = 1.5

    fun adjustIfNeeded(
        verdict: AuditorVerdict,
        bgMgdl: Double,
        deltaMgdl5m: Double,
        tbrRateUph: Double?,
        profileBasalUph: Double,
    ): AuditorVerdict {
        if (verdict.verdict != VerdictType.Confirm) return verdict
        val tbr = tbrRateUph ?: return verdict
        if (profileBasalUph <= 0.0) return verdict
        val stableBand = bgMgdl in STABLE_BG_MIN_MGDL..STABLE_BG_MAX_MGDL
        val stableDelta = abs(deltaMgdl5m) < STABLE_DELTA_ABS_MAX
        val highTbr = tbr > profileBasalUph * HIGH_TBR_PROFILE_RATIO
        if (!stableBand || !stableDelta || !highTbr) return verdict

        val softenFactor = 0.75.coerceAtMost(
            verdict.boundedAdjustments.smbFactorClamp.coerceIn(0.0, 1.0),
        )
        return verdict.copy(
            verdict = VerdictType.Soften,
            riskFlags = verdict.riskFlags + "STABLE_BG_HIGH_TBR",
            evidence = verdict.evidence + "Stable BG ${bgMgdl.toInt()} mg/dL with TBR ${aimiFmt2(tbr)} vs profile ${aimiFmt2(profileBasalUph)}",
            boundedAdjustments = verdict.boundedAdjustments.copy(
                smbFactorClamp = softenFactor,
                preferTbr = true,
            ),
        )
    }
}
