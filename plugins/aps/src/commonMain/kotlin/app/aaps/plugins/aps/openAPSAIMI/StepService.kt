package app.aaps.plugins.aps.openAPSAIMI

/**
 * Phone step counts in trailing windows, used as an activity signal by the engine.
 *
 * Platform implementations:
 * - Android: live step-detector sensor listener ([StepService] actual).
 * - iOS: not wired yet — returns 0 (same as "no data", the engine treats it as neutral).
 */
expect object StepService {
    fun getRecentStepCount5Min(): Int
    fun getRecentStepCount10Min(): Int
    fun getRecentStepCount15Min(): Int
    fun getRecentStepCount30Min(): Int
    fun getRecentStepCount60Min(): Int
    fun getRecentStepCount180Min(): Int
}
