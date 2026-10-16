package app.aaps.plugins.aps.openAPSAIMI

/**
 * Phone step counts in trailing windows, used as an activity signal by the engine.
 *
 * Platform implementations:
 * - Android: live step-detector sensor listener ([StepService] actual).
 * - iOS: HealthKit, read through an in-memory cache that a background refresh fills.
 *
 * **0 is not "no data" and it is not neutral.** The engine reads 0 steps as a real signal: in
 * `BasalDecisionEngine` the test `recentSteps5Minutes < 100` is one of the conditions that raises
 * basal to 1.5 times the profile, the branch `recentSteps5Minutes > 100` that lowers it is then
 * unreachable, and `calculateAdjustedDIA` lengthens the DIA by 30 percent on exactly 0 steps when
 * the heart rate is above its hour average. So a platform that has no step data and answers 0
 * pushes dosing toward more insulin. This signature is a plain `Int` and cannot say "unknown";
 * a platform that can be without data must expose that state itself (the iOS actual has
 * `dataStatus`) until the engine learns to tell "no data" from "no steps".
 */
expect object StepService {
    fun getRecentStepCount5Min(): Int
    fun getRecentStepCount10Min(): Int
    fun getRecentStepCount15Min(): Int
    fun getRecentStepCount30Min(): Int
    fun getRecentStepCount60Min(): Int
    fun getRecentStepCount180Min(): Int
}
