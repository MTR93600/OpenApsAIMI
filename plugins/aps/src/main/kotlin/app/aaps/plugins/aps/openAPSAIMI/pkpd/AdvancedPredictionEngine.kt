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
        delta: Double = 0.0, // Innovation: Carb impact awareness
        horizonMinutes: Int = 240
    ): List<Double> {
        val predictions = mutableListOf(currentBG)
        if (horizonMinutes <= 0) return predictions

        val steps = maxOf(1, horizonMinutes / 5)
        val now = System.currentTimeMillis()
        val carbRatio = profile.carb_ratio.takeIf { it > 0 } ?: 10.0
        val csf = finalSensitivity / carbRatio

        // Innovation: Dynamic IOB Damping during active meal rise
        // If BG is rising while COB exists OR during unannounced meals (UAM), the "effective" insulin pulling down is dampened by the inflow of glucose.
        // We reduce the impact of IOB in the prediction to avoid false hypo alarms.
        val iobDampingFactor = if (cobG > 0 || delta > 2.0) {
            when {
                delta > 12.0 -> 0.20 // Rocket rise: IOB 20% effective (Assume massive UAM)
                delta > 8.0 -> 0.30 // Strong rise: IOB 30% effective
                delta > 5.0 -> 0.50 // Intense rise: IOB 50% effective
                delta > 2.0 -> 0.70 // Moderate rise
                delta > 0.0 -> if (cobG > 0) 0.85 else 1.0 // Slight rise: damp only if explicit COB
                else -> 1.0
            }
        } else {
            1.0
        }

        val carbAbsorptionMinutes = 180.0
        val carbImpactPer5Min = if (cobG > 0) {
            (cobG * csf) / (carbAbsorptionMinutes / 5.0)
        } else {
            0.0
        }

        var lastBg = currentBG
        val insulinPeak = profile.peakTime.toDouble().takeIf { it > 0 } ?: 60.0
        val maxActivity = getInsulinActivity(insulinPeak, insulinPeak)

        // Innovation: Momentum (Delta Decay)
        // If BG is rising, assume it continues to rise for a while (inertia).
        // This is crucial for UAM where no COB is entered.
        var momentum = if (delta > 0) delta else 0.0

        repeat(steps) { stepIndex ->
            val minutesInFuture = (stepIndex + 1) * 5
            var insulinEffectMgDl = 0.0

            if (maxActivity > 0) {
                for (iobEntry in iobArray) {
                    val minutesSinceBolus = ((now - iobEntry.time) / 60000.0) + minutesInFuture
                    val activity = getInsulinActivity(minutesSinceBolus, insulinPeak) / maxActivity
                    insulinEffectMgDl += activity * iobEntry.iob * finalSensitivity
                }
            }
            
            // Apply damping innovation
            insulinEffectMgDl *= iobDampingFactor

            val insulinImpactPer5min = insulinEffectMgDl / 12.0
            
            // Apply Momentum
            // We add the current 'inertia' to the BG change, then decay it.
            val nextBg = (lastBg - insulinImpactPer5min + carbImpactPer5Min + momentum).coerceIn(39.0, 401.0)
            
            // Linear/Exp decay of momentum
            momentum *= 0.85 // Decays to ~0 over 45-60 mins
            
            lastBg = nextBg
            predictions.add(lastBg)
        }

        return predictions
    }

    private fun getInsulinActivity(minutesSinceBolus: Double, peakTime: Double): Double {
        if (minutesSinceBolus < 0 || peakTime <= 0) return 0.0
        val shape = 3.5
        val scale = peakTime / ((shape - 1) / shape).pow(1 / shape)
        return (shape / scale) * (minutesSinceBolus / scale).pow(shape - 1) *
            kotlin.math.exp(-(minutesSinceBolus / scale).pow(shape))
    }
}
