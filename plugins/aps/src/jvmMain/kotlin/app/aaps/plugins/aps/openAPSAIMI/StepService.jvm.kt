package app.aaps.plugins.aps.openAPSAIMI

/**
 * The JVM target is the desktop shell and the test runner. Neither has a step sensor, so every
 * getter returns `null`, which means "unknown". It does not mean "the person did not move": the
 * engine does not take a step-driven decision from `null`.
 */
actual object StepService {
    actual fun getRecentStepCount5Min(): Int? = null
    actual fun getRecentStepCount10Min(): Int? = null
    actual fun getRecentStepCount15Min(): Int? = null
    actual fun getRecentStepCount30Min(): Int? = null
    actual fun getRecentStepCount60Min(): Int? = null
    actual fun getRecentStepCount180Min(): Int? = null
}
