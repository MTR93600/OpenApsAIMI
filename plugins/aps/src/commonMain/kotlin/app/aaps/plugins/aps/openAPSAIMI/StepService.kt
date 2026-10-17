package app.aaps.plugins.aps.openAPSAIMI

/**
 * Phone step counts in trailing windows, used as an activity signal by the engine.
 *
 * Platform implementations:
 * - Android: live step-detector sensor listener ([StepService] actual).
 * - iOS: HealthKit, read through an in-memory cache that a background refresh fills.
 *
 * **`null` means unknown, and 0 means the person really took no steps.** A platform with no step
 * data (no sensor, HealthKit not answering, data too old) answers `null`. The engine does not take
 * a step-driven decision from `null`: in `BasalDecisionEngine` neither the 1.5 times raise (steps
 * under 100) nor the 0.5 times lowering (steps over 100) can fire, and in
 * `AimiHypoSmbSafety.adjustedDiaMinutes` neither the 1.2 nor the 0.90 DIA factor is applied.
 * A real 0 still counts as a measurement and keeps the old behaviour.
 */
expect object StepService {
    fun getRecentStepCount5Min(): Int?
    fun getRecentStepCount10Min(): Int?
    fun getRecentStepCount15Min(): Int?
    fun getRecentStepCount30Min(): Int?
    fun getRecentStepCount60Min(): Int?
    fun getRecentStepCount180Min(): Int?
}
