package app.aaps.plugins.dexcomoneplus

/**
 * Where a fingerstick handed to the sensor got to.
 *
 * Kept separate from the wire type so the app side never has to know about opcodes, and so the one
 * state the wire cannot express — [Pending] — has a place.
 *
 * [Unknown] is not a failure and not a success, and it is the outcome a Dexcom ONE+ is expected to
 * produce: this firmware answers a calibration with four bytes nobody has decoded. It must be shown
 * as "we cannot tell", never quietly rounded to either side. A sensor keeps a calibration it
 * accepted for good, so claiming success we have not seen would leave a user re-sending a value the
 * sensor already holds.
 */
sealed interface OnePlusCalibrationOutcome {

    /** Waiting for the Control loop to reach the point where it may write. */
    data object Pending : OnePlusCalibrationOutcome

    /** The sensor said it took the value. */
    data class Accepted(val detail: String) : OnePlusCalibrationOutcome

    /** The sensor said no, and said why. */
    data class Refused(val detail: String) : OnePlusCalibrationOutcome

    /**
     * Something came back that cannot be read, or nothing came back at all.
     *
     * @param detail the raw bytes when there were any, so a field report can carry them
     */
    data class Unknown(val detail: String) : OnePlusCalibrationOutcome
}
