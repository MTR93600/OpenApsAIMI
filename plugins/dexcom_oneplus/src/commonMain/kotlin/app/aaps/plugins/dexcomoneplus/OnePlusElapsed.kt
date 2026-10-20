package app.aaps.plugins.dexcomoneplus

/**
 * Milliseconds on a clock that only moves forward and keeps counting while the phone sleeps.
 *
 * The driver measures two things with it: how old an advertisement sighting is, and how long the
 * radio has been lent out to another job. Neither may be measured on the wall clock, which the user
 * and the network can move, and neither may be measured on a clock that stops in sleep - a wait
 * that really took minutes would then look like milliseconds and the driver would act on it.
 *
 * On Android this is `SystemClock.elapsedRealtime()`, on iPhone `NSProcessInfo.systemUptime`. The
 * two count from different moments, so only the difference between two readings means anything.
 * `OnePlusScanResult.seenElapsedMs` is a value of this clock and nothing else.
 */
internal expect fun onePlusElapsedMs(): Long
