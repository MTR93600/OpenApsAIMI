package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.max

/**
 * Applies MaxSMB then MaxIOB headroom, matching the former [applyMaxLimits] logic in
 * `app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2.applySafetyPrecautions` chain.
 */
internal fun clampSmbToMaxSmbAndMaxIob(
    smbToGive: Float,
    maxSmb: Double,
    maxIob: Double,
    iob: Float,
): Float {
    var result = smbToGive
    if (result > maxSmb) {
        result = maxSmb.toFloat()
    }
    if (iob + result > maxIob) {
        val room = maxIob.toFloat() - iob
        result = max(0.0f, room)
    }
    return result
}
