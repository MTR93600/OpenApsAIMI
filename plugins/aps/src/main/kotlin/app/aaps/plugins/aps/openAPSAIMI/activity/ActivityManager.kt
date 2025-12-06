package app.aaps.plugins.aps.openAPSAIMI.activity

import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Manages the detection of physical activity using Steps and Heart Rate.
 * Replaces simple threshold logic with state-based scoring and recovery management.
 */
class ActivityManager @Inject constructor() {

    // Internal state for smoothing/hysteresis
    private var lastIntensityScore: Double = 0.0
    private var recoveryBucket: Double = 0.0 // Accumulates during effort, decays slowly
    private val RECOVERY_DECAY_RATE = 0.5 // Points lost per 5 min cycle
    private val RECOVERY_ACCUMULATION_FACTOR = 0.2 // Points gained per score point

    /**
     * Main processing function. Call this every loop cycle (~5 mins).
     *
     * @param steps5min Steps count in last 5 minutes.
     * @param steps10min Steps count in last 10 minutes (for trend).
     * @param avgHr Current average HR (e.g. 5 min avg).
     * @param avgHrResting Estimated resting HR (e.g. 60 min avg or user param).
     * @return The calculated ActivityContext.
     */
    fun process(
        steps5min: Int,
        steps10min: Int,
        avgHr: Double,
        avgHrResting: Double
    ): ActivityContext {
        // 1. Scoring (0.0 - 10.0+)
        // Base score from steps (approx 100 steps/min = moderate walk -> score ~3-4)
        val stepsPerMin = steps5min / 5.0
        val stepScore = (stepsPerMin / 25.0).coerceIn(0.0, 8.0) // 100 spm => 4.0

        // HR Reserve contribution (HRR)
        // If HR is unknown or low quality, we rely mostly on steps.
        // Assuming Max HR ~ 180 (simplified) or generic scaling.
        val safeResting = if (avgHrResting > 40) avgHrResting else 60.0
        val hrReserve = if (avgHr > safeResting) (avgHr - safeResting) else 0.0
        val hrScore = (hrReserve / 10.0).coerceIn(0.0, 8.0) // +30 bpm => 3.0

        // Context fusion
        // HR alone is suspicious (stress?), Steps alone is robust but maybe light.
        // If both are present, we boost confidence.
        var rawScore = 0.0
        if (stepsPerMin > 20) {
            rawScore = stepScore
            if (hrScore > 1.0) {
                // Confirming activity with HR
                rawScore += (hrScore * 0.5)
            }
        } else if (hrScore > 3.0) {
           // High HR without steps -> Stress or Resistance, or Cycling?
           // For safety, we count it but weight it differently (handled by existing stress logic usually)
           // Here we focus on "Activity" for ISF boost.
           // We'll ignore pure HR for *Activity* boost to avoid over-bolusing during stress.
           rawScore = 0.0
        }

        // 2. Smoothing (Simple Exponential Moving Average)
        // alpha 0.4 -> 40% new, 60% old. Avoids jitter.
        val smoothedScore = (rawScore * 0.4) + (lastIntensityScore * 0.6)
        lastIntensityScore = smoothedScore

        // 3. Recovery Bucket Management
        if (smoothedScore > 2.0) {
            // Accumulate fatigue/recovery debt
            recoveryBucket += (smoothedScore * RECOVERY_ACCUMULATION_FACTOR)
        } else {
            // Decay
            recoveryBucket = max(0.0, recoveryBucket - RECOVERY_DECAY_RATE)
        }
        // Cap bucket reasonable size (e.g. max 2-3 hours of decay)
        recoveryBucket = min(recoveryBucket, 40.0)

        // 4. State Determination
        val state = when {
            smoothedScore < 1.0 -> ActivityState.REST
            smoothedScore < 3.0 -> ActivityState.LIGHT
            smoothedScore < 6.0 -> ActivityState.MODERATE
            else -> ActivityState.INTENSE
        }

        // 5. Impact Calculation (ISF Multiplier)
        // Light: 1.1x - 1.2x
        // Moderate: 1.3x - 1.4x
        // Intense: 1.5x+
        val baseMultiplier = 1.0 + (smoothedScore * 0.08) // Score 5 => 1.4x
        val cappedMultiplier = baseMultiplier.coerceIn(1.0, 1.6) // Cap safety 60% boost

        // 6. Recovery Logic
        // If score is low (REST) but bucket is high -> Recovery Mode
        val isRecovery = (state == ActivityState.REST && recoveryBucket > 5.0)
        
        // During recovery, we might want to keep *some* sensitivity or protection
        // For now, let's flag it for safety protection (e.g. conservative SMBs)
        
        val description = if (isRecovery) "Recovery (Debt: ${"%.1f".format(recoveryBucket)})" else "${state.name} (Score: ${"%.1f".format(smoothedScore)})"

        return ActivityContext(
            state = state,
            intensityScore = smoothedScore,
            isRecovery = isRecovery,
            isfMultiplier = cappedMultiplier,
            protectionMode = isRecovery || (state == ActivityState.INTENSE),
            description = description
        )
    }
}
