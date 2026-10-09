package app.aaps.plugins.aps.openAPSAIMI

/**
 * iOS step counts are not wired yet. All getters return 0 ("no data"),
 * which the engine treats as a neutral activity signal.
 */
actual object StepService {
    actual fun getRecentStepCount5Min(): Int = 0
    actual fun getRecentStepCount10Min(): Int = 0
    actual fun getRecentStepCount15Min(): Int = 0
    actual fun getRecentStepCount30Min(): Int = 0
    actual fun getRecentStepCount60Min(): Int = 0
    actual fun getRecentStepCount180Min(): Int = 0
}
