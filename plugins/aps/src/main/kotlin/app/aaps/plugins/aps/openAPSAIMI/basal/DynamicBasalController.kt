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
            iob: Double,
            maxIob: Double,
            profileBasal: Double,
            isf: Double,
            duraISFminutes: Double,
            eventualBg: Double?
        ): Double {
            val effectiveIsf = isf.coerceAtLeast(10.0)
            val velocity = delta * 0.7f + shortAvgDelta.toFloat() * 0.3f

            // ── Safety Guard 1: Immediate zero basal ───────────────────────
            if (bg < 80.0 || (bg < targetBg && delta < -1.5f)) return 0.0

            // ── Safety Guard 2: Resistance→Sensitivity transition ──────────
            // After prolonged hyperglycemia (duraISF > 30 min), any drop signals
            // that accumulated IOB is now becoming effective. Pre-emptive cut.
            val wasChronicallyHigh = duraISFminutes > 30.0
            val nowFalling = velocity < -0.5f
            val highIobPresent = iob > maxIob * 0.35
            if (wasChronicallyHigh && nowFalling && highIobPresent) {
                return 0.0  // resistance resolved → IOB acting → pre-emptive zero
            }

            // ── Safety Guard 3: Excessive IOB ──────────────────────────────
            if (iob > maxIob * 1.5) return profileBasal * 0.1

            // ── T3C V2: Prédictif et Agressif ──────────────────────────────
            
            // 1. Projection
            // On veut corriger sur la base du BG projeté (s'il est plus haut que le BG actuel)
            // Si la montée est violente, on projette plus loin
            val projectionMins = if (delta >= 3.0f && delta > shortAvgDelta) 40.0 else 30.0
            val projectedBg = bg + (velocity * (projectionMins / 5.0))
            val effectiveBgToCorrect = projectedBg.coerceAtLeast(bg)
            
            // Si on est sous la cible et stable ou en baisse, approche douce exponentielle
            if (bg < targetBg && projectedBg < targetBg) {
                return profileBasal * exp((bg - targetBg) / 20.0)
            }
            
            val projectedError = (effectiveBgToCorrect - targetBg).coerceAtLeast(0.0)
            
            // 2. Calcul du Besoin Brut T3C
            // Pour le T3C on convertit directement l'erreur en insuline (sans retirer the IOB localement 
            // pour garantir la force de la pente. La Guard 3 limite l'accumulation totale d'IOB).
            val requiredU = projectedError / effectiveIsf
            
            // 3. Multiplicateur Anti-Résistance (Glucotoxicité)
            // Passe de 1.0x (à 160) jusqu'à 2.0x (à 260) pour vaincre la résistance installée
            val resistanceFactor = if (bg > 160.0) {
                1.0 + ((bg - 160.0) / 100.0).coerceAtMost(1.0)
            } else {
                1.0
            }
            
            // 3.b Multiplicateur d'Accélération (Nouveau)
            // Si on monte très vite, on booste l'insuline immédiatement pour casser la courbe
            val accelFactor = if (delta >= 3.0f) {
                1.0 + (delta / 10.0).coerceAtMost(0.5) // Jusqu'à +50%
            } else {
                1.0
            }
            
            // 4. Horizon de Livraison Réactif (en Heures)
            // Plus on monte vite, plus on compresse l'horizon pour expédier l'insuline comme un bolus
            val deliveryHorizonHours = when {
                delta >= 3.0f || velocity >= 3.0f -> 0.16   // 10 mins (Urgence Post-Repas ou Montée Fulgurante)
                effectiveBgToCorrect > 160.0 -> 0.25        // 15 mins (Réponse Rapide)
                else -> 0.33                                // 20 mins (Standard T3C)
            }
            
            // Taux de correction ciblé
            val correctionRate = (requiredU / deliveryHorizonHours) * resistanceFactor * accelFactor
            
            // 5. Braking override : on protège les chutes non-détectées par la Guard 1
            val brakeFactor: Double = when {
                velocity < -2.0f -> return 0.0              // chute rapide → zero immédiat
                velocity < -0.5f -> 0.4                     // chute modérée → 40%
                else             -> 1.0
            }

            val totalRate = profileBasal + (correctionRate * brakeFactor)

            // Cap at 10× profileBasal (maxBasal from profile applied externally)
            return totalRate.coerceIn(0.0, profileBasal * 10.0)
        }
    }
}
