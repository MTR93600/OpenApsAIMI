package app.aaps.plugins.aps.openAPSAIMI.activity

/**
 * Represents the detected activity state and its physiological impact.
 */
enum class ActivityState {
    REST,
    LIGHT,
    MODERATE,
    INTENSE
}

/**
 * Context holding the result of activity analysis.
 *
 * @param state The categorized state of activity (e.g. REST, INTENSE).
 * @param intensityScore A normalized score of intensity (0.0 to ~10.0+), roughly correlating to METs or relative effort.
 * @param isRecovery true if the user is in a post-activity recovery window (risk of late hypo).
 * @param isfMultiplier Suggested multiplier for Insulin Sensitivity Factor (e.g. 1.3 = 30% more sensitive).
 * @param protectionMode true if the system should engage protective measures (e.g. cap SMB).
 * @param description Diagnosis description for logging.
 */
data class ActivityContext(
    val state: ActivityState = ActivityState.REST,
    val intensityScore: Double = 0.0,
    val isRecovery: Boolean = false,
    val isfMultiplier: Double = 1.0,
    val protectionMode: Boolean = false,
    val description: String = "Rest"
)
