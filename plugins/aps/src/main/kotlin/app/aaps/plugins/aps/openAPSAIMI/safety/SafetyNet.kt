package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.min
import kotlin.math.max

/**
 * 🛡️ SafetyNet: Centralized SMB Safety Logic
 * 
 * Implements the "Tiered Reaction" strategy to manage aggression levels
 * based on Glucose Zones and Predictive Trajectories.
 * 
 * Zone Architecture:
 * - Zone 0.5: Soft Landing (Target → Target+15) - Gentle convergence boost
 * - Zone 1: Strict Guard (< 130) - Hypo prevention with UAM bypass
 * - Zone 2: Buffer (130-170) - Progressive SMB scaling
 * - Zone 3: Reactor (> 170) - Full aggression
 */
object SafetyNet {

    /**
     * Calculates the safe Maximum SMB allowed for the current context.
     * 
     * @param bg Current Blood Glucose
     * @param targetBg Profile Target BG
     * @param eventualBg Predicted eventual BG (Trajectory)
     * @param delta Instant trend
     * @param shortAvgDelta 15min trend confirmation
     * @param maxSmbLow The user's standard "MaxSMB" setting (Conservative)
     * @param maxSmbHigh The user's "High BG MaxSMB" setting (Aggressive/Rage)
     * @param isExplicitUserAction True if this is a manual user override
     * @param auditorConfidence AI Auditor confidence (0-1), null if unavailable
     * @return The safe SMB limit (U)
     */
    fun calculateSafeSmbLimit(
        bg: Double,
        targetBg: Double,
        eventualBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        maxSmbLow: Double,
        maxSmbHigh: Double,
        isExplicitUserAction: Boolean,
        auditorConfidence: Double? = null
    ): Double {
        // 1. Manual Override: Full Trust
        if (isExplicitUserAction) return maxSmbHigh

        // 2. ZONE 0.5: SOFT LANDING (Target → Target+15)
        // Purpose: Prevent "plateau effect" 10-20 mg/dL above target
        // Example: Target=100, Current=110 → Apply gentle boost to converge
        val distanceAboveTarget = bg - targetBg
        val inSoftLandingZone = distanceAboveTarget > 0 && distanceAboveTarget <= 15
        
        if (inSoftLandingZone) {
            // Only boost if coasting stable (not rising fast)
            val isCoasting = delta <= 1.0 && eventualBg > targetBg
            
            if (isCoasting) {
                // Base boost: +10% SMB to gently push down
                var boostFactor = 1.10
                
                // 🧠 AI AUDITOR SAFETY LAYER
                // If Auditor doubts the situation, reduce or cancel boost
                if (auditorConfidence != null) {
                    boostFactor = when {
                        auditorConfidence >= 0.7 -> 1.10  // High confidence: Full boost
                        auditorConfidence >= 0.5 -> 1.05  // Medium: Half boost
                        else -> 1.0                       // Low: No boost (safety)
                    }
                }
                
                return maxSmbLow * boostFactor
            }
        }

        // 3. ZONE 1: STRICT GUARD (< 130 mg/dL)
        // [TIR 70-140 OPTIMIZATION] Limit extended to 130 to protect the 140 ceiling
        // Standard: Clamp to 50% to prevent deep hypos
        // Exception: UAM ROCKET BYPASS
        val isUamRise = delta > 6.0 || shortAvgDelta > 6.0

        if (bg < 130.0) {
            if (isUamRise) {
                // 🚀 ROCKET BYPASS: Meal detected early
                return maxSmbLow
            }
            
            // Default Safety: Brakes On
            val strictFactor = 0.5 
            return maxSmbLow * strictFactor
        }

        // 4. ZONE 2: BUFFER / TRANSITION (130 - 170 mg/dL)
        if (bg < 170.0) {
            // Predictive Check
            if (eventualBg < 130.0) {
                return maxSmbLow
            }
            
            // Linear interpolation
            val progress = (bg - 130.0) / (170.0 - 130.0)
            val range = maxSmbHigh - maxSmbLow
            return maxSmbLow + (range * progress)
        }

        // 5. ZONE 3: REACTOR MAX (> 160 mg/dL)
        return maxSmbHigh
    }
}
