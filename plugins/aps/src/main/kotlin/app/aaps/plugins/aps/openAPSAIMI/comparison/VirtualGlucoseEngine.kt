package app.aaps.plugins.aps.openAPSAIMI.comparison

import app.aaps.core.interfaces.aps.OapsProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 🧪 Virtual Glucose Engine (VGE) - Evolution for Comparator Realism
 * 
 * This engine calculates how the glucose trajectory would have changed if the 
 * simulated algorithm had been in control instead of the real one.
 * 
 * Logic:
 * It computes a "Deviation" from the real BG readings based on the difference 
 * in insulin activity between the two algorithms.
 */
@Singleton
class VirtualGlucoseEngine @Inject constructor() {

    /**
     * Calculates the next simulated BG based on real BG and insulin divergence.
     * 
     * @param realBg The actual BG reading from history
     * @param lastSimBg The BG calculated by the simulator in the previous tick
     * @param realActivity Insulin activity (U absorbed) that actually happened 
     * @param simActivity Insulin activity (U absorbed) that the simulated algorithm delivered
     * @param isf Insulin Sensitivity Factor (mg/dL / U)
     * @param tickMinutes Duration of the interval (usually 5.0)
     */
    fun calculateNextBg(
        realBg: Double,
        lastSimBg: Double,
        realActivity: Double,
        simActivity: Double,
        isf: Double,
        tickMinutes: Double = 5.0
    ): Double {
        // 1. Calculate the insulin activity difference (U)
        // Activity is usually expressed as a "rate" or "total absorbed in the interval"
        // Here we assume it's the amount absorbed since the last tick.
        val insulinDiff = simActivity - realActivity
        
        // 2. Calculate the glucose impact of this difference
        // Impact = - (Difference in U) * ISF
        // (Negative because more insulin = less glucose)
        val glucoseImpact = - (insulinDiff * isf)
        
        // 3. Calculate the natural evolution that happened in reality
        // This includes carbs, basal, endogenous production, etc.
        // We assume lastRealBg -> currentRealBg reflects this.
        // But for the recursive simulation, we apply the impact on top of the last sim state.
        
        // Deviation Approach:
        // BgSim(t) = BgReal(t) + CumulativeDeviation(t)
        // This is safer than absolute Bergman modeling because it's self-correcting 
        // regarding carbohydrate errors or profile errors that are common to both.
        
        val currentDeviation = lastSimBg - realBg
        val nextDeviation = currentDeviation + glucoseImpact
        
        // Prevent extreme drift (Safety ceiling for simulation)
        // Simulation shouldn't deviate more than 150 mg/dL from reality to stay "plausible"
        val cappedDeviation = nextDeviation.coerceIn(-150.0, 150.0)
        
        val nextSimBg = realBg + cappedDeviation
        
        // Safety: BG cannot be negative
        return maxOf(39.0, nextSimBg)
    }
}
