package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.calibration.CalibrationStatus

/**
 * The body of `LinearCalibrationPlugin.status` (ref L209–224 @ `6598201d`).
 *
 * One classification, shared with the test. It does not fit a second line and it does not
 * restate the safety checks: a missing line is [fitLinearCalibration] returning null, and an
 * unsafe line is [CalibrationFit.isApplicable] (`slopeInRange && correctionInRange && lowEndSafe
 * && highEndSafe`, ref `CalibrationMath.kt` L166 @ `6598201d`).
 *
 * [entries] must already be the list [entriesForFit] would return. The plugin loads them, and
 * only once warm-up is over, because the ref returns before that read. Passing them in keeps
 * the database out of this function. [now] is the caller's clock (`dateUtil.now()` in the plugin).
 *
 * [warmUpHours] is the plugin's `WARM_UP_HOURS` (2). It is a parameter so this file does not
 * keep a second copy of that constant.
 */
internal fun calibrationStatus(
    sessionStart: Long?,
    now: Long,
    entries: List<CAL>,
    warmUpHours: Long,
): CalibrationStatus {
    if (sessionStart == null) return CalibrationStatus.NoSession
    val warmUpEndsAt = sessionStart + T.hours(warmUpHours).msecs()
    if (now < warmUpEndsAt) return CalibrationStatus.WarmUp(warmUpEndsAt)
    val fit = fitLinearCalibration(entries, now) ?: return CalibrationStatus.NeedMoreEntries(entries.size)
    return when {
        !fit.isApplicable               -> CalibrationStatus.UnsafeFit
        fit.mode == FitMode.OffsetOnly   -> CalibrationStatus.AppliedOffsetOnly
        fit.mode == FitMode.SlopeClamped -> CalibrationStatus.AppliedSlopeClamped
        else                             -> CalibrationStatus.Applied
    }
}
