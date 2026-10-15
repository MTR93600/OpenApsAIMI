package app.aaps.plugins.aps.openAPSAIMI

/**
 * The JVM target is the desktop shell and the test runner. Neither has a step sensor, so every
 * getter returns 0.
 *
 * Zero is not a stand-in for an unknown here: the contract in `StepService` defines 0 as "no data",
 * and the engine reads it as a neutral activity signal rather than as "the person did not move".
 * This is the same answer the iOS target gives, for the same reason.
 */
actual object StepService {
    actual fun getRecentStepCount5Min(): Int = 0
    actual fun getRecentStepCount10Min(): Int = 0
    actual fun getRecentStepCount15Min(): Int = 0
    actual fun getRecentStepCount30Min(): Int = 0
    actual fun getRecentStepCount60Min(): Int = 0
    actual fun getRecentStepCount180Min(): Int = 0
}
