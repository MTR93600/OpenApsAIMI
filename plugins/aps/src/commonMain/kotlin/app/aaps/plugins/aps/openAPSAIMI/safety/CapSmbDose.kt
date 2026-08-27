package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.max
import kotlin.math.min

/**
 * Ultimate SMB cap before delivery: MaxSMB config, BG&lt;120 double-check, then MaxIOB headroom.
 * Same behaviour as the former private `app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2.capSmbDose`.
 */
internal fun capSmbDose(
    proposedSmb: Float,
    bg: Double,
    maxSmbConfig: Double,
    iob: Double,
    maxIob: Double,
): Float {
    var capped = min(proposedSmb, maxSmbConfig.toFloat())
    if (bg < 120) {
        if (capped > maxSmbConfig) {
            capped = maxSmbConfig.toFloat()
        }
    }
    if (iob + capped > maxIob) {
        capped = max(0.0, maxIob - iob).toFloat()
    }
    return capped
}
