package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dagger.Reusable
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Dynamic Basal Controller based on a Proportional-Derivative (PD) error model
 * and Sigmoid scaling.
 * 
 * Objective: Allow massive but smooth Temporary Basal Rates (TBR) up to 1000%
 * when deviating significantly from the target, while applying strong derivative
 * braking to prevent hypo rebounds when BG is falling.
 */
@Reusable
class DynamicBasalController @Inject constructor(
    private val log: AAPSLogger
) {

    // Configuration - Can be extracted to Preferences later
    private val MAX_TBR_MULTIPLIER = 10.0 // 1000%
    private val MIN_TBR_MULTIPLIER = 0.0  // 0%
    
    // Proportional & Derivative Weights
    private val P_WEIGHT = 0.05  // Gain on the raw distance from target
    private val D_WEIGHT = 0.15  // Gain on the velocity (delta)

    data class ControllerState(
        val errorP: Double,
        val errorD: Double,
        val totalError: Double,
        val sigmoidMultiplier: Double,
        val finalRate: Double,
        val isBraking: Boolean
    )

    /**
     * Calculates the dynamic basal rate using Sigmoid scaling and PD feedback.
     *
     * @param currentRate The base rate before adjustment (e.g., profile basal)
     * @param bg Current BG level
     * @param targetBg Target BG level
     * @param delta Immediate BG velocity (mg/dL/5min)
     * @param shortAvgDelta Smoothed BG velocity (for trend confirmation)
     * @return The suggested TBR and the calculated state for logging
     */
    fun calculateDynamicRate(
        currentRate: Double,
        bg: Double,
        targetBg: Double,
        delta: Double,
        shortAvgDelta: Double
    ): ControllerState {
        
        // 1. Proportional Error (Distance from target)
        // Positive = Above target (needs more insulin)
        // Negative = Below target (needs less insulin)
        val proportionalError = bg - targetBg

        // 2. Derivative Error (Velocity)
        // Blend immediate delta with shortAvgDelta to handle noise but prioritize current momentum.
        // If accelerating (delta > shortAvg), weigh immediate delta more.
        val velocity = if ((delta > 0 && shortAvgDelta > 0 && delta > shortAvgDelta) || 
                           (delta < 0 && shortAvgDelta < 0 && delta < shortAvgDelta)) {
            delta * 0.8 + shortAvgDelta * 0.2
        } else {
            delta * 0.5 + shortAvgDelta * 0.5
        }

        // 3. Total Error Signal
        // Error = (Distance * P) + (Velocity * D)
        // Note: Delta is in mg/dL/5min. Multiplying by 12 converts roughly to mg/dL/hour for parity with P.
        val derivativeError = velocity * 12.0
        val totalErrorSignal = (proportionalError * P_WEIGHT) + (derivativeError * D_WEIGHT)

        // 4. Sigmoid Mapping
        // We use a logistic function to map the unbounded error signal strictly to [0.0, 1.0]
        // Base function: S(x) = 1 / (1 + e^-x)
        // Shifted so that an error of 0 gives a multiplier of 1.0
        val sigmoidBase = 1.0 / (1.0 + exp(-totalErrorSignal))
        
        // Scale to [MIN_TBR, MAX_TBR]
        // Since sigmoid goes 0 to 1:
        // When error = 0, sigmoid = 0.5. We want this mapped to 1.0x (100% basal).
        // Let's adjust the curve:
        // S_adjusted(x) = MAP(sigmoid(x), 0..1, MIN..MAX)
        // But we must guarantee that S(0) = 1.0
        
        // A better approach for targeting 1.0 at origin:
        val multiplier = when {
            totalErrorSignal > 0 -> {
                // Scaling up from 1.0 to MAX_TBR
                // Using a modified exponential approach for the positive side
                // Cap soft limit using formula: M = 1 + (MAX-1) * (1 - e^(-error/K))
                // Where K controls how fast we climb. K=5 means at error=5 we are ~63% of the way to MAX.
                val K_UP = 5.0
                1.0 + (MAX_TBR_MULTIPLIER - 1.0) * (1.0 - exp(-totalErrorSignal / K_UP))
            }
            totalErrorSignal < 0 -> {
                // Scaling down from 1.0 to 0.0
                // M = e^(error/K)
                val K_DOWN = 3.0 // Falls faster than it rises for safety
                exp(totalErrorSignal / K_DOWN).coerceAtLeast(MIN_TBR_MULTIPLIER)
            }
            else -> 1.0
        }

        // 5. Hard Braking Override
        // If BG is < Target and falling fast, or BG is low (< 90) and falling, force 0.0
        val isBraking = (bg < targetBg && velocity < -1.0) || (bg <= 90.0 && velocity < -2.0)
        
        val safeMultiplier = if (isBraking) {
            0.0
        } else {
            // Guarantee bounds
            multiplier.coerceIn(MIN_TBR_MULTIPLIER, MAX_TBR_MULTIPLIER)
        }

        val finalRate = currentRate * safeMultiplier

        return ControllerState(
            errorP = proportionalError,
            errorD = derivativeError,
            totalError = totalErrorSignal,
            sigmoidMultiplier = safeMultiplier,
            finalRate = finalRate,
            isBraking = isBraking
        )
    }

    enum class Mode {
        STANDARD, AGGRESSIVE, CONSERVATIVE
    }

    data class Input(
        val bg: Double,
        val targetBg: Double,
        val delta: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double,
        val iob: Double,
        val maxIob: Double,
        val profileBasal: Double,
        val variableSensitivity: Double,
        val duraISFminutes: Double,
        val predictedBgOverride: Double?,
        val mode: Mode
    )

    data class Decision(
        val rate: Double,
        val durationMin: Int,
        val reason: String
    )

    companion object {
        /**
         * Main compute function called by BasalDecisionEngine.
         * For now, it delegates back to a simplified instance/companion calculation
         * or provides a robust fallback logic using the same math.
         */
        fun compute(input: Input): Decision {
            // Replicate the logic simply to satisfy the interface for the general engine fallback.
            // Using similar math to `calculateDynamicRate` without injecting the logger for this static path.
            val proportionalError = input.bg - input.targetBg
            val velocity = input.delta * 0.8 + input.shortAvgDelta * 0.2
            
            // Braking
            if ((input.bg < input.targetBg && velocity < -1.0) || (input.bg <= 90.0 && velocity < -2.0)) {
                return Decision(0.0, 30, "PI-Brake: Fast Drop")
            }

            // P-D simplistic map for fallback
            var multiplier = 1.0 + (proportionalError * 0.05) + (velocity * 12.0 * 0.15)
            
            // Scale and constrain
            multiplier = multiplier.coerceIn(0.0, 10.0)
            
            // Adjust for High IOB vs Max IOB
            if (input.iob > input.maxIob) {
                multiplier *= 0.5 // Throttle if massive IOB exists
            }

            val finalRate = input.profileBasal * multiplier
            return Decision(
                rate = finalRate,
                durationMin = 30,
                reason = "PI-Fallback: P=%.1f D=%.1f Mult=%.2fx".format(proportionalError, velocity, multiplier)
            )
        }

        /**
         * Dedicated T3c Brittle Mode calculation.
         * T3c patients have zero endogenous insulin and glucagon, leading to extreme brittleness.
         * This function provides a pure proportional-derivative TBR (Temporary Basal Rate)
         * escalation without delivering micro-boluses (which are blocked by maxSMB=0.0 upstream).
         */
        fun computeT3c(
            bg: Double,
            targetBg: Double,
            delta: Float,
            shortAvgDelta: Double,
            longAvgDelta: Double,
            iob: Double,
            maxIob: Double,
            profileBasal: Double,
            isf: Double,
            duraISFminutes: Double,
            eventualBg: Double?
        ): Double {
            // If BG is dangerously low or dropping fast, immediately cut basal to 0%
            if (bg < 80.0 || (bg < targetBg && delta < -1.0)) {
                return 0.0
            }

            // If we have too much insulin on board relative to our max, throttle back
            if (iob > maxIob * 1.5) {
               return profileBasal * 0.1 // 10% basal
            }

            val currentError = bg - targetBg
            val futureError = (eventualBg ?: bg) - targetBg

            // Base Multiplier from Proportional Distance
            var multiplier = 1.0
            
            if (currentError > 0) {
                 // For every 30mg/dL above target, we add +100% to the basal rate
                 multiplier += (currentError / 30.0) 
            } else if (currentError < 0) {
                 // For every 15mg/dL below target, we halve the basal rate
                 multiplier *= exp(currentError / 15.0)
            }

            // Derivative modifier (Velocity)
            val velocity = delta * 0.7 + shortAvgDelta * 0.3
            if (velocity > 1.0) {
                 multiplier *= 1.5 // Climbing fast, aggressive boost
            } else if (velocity < -1.0) {
                 multiplier *= 0.5 // Falling fast, aggressive braking
            }

            // Cap at 1000% (10x) for extreme safety ceilings, though maxSafe will limit it later
            return (profileBasal * multiplier).coerceIn(0.0, profileBasal * 10.0)
        }
    }
}
