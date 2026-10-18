package app.aaps.plugins.dexcomoneplus

import platform.Foundation.NSProcessInfo

/**
 * Milliseconds since this phone last started, the iOS answer to `SystemClock.elapsedRealtime()`.
 *
 * `NSProcessInfo.systemUptime` keeps counting while the phone is asleep, which the driver's waits
 * and the scan budget both rely on. A monotonic clock that stops in sleep would make a wait that
 * really took minutes look like milliseconds.
 */
internal fun onePlusIosElapsedMs(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000.0).toLong()
