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

        val insulinPeak = profile.peakTime.toDouble().takeIf { it > 0 } ?: 60.0
        
        // 1. Calculate Activity at t=0 to find "Expected Delta"
        var activityNow = 0.0
        for (iobEntry in iobArray) {
             val minutesSinceBolus = ((now - iobEntry.time) / 60000.0)
             if (minutesSinceBolus >= 0) {
                 activityNow += getInsulinActivityRate(minutesSinceBolus, insulinPeak) * iobEntry.iob
             }
        }
        
        // Expected drop per 5 min due to IOB
        val expectedDeltaFromInsulin = - (activityNow * finalSensitivity * 5.0) 
        
        // Carb impact at t=0 (simplified) - assuming constant linear for now
        val initialCarbImpact = if (cobG > 0) (cobG * csf) / (180.0/5.0) else 0.0
        
        val expectedDelta = expectedDeltaFromInsulin + initialCarbImpact
        
        // 2. Calculate Deviation (The "Missing Force" - UAM, Liver, Stress)
        // If Actual (+2) > Expected (-3) -> Deviation = +5. We project this forward.
        var deviation = delta - expectedDelta
        
        // Damping: If deviation is negative (dropping faster than expected), we trust it. 
        // If positive (UAM), we let it push up.
        
        // Momentum: Use deviation as the driver
        var momentum = deviation

        val carbAbsorptionMinutes = 180.0
        // We use a simplified carb curve
        
        var lastBg = currentBG

        repeat(steps) { stepIndex ->
            val minutesInFuture = (stepIndex + 1) * 5
            var insulinRateSum = 0.0
            
            // Calculate total insulin rate at this future time
            for (iobEntry in iobArray) {
                val minutesSinceBolus = ((now - iobEntry.time) / 60000.0) + minutesInFuture
                // Rate in Units/min
                val rate = getInsulinActivityRate(minutesSinceBolus, insulinPeak) 
                insulinRateSum += rate * iobEntry.iob 
            }
            
            // Insulin Impact (mg/dL per 5 min)
            val insulinImpact = insulinRateSum * finalSensitivity * 5.0

            // Carb Impact
            // Simplified linear decay of COB? For now assume constant if COB > 0
            // Ideally should use a curve derived from COB, but linear is safe baseline.
            val carbImpact = if (cobG > 0 && minutesInFuture < carbAbsorptionMinutes) {
                (cobG * csf) / (carbAbsorptionMinutes / 5.0) 
            } else {
                0.0
            }

            // Apply fluxes
            val nextBg = (lastBg - insulinImpact + carbImpact + momentum).coerceIn(39.0, 401.0)
            
            // Decay momentum/deviation
            momentum *= 0.80 // Decays over time (UAM doesn't last forever)
            
            lastBg = nextBg
            predictions.add(lastBg)
        }

        return predictions
    }

    // Returns Activity Rate (Fraction of Unit per Minute)
    // Integral of this function over 0..infinity should be approx 1.0
    private fun getInsulinActivityRate(minutesSinceBolus: Double, peakTime: Double): Double {
        if (minutesSinceBolus < 0 || peakTime <= 0) return 0.0
        val tau = peakTime * (1 - 1/3.5) / (1 - (1/(3.5*3.5))) // Approx tau relation to peak
        // Standard Dr. Walsh / Oref0 curve approximation?
        // Using the previous Gamma function but normalizing carefully
        // Gamma PDF: (t^(k-1) * exp(-t/theta)) / (theta^k * Gamma(k))
        // k=3.5. theta needed such that mode is peakTime.
        // mode = (k-1)*theta => theta = peakTime / (k-1)
        
        val k = 3.5 // shape
        val theta = peakTime / (k - 1) // scale
        
        // PDF Formula without Gamma(k) constant is t^(2.5) * exp(-t/theta)
        // We need the constant 1 / (theta^k * Gamma(k))
        // Gamma(3.5) ~= 3.323
        
        val t = minutesSinceBolus
        val core = t.pow(k - 1) * kotlin.math.exp(-t / theta)
        val div = theta.pow(k) * 3.323
        
        // Safety check to avoid NaN
        if (div.isNaN() || div == 0.0) return 0.0
        
        return core / div
    }
}
