package app.aaps.plugins.aps.openAPSAIMI.steps

/**
 * 🏃 Activity Vitals Provider Interface
 * 
 * unified interface for accessing physiological data (Steps, HR)
 * irrespective of the underlying source (Wear OS, Health Connect, Garmin).
 * 
 * Used by the Fallback Orchestrator (UnifiedActivityProviderMTR) to expose
 * the "best" available data to consumers.
 */
interface ActivityVitalsProvider {
    /**
     * Gets the latest step count within the specified window
     * @param windowMs Time window in milliseconds (backward from now)
     * @return Steps result or null if no data
     */
    fun getLatestSteps(windowMs: Long): StepsResult?

    /**
     * Gets the latest heart rate within the specified window
     * @param windowMs Time window in milliseconds (backward from now)
     * @return Heart Rate result or null if no data
     */
    fun getLatestHeartRate(windowMs: Long): HrResult?
}

data class StepsResult(
    val steps: Int,
    val timestamp: Long,
    val source: String,
    val duration: Long
)

data class HrResult(
    val bpm: Double,
    val timestamp: Long,
    val source: String
)
