package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.math.max
import kotlin.math.min

/**
 * Smooth basal-only correction for [PhysiologicalPhase.ENDOGENOUS_COUNTER_REGULATORY].
 * Matches slow R_HGP(t) with fractionated insulin delivery (no SMB pulse).
 */
object EndogenousBasalBridgePolicy {

    const val BRIDGE_FRACTION = 0.25
    const val WINDOW_MINUTES = 90.0
    const val MAX_SCALE_OVER_PROFILE = 2.0

    fun computeBridgeRateUph(
        bgMgdl: Double,
        targetBgMgdl: Double,
        isfMgdlPerU: Double,
        profileBasalUph: Double,
        maxBasalUph: Double,
    ): Double? {
        if (!bgMgdl.isFinite() || !targetBgMgdl.isFinite() || !isfMgdlPerU.isFinite()) return null
        if (isfMgdlPerU <= 0.0 || profileBasalUph <= 0.0) return null
        val excess = bgMgdl - targetBgMgdl
        if (excess < 6.0) return null

        val naiveUnits = excess / isfMgdlPerU
        val bridgeUnits = naiveUnits * BRIDGE_FRACTION
        val bridgeUph = bridgeUnits / (WINDOW_MINUTES / 60.0)
        val cappedUph = min(bridgeUph, profileBasalUph * MAX_SCALE_OVER_PROFILE)
        val ceiling = if (maxBasalUph > 0.1) maxBasalUph else profileBasalUph * MAX_SCALE_OVER_PROFILE
        val rate = min(cappedUph, ceiling)
        return rate.takeIf { it > profileBasalUph * 1.05 }
    }
}

