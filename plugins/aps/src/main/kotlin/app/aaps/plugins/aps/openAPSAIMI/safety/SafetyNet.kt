package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.min
import kotlin.math.max

/**
 * 🛡️ SafetyNet: Centralized SMB Safety Logic
 * 
 * Implements the "Tiered Reaction" strategy to manage aggression levels
 * based on Glucose Zones and Predictive Trajectories.
 * 
 * Addresses the "React Over 120" amplification request:
 * - Introduces a Buffer Zone (120-160 mg/dL) to prevent binary aggression jumps.
 * - Enforces strict limits below 120 mg/dL to prevent "Deep Lows" (45-50 mg/dL).
 */
object SafetyNet {

    /**
     * Calculates the safe Maximum SMB allowed for the current context.
     * 
     * @param bg Current Blood Glucose
     * @param eventualBg Predicted eventual BG (Trajectory)
     * @param maxSmbLow The user's standard "MaxSMB" setting (Conservative)
     * @param maxSmbHigh The user's "High BG MaxSMB" setting (Aggressive/Rage)
     * @param isExplicitUserAction True if this is a manual user override
     * @return The safe SMB limit (U)
     */
    fun calculateSafeSmbLimit(
        bg: Double,
        eventualBg: Double,
        delta: Double,         // 🚀 NEW: Instant trend
        shortAvgDelta: Double, // 🚀 NEW: 15min trend confirmation
        maxSmbLow: Double,
        maxSmbHigh: Double,
        isExplicitUserAction: Boolean
    ): Double {
        // 1. Manual Override: Full Trust
        if (isExplicitUserAction) return maxSmbHigh

        // 2. ZONE 1: STRICT GUARD (< 120 mg/dL)
        // Standard: Clamp to 50% to prevent deep hypos
        // Exception: UAM ROCKET BYPASS
        // If BG is rising violently, waiting for 120 mg/dL is too late.
        // We detect this via Delta > 6.0 OR AvgDelta > 6.0 (Confirmed Rise)
        val isUamRise = delta > 6.0 || shortAvgDelta > 6.0

        if (bg < 120.0) {
            if (isUamRise) {
                // 🚀 ROCKET BYPASS ACTIVATE
                // Situation: Meal detected early.
                // Action: Unlock the full "Low" aggression immediately.
                // We keep "Low" (not High) because we are still < 120.
                return maxSmbLow
            }
            
            // Default Safety: Brakes On
            val strictFactor = 0.5 
            return maxSmbLow * strictFactor
        }

        // 3. ZONE 2: BUFFER / TRANSITION (120 - 160 mg/dL)
        // User Request: "Amplifier the range of react over 120?"
        // Strategy: Don't jump straight to MaxSMBHigh. 
        // Use a progressive transition or "Predictive Clamp".
        if (bg < 160.0) {
            // Predictive Check:
            // If the trajectory is falling (Eventual < 120), DO NOT use High Rage settings.
            if (eventualBg < 120.0) {
                return maxSmbLow
            }
            
            // If trajectory is stable/rising (Eventual > 120), allow linear interpolation.
            // Result scales from maxSmbLow (at 120) to maxSmbHigh (at 160).
            val progress = (bg - 120.0) / (160.0 - 120.0) // 0.0 to 1.0
            val range = maxSmbHigh - maxSmbLow
            return maxSmbLow + (range * progress)
        }

        // 4. ZONE 3: REACTOR MAX (> 160 mg/dL)
        // Full Aggression allowed (bounded by MaxSMBHB)
        return maxSmbHigh
    }
}
