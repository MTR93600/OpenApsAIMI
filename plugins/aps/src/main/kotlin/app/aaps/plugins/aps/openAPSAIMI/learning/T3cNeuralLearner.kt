package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * T3cNeuralLearner identifies user-specific glycemic response patterns 
 * during Brittle Mode intervention.
 * 
 * Strategy:
 * 1. Track stability after T3C engagements.
 * 2. If BG remains high/stagnant after correction -> Increase aggressiveness.
 * 3. If BG drops too fast or causes hypo -> Reduce aggressiveness.
 */
@Singleton
class T3cNeuralLearner @Inject constructor(
    private val preferences: Preferences,
    private val log: AAPSLogger
) {
    private var internalAggressivenessFactor = 1.0

    /**
     * Estimates the adaptive factor based on recent performance.
     * In a full implementation, this would load weights from a local model.
     * For now, it provides a stable interface for the T3C logic.
     */
    fun getAdaptiveFactor(): Double {
        // Fallback to manual preference if learner is disabled or not yet trained
        val baseAggressiveness = preferences.get(DoubleKey.OApsAIMIT3cAggressiveness)
        return baseAggressiveness * internalAggressivenessFactor
    }

    /**
     * Updates the internal state based on observed outcome.
     * Called by DetermineBasalAIMI2 after a T3C cycle to "learn" from the result.
     */
    fun updateLearning(bgBefore: Double, bgAfter: Double, basalDelivered: Double, targetBg: Double) {
        if (!preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)) return

        val delta = bgAfter - bgBefore
        
        // Simple heuristic for POC:
        // If we are high (>180) and delta is positive despite T3C basal -> we were too slow
        if (bgBefore > 180.0 && delta >= 0) {
            internalAggressivenessFactor = min(internalAggressivenessFactor + 0.05, 2.0)
        }
        
        // If we are dropping extremely fast (>15 mg/dL per 5min) -> we might be too aggressive
        if (delta < -15.0) {
            internalAggressivenessFactor = max(internalAggressivenessFactor - 0.1, 0.5)
        }
        
        // If we hit target range and stabilized -> Reward (stay here)
    }
}
