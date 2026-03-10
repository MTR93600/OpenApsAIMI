package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import kotlin.math.pow

/**
 * Provides a unified prediction model that mirrors the same parameters used during SMB/basal decisions.
 */
object AdvancedPredictionEngine {

    /**
     * Predict the BG evolution using the final ISF/sensitivity applied by the decision engine.
     *
     * @param currentBG Current glucose in mg/dL.
     * @param iobArray Active insulin entries.
     * @param finalSensitivity Final ISF after all adjustments.
     * @param cobG Active carbs in grams.
     * @param profile User profile used for insulin timing and carb ratio.
     * @param horizonMinutes Prediction horizon (defaults to 4h).
     */
    fun predict(
        currentBG: Double,
        iobArray: Array<IobTotal>,
        finalSensitivity: Double,
        cobG: Double,
        profile: OapsProfileAimi,
        delta: Double = 0.0,
        horizonMinutes: Int = 240
    ): List<Double> {
        val predictions = mutableListOf(currentBG)
        if (horizonMinutes <= 0) return predictions

        val steps = maxOf(1, horizonMinutes / 5)
        val now = System.currentTimeMillis()
        val carbRatio = profile.carb_ratio.takeIf { it > 0 } ?: 10.0
        val csf = finalSensitivity / carbRatio

        // 1. Calculate Expected Drop at t=0
        val currentEntry = iobArray.minByOrNull { kotlin.math.abs(it.time - now) }
        val currentActivity = currentEntry?.activity ?: 0.0
        
        // IOB Activity is U/min (rate).
        // Drop (mg/dL) = Rate * 5 * ISF
        val expectedDeltaFromInsulin = - (currentActivity * finalSensitivity * 5.0)
        
        // Carb impact at t=0
        val initialCarbImpact = if (cobG > 0) (cobG * csf) / (180.0/5.0) else 0.0
        
        val expectedDelta = expectedDeltaFromInsulin + initialCarbImpact
        
        // 2. Calculate Deviation
        var deviation = delta - expectedDelta
        
        // Innovation: Hybrid UAM Strategy
        var momentum = deviation
        
        // Damping: Reduce IOB effectiveness if rising (Long term).
        val iobDampingFactor = if (delta > 2.0) {
            when {
                delta > 10.0 -> 0.40 // Massive rise
                delta > 5.0 -> 0.60  // Strong rise
                else -> 0.80         // Moderate rise
            }
        } else {
            1.0
        }
        
        // Decay factor for momentum
        val momentumDecay = if (delta > 3.0) 0.92 else 0.85

        val carbAbsorptionMinutes = 180.0
        var lastBg = currentBG

        repeat(steps) { stepIndex ->
            val minutesInFuture = (stepIndex + 1) * 5
            val targetTime = now + (minutesInFuture * 60000L)
            
            // LOOKUP from Curve
            val entry = iobArray.minByOrNull { kotlin.math.abs(it.time - targetTime) }
            val activityRate = entry?.activity ?: 0.0
            
            // Insulin Impact (mg/dL per 5 min)
            val insulinImpact = (activityRate * finalSensitivity * 5.0) * iobDampingFactor

            // Carb Impact
            val carbImpact = if (cobG > 0 && minutesInFuture < carbAbsorptionMinutes) {
                (cobG * csf) / (carbAbsorptionMinutes / 5.0) 
            } else {
                0.0
            }

            // Apply fluxes
            val nextBg = (lastBg - insulinImpact + carbImpact + momentum).coerceIn(39.0, 401.0)
            
            // Decay momentum
            momentum *= momentumDecay
            
            lastBg = nextBg
            predictions.add(lastBg)
        }

        return predictions
    }
}
