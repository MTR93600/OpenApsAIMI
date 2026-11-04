package app.aaps.plugins.dexcomoneplus

import kotlin.time.Clock

/**
 * iOS sink. It prints to standard output, which Xcode shows, with the same tag Android uses so the
 * two logs read the same way.
 *
 * iPhone has no AAPS log file yet, so the line also goes to [OnePlusCommLog.shared]. That buffer is
 * what the driver log screen shows on this phone. Printing alone is not a silent no-op, but a line
 * only Xcode can read is of no use to a person holding a sensor that does not work.
 */
internal actual fun onePlusLogWrite(level: OnePlusLogLevel, message: String, cause: Throwable?) {
    val suffix = if (cause == null) "" else " cause=$cause"
    println("${OnePlusLogMarkers.TAG} ${level.name} $message$suffix")
    OnePlusCommLog.shared.add(Clock.System.now().toEpochMilliseconds(), level.name, message + suffix)
}
