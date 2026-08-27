package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.core.interfaces.aps.OapsProfileAimi

/**
 * OpenAPS-style hypo / LGS threshold helpers extracted from `DetermineBasalAIMI2` for reuse and testing.
 * Behaviour is intentionally unchanged from the original private methods.
 */
object HypoThresholdMath {

    /** Computes the "OpenAPS-like" floor threshold; if [lgsThreshold] is higher, it wins. */
    fun computeHypoThreshold(minBg: Double, lgsThreshold: Int?): Double {
        var t = minBg - 0.5 * (minBg - 40.0) // 90→65, 100→70, 110→75, 130→85
        if (lgsThreshold != null && lgsThreshold > t) t = lgsThreshold.toDouble()
        return t
    }

    /**
     * LGS threshold from profile when set; otherwise [computeHypoThreshold] on [OapsProfileAimi.min_bg].
     */
    fun getLgsThresholdSafe(profile: OapsProfileAimi): Double {
        return if (profile.lgsThreshold != null && profile.lgsThreshold!! > 0) {
            profile.lgsThreshold!!.toDouble()
        } else {
            computeHypoThreshold(profile.min_bg, null)
        }
    }
}
