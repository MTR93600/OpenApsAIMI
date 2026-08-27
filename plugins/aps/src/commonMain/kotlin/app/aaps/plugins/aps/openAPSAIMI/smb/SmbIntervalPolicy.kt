package app.aaps.plugins.aps.openAPSAIMI.smb

import kotlin.math.max

/**
 * Final SMB refractory interval: low-BG safety floor vs PKPD throttle add-on.
 * Below [lowBgThresholdMgdl], PKPD does not extend the interval (glycemic safety wins).
 */
object SmbIntervalPolicy {

    const val DEFAULT_LOW_BG_THRESHOLD_MGDL = 120f
    const val DEFAULT_LOW_BG_INTERVAL_MIN = 5
    const val DEFAULT_MAX_INTERVAL_MIN = 10

    fun applyLowBgFloorAndPkpdBoost(
        intervalAfterModes: Int,
        bgMgdl: Float,
        pkpdThrottleIntervalAdd: Int,
        lowBgThresholdMgdl: Float = DEFAULT_LOW_BG_THRESHOLD_MGDL,
        lowBgIntervalMin: Int = DEFAULT_LOW_BG_INTERVAL_MIN,
        maxIntervalMin: Int = DEFAULT_MAX_INTERVAL_MIN,
    ): Int {
        var finalInterval = intervalAfterModes.coerceIn(1, maxIntervalMin)
        if (bgMgdl < lowBgThresholdMgdl) {
            finalInterval = max(finalInterval, lowBgIntervalMin)
        } else if (pkpdThrottleIntervalAdd > 0) {
            finalInterval = (finalInterval + pkpdThrottleIntervalAdd).coerceAtMost(maxIntervalMin)
        }
        return finalInterval
    }
}
