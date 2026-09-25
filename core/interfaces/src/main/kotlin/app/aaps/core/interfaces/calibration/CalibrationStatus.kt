package app.aaps.core.interfaces.calibration

/**
 * Whether a calibration override is currently in effect for the running sensor session, and why
 * not when it isn't.
 *
 * Returned by [Calibration.status]. A caller that just got [AddEntryResult.Accepted] back from
 * [Calibration.addEntry] can use this to tell whether the entry it just saved already changed
 * anything — a single fingerstick is not enough to fit a calibration line, so the first entry of a
 * session is accepted but leaves the sensor value unchanged, which otherwise looks like nothing
 * happened.
 */
sealed interface CalibrationStatus {

    /** No recorded sensor session, so there is nothing to calibrate. */
    data object NoSession : CalibrationStatus

    /** Sensor session has not left its warm-up window yet. */
    data class WarmUp(val warmUpEndsAt: Long) : CalibrationStatus

    /** A session is running, but not enough fingerstick entries exist yet to fit a line. */
    data class NeedMoreEntries(val entryCount: Int) : CalibrationStatus

    /** Enough entries exist, but the fitted line falls outside the safe slope/offset range. */
    data object UnsafeFit : CalibrationStatus

    /** A safe fit is applied, with the slope locked to 1 (sensor range too narrow to trust a slope). */
    data object AppliedOffsetOnly : CalibrationStatus

    /** A safe fit is applied, with the slope clamped to the nearest edge of its allowed range. */
    data object AppliedSlopeClamped : CalibrationStatus

    /** A safe fit is applied with a freely fitted slope and offset. */
    data object Applied : CalibrationStatus
}
