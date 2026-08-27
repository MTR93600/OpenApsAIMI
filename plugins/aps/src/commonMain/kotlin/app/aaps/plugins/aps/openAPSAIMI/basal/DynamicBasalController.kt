package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
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
@SingleIn(AppScope::class)
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
        shortAvgDelta: Double,
        projectionHorizonMin: Double? = null,
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
        //
        // 🎯 Lot 1 — formulation en **erreur projetée**. Le couple (P, D) historique combinait deux
        // grandeurs d'unités différentes avec des poids indépendants : `P_WEIGHT = 0.05` par mg/dL
        // d'écart contre `12 * D_WEIGHT = 1.8` par mg/dL/5min de pente, soit un rapport de **36:1**.
        // Une montée banale de +2,5 mg/dL/5min ajoutait +4,5 au multiplicateur, ce qu'il aurait fallu
        // compenser par une glycémie 90 mg/dL sous la cible : le terme proportionnel ne pouvait donc
        // jamais freiner le terme dérivé, et le contrôleur demandait 5 à 8× le basal profil alors que la
        // glycémie était *sous* la cible (observé en production les 01 et 02/08/2026).
        //
        // On projette désormais la glycémie sur l'horizon d'action de l'insuline et on mesure **un seul**
        // écart. Le gain dérivé n'est plus un réglage libre : il vaut `P_WEIGHT * horizon`, ce qui le rend
        // dimensionnellement cohérent avec le gain proportionnel. À glycémie stable le résultat est
        // identique à l'ancien ; seule l'influence de la pente est ramenée à une valeur physiologique.
        // ⚠️ Asymétrie montée/descente. La projection réduit l'influence de la pente de 1,8 à 0,6 par
        // mg/dL/5min : bénéfique en **montée** (c'est le défaut corrigé), mais en **descente** elle
        // freinerait *moins* que l'ancienne formulation — sur une hyper qui redescend vite, l'ancien
        // signal tombait à 0 là où le projeté demanderait encore ~1,8× le profil. On retient donc le
        // **minimum** des deux : jamais plus agressif que le comportement historique, sur tout le domaine.
        val derivativeError = velocity * 12.0
        val legacySignal = (proportionalError * P_WEIGHT) + (derivativeError * D_WEIGHT)
        val totalErrorSignal = if (projectionHorizonMin != null && projectionHorizonMin > 0.0) {
            val steps = projectionHorizonMin / 5.0
            val projectedBg = bg + velocity * steps
            min((projectedBg - targetBg) * P_WEIGHT, legacySignal)
        } else {
            legacySignal
        }

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
        val mode: Mode,
        /** Horizon de projection (min) du lot 1 ; `null` = ancienne formulation P+D. */
        val projectionHorizonMin: Double? = null,
    )

    data class Decision(
        val rate: Double,
        val durationMin: Int,
        val reason: String
    )

    companion object {

        /**
         * Horizon de projection du lot 1, en minutes : délai d'action typique de l'insuline rapide.
         * Le gain dérivé effectif vaut `P_WEIGHT * (horizon / 5)`, soit 0,6 par mg/dL/5min à 60 min —
         * contre 1,8 dans l'ancienne formulation.
         */
        const val PROJECTION_HORIZON_MIN = 60.0

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
            val horizon = input.projectionHorizonMin
            
            // Braking
            if ((input.bg < input.targetBg && velocity < -1.0) || (input.bg <= 90.0 && velocity < -2.0)) {
                return Decision(0.0, 30, "PI-Brake: Fast Drop")
            }

            // P-D simplistic map for fallback
            // 🎯 Lot 1 — même refonte que [calculateDynamicRate] : erreur projetée au lieu d'un couple
            // (P, D) dont le gain dérivé valait 36× le gain proportionnel. Voir le commentaire détaillé
            // dans [calculateDynamicRate].
            val legacyMultiplier = 1.0 + (proportionalError * 0.05) + (velocity * 12.0 * 0.15)
            // Voir [calculateDynamicRate] : minimum des deux formulations, pour ne jamais freiner moins
            // que l'ancienne sur une descente.
            var multiplier = if (horizon != null && horizon > 0.0) {
                val projectedBg = input.bg + velocity * (horizon / 5.0)
                min(1.0 + (projectedBg - input.targetBg) * 0.05, legacyMultiplier)
            } else {
                legacyMultiplier
            }
            
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
                reason = if (horizon != null && horizon > 0.0)
                    "PI-Projected: P=${aimiFmt1(proportionalError)} D=${aimiFmt1(velocity)} H=${aimiFmt0(horizon)}min Proj=${aimiFmt0(input.bg + velocity * (horizon / 5.0))} Mult=${aimiFmt2(multiplier)}x"
                else
                    "PI-Fallback: P=${aimiFmt1(proportionalError)} D=${aimiFmt1(velocity)} Mult=${aimiFmt2(multiplier)}x"
            )
        }

        /**
         * Kinematic BG projection shared by the T3C rise-unlock gate and [computeT3c].
         * Parabolic (constant-accel) forecast of where BG is heading, so the unlock decision and the
         * correction magnitude agree on the same projection. Pure; no eventualBg blending here — kept
         * independent of the pkpd/UAM eventual so the unlock is not dragged by a floored eventual.
         */
        fun projectBg(bg: Double, delta: Float, shortAvgDelta: Double, accel: Double): Double {
            val velocity = delta * 0.7f + shortAvgDelta.toFloat() * 0.3f
            val projectionMins = if (delta >= 3.0f && delta > shortAvgDelta) 40.0 else 30.0
            val t = projectionMins / 5.0
            return bg + (velocity * t) + (0.5 * accel * t * t)
        }

        /**
         * Dedicated T3c Brittle Mode calculation — ISF-driven, resistance-aware.
         *
         * T3c patients have zero endogenous insulin and glucagon. The main risks are:
         *  1. Prolonged hyperglycemia → glucotoxicity → transient insulin resistance
         *  2. Resistance resolving suddenly → accumulated IOB causes rapid hypoglycemia
         *
         * Strategy:
         *  - Correction activates once the **projected** BG exceeds `activationThreshold` (configurable,
         *    default 100) — anticipatory: engages on where BG is heading, not current BG
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
            eventualBg: Double?,
            activationThreshold: Double = 130.0,
            aggressiveness: Double = 1.0,
            maxBasalCap: Double? = null,
            trajectory: T3cTrajectoryContext? = null,
            anticipationHints: T3cAnticipation.Hints = T3cAnticipation.Hints.DISABLED
        ): Double {
            val effectiveIsf = isf.coerceAtLeast(10.0)
            val velocity = delta * 0.7f + shortAvgDelta.toFloat() * 0.3f

            // ── Safety Guard 1: Adaptive Immediate Zero Basal ──────────────
            val floor = (targetBg - 20.0).coerceAtLeast(70.0)
            val cushion = (targetBg - 5.0).coerceAtLeast(85.0)
            if (bg < floor || (bg < cushion && delta < -1.0f) || (bg < targetBg && delta < -1.5f)) return 0.0

            // ── Safety Guard 2: Resistance→Sensitivity transition ──────────
            val wasChronicallyHigh = duraISFminutes > 20.0 && duraISFaverage > activationThreshold
            val nowFalling = velocity < -0.2f
            val resTransitionMult = if (wasChronicallyHigh && nowFalling) {
                (1.0 + (velocity + 0.2) / 1.0).coerceIn(0.0, 1.0)
            } else 1.0

            // ── Safety Guard 3: Excessive IOB ──────────────────────────────
            if (iob > maxIob * 1.5) return profileBasal * 0.1

            // ── T3C V3: Parabolic & Adaptive ───────────────────────────────
            
            // 1. Parabolic Projection
            // USES activationThreshold instead of targetBg for engagement trigger
            val projectedBg = projectBg(bg, delta, shortAvgDelta, accel)
            val effectiveProjectedBg = T3cAnticipation.blendProjectedForHyper(
                projectedBg = projectedBg,
                eventualBg = eventualBg,
                strength = anticipationHints.strength
            )

            // 2. Anticipation de la Cible (Smarter Braking)
            val baseToDeliver = if (effectiveProjectedBg < targetBg) {
                profileBasal * exp((effectiveProjectedBg - targetBg) / 15.0)
            } else {
                profileBasal
            }

            // Use activationThreshold as the reference for correction
            val projectedError = (effectiveProjectedBg - activationThreshold).coerceAtLeast(0.0)
            
            // 3. Calcul du Besoin Brut T3C
            val requiredU = projectedError / effectiveIsf
            
            // 4. Multiplicateur Anti-Résistance (Glucotoxicité)
            val resistanceFactor = if (effectiveProjectedBg > activationThreshold + 30.0) {
                1.0 + ((effectiveProjectedBg - (activationThreshold + 30.0)) / 100.0).coerceAtMost(1.0)
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
            val deliveryHorizonHoursRaw = when {
                delta >= 3.0f || velocity >= 3.0f -> 0.16
                effectiveProjectedBg > activationThreshold + 30.0 -> 0.25
                else -> 0.33
            }
            val deliveryHorizonHours = T3cAnticipation.compressDeliveryHorizonHours(
                baseHorizonHours = deliveryHorizonHoursRaw,
                minutesToHyperExcursion = anticipationHints.minutesToHyperExcursion,
                strength = anticipationHints.strength
            )

            // Apply AGGRESSIVENESS here
            val correctionRate = (requiredU / deliveryHorizonHours) * resistanceFactor * accelFactor * aggressiveness

            // 7. Continuous Braking : Freinage linéaire basé sur la vitesse de chute
            val brakeFactor = (1.0 + velocity / 2.0).coerceIn(0.0, 1.0)

            // Apply BOTH brakeFactor AND resTransitionMult; then curve-based hypo lead (IOB/COB envelope)
            var totalRate = (baseToDeliver + correctionRate) * brakeFactor * resTransitionMult
            totalRate *= T3cAnticipation.hypoLeadMultiplier(anticipationHints, targetBg)

            // Cap at configured max basal (T3C should respect user-defined hard ceiling).
            // Fallback keeps legacy behavior if cap is not provided.
            val effectiveCap = (maxBasalCap ?: (profileBasal * 10.0)).coerceAtLeast(profileBasal)
            var out = totalRate.coerceIn(0.0, effectiveCap)
            trajectory?.let { ctx ->
                out = applyT3cTrajectoryHypoBrake(
                    rate = out,
                    profileBasal = profileBasal,
                    targetBg = targetBg,
                    ctx = ctx
                )
            }
            // eventualBg + prediction envelopes feed [anticipationHints] on the T3C path; trajectory.ctx still applies last.
            return out.coerceIn(0.0, effectiveCap)
        }

        /**
         * Reduces T3c basal when the prediction curve or trajectory metrics anticipate hypo
         * before current BG reflects it (isolated from main AIMI; used only with [T3cTrajectoryContext]).
         */
        private fun applyT3cTrajectoryHypoBrake(
            rate: Double,
            profileBasal: Double,
            targetBg: Double,
            ctx: T3cTrajectoryContext
        ): Double {
            val guard = T3cTrajectoryContext.guardBg(ctx)
            if (!guard.isFinite()) return rate
            val lgs = ctx.lgsThresholdMgdl
            var out = rate

            if (guard <= lgs) {
                return min(profileBasal * 0.05, out)
            }
            if (guard <= lgs + 10.0) {
                out = min(out, profileBasal * 0.12)
            }

            if (guard < targetBg) {
                val span = (targetBg - lgs).coerceAtLeast(12.0)
                val headroom = ((guard - lgs) / span).coerceIn(0.0, 1.0)
                out *= (headroom * headroom).coerceIn(0.08, 1.0)
            }

            val energy = ctx.energyBalance
            if (energy != null && energy > 1.5) {
                val energyCap = when {
                    energy > 3.5 -> profileBasal * 0.25
                    energy > 2.5 -> profileBasal * 0.50
                    else -> profileBasal * 0.70
                }
                out = min(out, energyCap)
            }

            if (ctx.trajectoryAnalysisActive &&
                ctx.trajectoryTypeName == "TIGHT_SPIRAL" &&
                guard < targetBg + 30.0
            ) {
                out = min(out, profileBasal * 0.65)
            }

            val conv = ctx.convergenceVelocity
            if (ctx.trajectoryAnalysisActive && conv != null && conv > 0.18 && guard < targetBg) {
                out *= 0.82
            }

            return out.coerceAtLeast(0.0)
        }
    }
}

/**
 * Snapshot used by T3c basal braking.
 *
 * `build()` is not here: it needs `TrajectoryAnalysis`, which is still dump-only.
 * A later T1 wave should replace this with dump `T3cTrajectoryContext.kt`.
 */
data class T3cTrajectoryContext(
    val minPredBg: Double,
    val eventualPredBg: Double,
    val lgsThresholdMgdl: Double,
    val trajectoryAnalysisActive: Boolean,
    val convergenceVelocity: Double?,
    val energyBalance: Double?,
    val trajectoryTypeName: String?
) {
    companion object {
        /** Lowest credible BG among prediction curve and eventual BG. */
        fun guardBg(ctx: T3cTrajectoryContext): Double =
            min(ctx.minPredBg, ctx.eventualPredBg)
    }
}
