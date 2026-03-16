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
         * Dedicated T3c Brittle Mode calculation — ISF-driven, resistance-aware.
         *
         * T3c patients have zero endogenous insulin and glucagon. The main risks are:
         *  1. Prolonged hyperglycemia → glucotoxicity → transient insulin resistance
         *  2. Resistance resolving suddenly → accumulated IOB causes rapid hypoglycemia
         *
         * Strategy:
         *  - Correction activates at 130 mg/dL (not target=100) to prevent resistance
         *  - ISF-driven formula (not multiplier-based) → proper correction magnitude
         *  - Adaptive horizon: 20 min at BG 130–160, 15 min above 160 (more urgent)
         *  - Resistance→sensitivity transition detection: cut basal EARLY when drop begins
         *    after a prolonged hyperglycemic period (duraISFminutes)
         */
        fun computeT3c(
            bg: Double,
            targetBg: Double,
            delta: Float,
            shortAvgDelta: Double,
            longAvgDelta: Double,
            accel: Double,
            iob: Double,
            maxIob: Double,
            profileBasal: Double,
            isf: Double,
            duraISFminutes: Double,
            duraISFaverage: Double,
            eventualBg: Double?
        ): Double {
            val effectiveIsf = isf.coerceAtLeast(10.0)
            val velocity = delta * 0.7f + shortAvgDelta.toFloat() * 0.3f

            // ── Safety Guard 1: Adaptive Immediate Zero Basal ──────────────
            // [Improvement 1]: Thresholds are now relative to targetBg
            val floor = (targetBg - 20.0).coerceAtLeast(70.0)
            val cushion = (targetBg - 5.0).coerceAtLeast(85.0)
            if (bg < floor || (bg < cushion && delta < -1.0f) || (bg < targetBg && delta < -1.5f)) return 0.0

            // ── Safety Guard 2: Resistance→Sensitivity transition ──────────
            // [Improvement 3]: Smooth progressive cut instead of binary 0.0
            val wasChronicallyHigh = duraISFminutes > 20.0 && duraISFaverage > 140.0
            val nowFalling = velocity < -0.2f
            val resTransitionMult = if (wasChronicallyHigh && nowFalling) {
                // If velocity = -1.2 -> mult = 0.0. If velocity = -0.2 -> mult = 1.0
                (1.0 + (velocity + 0.2) / 1.0).coerceIn(0.0, 1.0)
            } else 1.0

            // ── Safety Guard 3: Excessive IOB ──────────────────────────────
            if (iob > maxIob * 1.5) return profileBasal * 0.1

            // ── T3C V3: Parabolic & Adaptive ───────────────────────────────
            
            // 1. Parabolic Projection
            // [Improvement 2]: Uses acceleration for better anticipation
            // Formula: BG(t) = BG + v*t + 0.5*a*t^2
            val projectionMins = if (delta >= 3.0f && delta > shortAvgDelta) 40.0 else 30.0
            val t = projectionMins / 5.0
            val projectedBg = bg + (velocity * t) + (0.5 * accel * t * t)
            
            // 2. Anticipation de la Cible (Smarter Braking)
            val baseToDeliver = if (projectedBg < targetBg) {
                profileBasal * exp((projectedBg - targetBg) / 15.0)
            } else {
                profileBasal
            }
            
            val projectedError = (projectedBg - targetBg).coerceAtLeast(0.0)
            
            // 3. Calcul du Besoin Brut T3C
            val requiredU = projectedError / effectiveIsf
            
            // 4. Multiplicateur Anti-Résistance (Glucotoxicité)
            val resistanceFactor = if (bg > 160.0) {
                1.0 + ((bg - 160.0) / 100.0).coerceAtMost(1.0)
            } else {
                1.0
            }
            
            // 5. Multiplicateur d'Accélération
            val accelFactor = if (delta >= 3.0f) {
                1.0 + (delta / 10.0).coerceAtMost(0.5)
            } else {
                1.0
            }
            
            // 6. Horizon de Livraison Réactif
            val deliveryHorizonHours = when {
                delta >= 3.0f || velocity >= 3.0f -> 0.16
                projectedBg > 160.0 -> 0.25
                else -> 0.33
            }
            
            val correctionRate = (requiredU / deliveryHorizonHours) * resistanceFactor * accelFactor
            
            // 7. Continuous Braking : Freinage linéaire basé sur la vitesse de chute
            val brakeFactor = (1.0 + velocity / 2.0).coerceIn(0.0, 1.0)

            // Apply BOTH brakeFactor AND resTransitionMult
            val totalRate = (baseToDeliver + correctionRate) * brakeFactor * resTransitionMult

            // Cap at 10× profileBasal
            return totalRate.coerceIn(0.0, profileBasal * 10.0)
        }
    }
}
