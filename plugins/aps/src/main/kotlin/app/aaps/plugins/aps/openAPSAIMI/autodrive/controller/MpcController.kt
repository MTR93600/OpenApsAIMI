package app.aaps.plugins.aps.openAPSAIMI.autodrive.controller

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * 🧮 Model Predictive Controller (MPC) - Autodrive Phase 3
 * 
 * Ce contrôleur remplace complètement les heuristiques classiques (PD, Sigmoïde).
 * Il prend l'état courant estimé (BG, SI, Ra) et simule le tur sur N étapes pour trouver
 * la séquence d'insuline optimale minimisant l'écart par rapport à la cible (100 mg/dL).
 * 
 * Il ne gère pas la sécurité absolue (Hypo). Cela sera géré par la Phase 4 (CBF).
 */
@Singleton
class MpcController @Inject constructor(
    private val aapsLogger: AAPSLogger
) {


    // Paramètres MPC
    private val horizonMinutes = 180          // On vérifie sur 180 minutes (Weighted Horizon)
    private val stepMinutes = 5              // Pas de simulation
    private val steps = horizonMinutes / stepMinutes
    // Poids de base (Modifiés dynamiquement si Nuit/Jour)
    private val qBg = 1.0                    // Pénalité pour l'écart au Target BG

    /**
     * Calcule la dose optimale pour les 5 prochaines minutes (Receding Horizon).
     */
    fun calculateOptimalDose(state: AutoDriveState, profileBasal: Double, lgsThreshold: Double): AutoDriveCommand {
        // Optimisation Newton-Raphson 1D simplifiée ou Recherche Dichotomique pour trouver min(J)
        // La fonction J(u) est supposée convexe par rapport à la dose d'insuline injectée maintement.
        

        // 🌙 PROTECTION NOCTURNE ANTI-HYPO (Phase 3+)
        // La nuit, on dort : pas de repas en cours, la sensibilité est souvent stable.
        // On interdit au solver de chercher la perfection absolue et on sur-pénalise l'injection
        // d'insuline rapide (SMB) pour privilégier des basales douces.
        val activeTargetBg: Double
        val activeRInsulin: Double
        val activeMaxSmb: Double

        if (state.isNight) {
            activeTargetBg = 110.0 // On rehausse la cible de sécurité
            activeRInsulin = 150.0 // On multiplie par 3 le coût (plus proactif que 5x)
            activeMaxSmb = 0.5 // On refuse d'envoyer de fortes doses d'un coup
        } else {
            activeTargetBg = 100.0
            
            // 🚀 DAWN GUARD CONSERVATISM
            // Si on suspecte un pic de cortisol (Matériel + Heure + Pas de glucides + Pas de pas), 
            // on rend l'IA extrêmement prudente sur l'envoi de bolus (SMB).
            val isDawnWindow = state.hour in 5..9
            val isLowActivity = state.steps < 200
            val isDawnRiseSuspected = isDawnWindow && isLowActivity && (state.hr > state.rhr + 5) && state.cob < 0.1

            if (isDawnRiseSuspected) {
                activeRInsulin = 100.0 // L'insuline est "très chère" : on préfère la basale lente
                activeMaxSmb = state.maxSMB * 0.5 // On divise par 2 le plafond de bolus
            } else if (state.estimatedRa > 3.0) {
                // 🧨 DYNAMIC AGGRESSIVENESS (Phase 11 - Unannounced Meal Crushing)
                activeRInsulin = 10.0
                activeMaxSmb = state.highBgMaxSMB 
            } else if (state.bg > 120.0) {
                activeRInsulin = 20.0
                activeMaxSmb = state.highBgMaxSMB
            } else {
                activeRInsulin = 30.0
                activeMaxSmb = state.maxSMB
            }
        }

        // 🛡️ WEIGHT-AWARENESS SAFETY CAP (Phase 7)
        // L'agressivité mathématique du solver est physiquement bridée par le poids du corps.
        // Un adulte de 70kg ne pourra jamais recevoir une dose supérieure à 3.5U (0.05 * 70kg) sur 5 minutes.
        // Un enfant de 20kg sera physiquement bloqué à 1.0U max.
        val maxSafeDoseU = min(activeMaxSmb, state.patientWeightKg * 0.05)

        // Résolution via balayage fin sur le domaine praticable Sécurisé [0.0 , maxSafeDoseU]
        // Un pas de 0.005U sur 5 min équivaut à un ajustement basal lisse de 0.06 U/h
        val searchStep = 0.005
        val doseCandidates = generateSequence(0.0) { it + searchStep }
            .takeWhile { it <= maxSafeDoseU }
            .toList()

        // 📊 MULTI-HORIZON COMPARISON (Alignment Request)
        // We find the optimal dose for each horizon to provide a comparative view in the logs.
        val horizons = listOf(60, 120, 180)
        val optimalDoses = mutableMapOf<Int, Double>()

        for (h in horizons) {
            var bestDoseForH = 0.0
            var minCostForH = Double.MAX_VALUE
            val hSteps = h / stepMinutes

            for (candidateDose in doseCandidates) {
                val cost = simulateAndCost(candidateDose, state, activeTargetBg, activeRInsulin, lgsThreshold, state.isNight, hSteps)
                if (cost < minCostForH) {
                    minCostForH = cost
                    bestDoseForH = candidateDose
                }
            }
            optimalDoses[h] = bestDoseForH
        }

        val bestDose = optimalDoses[180] ?: 0.0 // We act on the safest (180w) result
        
        aapsLogger.debug(
            LTag.APS,
            "🧮 [MPC] Alignment Comparison: 60=${optimalDoses[60]!!.format(2)}U, 120=${optimalDoses[120]!!.format(2)}U, 180w=${bestDose.format(2)}U"
        )

        // Traitement de la sortie :
        val maxTbrMultiplier = if (state.isNight) 5.0 else 3.0
        var tbrUph = min(bestDose * 12.0, profileBasal * maxTbrMultiplier)
        
        // 🛡️ DYNAMIC BASAL CUSHION (Amortisseur de Basale)
        // On remplace le blocage brut à 100 mg/dL par un palier dépendant de la vélocité.
        // Cela permet de couper la basale à 110-120 si on chute, tout en protégeant le profil si on est stable.
        val floorMultiplier = when {
            state.bg > 130.0 -> 1.0            // Zone haute : Basale profil maintenue
            state.bg > 100.0 -> {
                when {
                    state.bgVelocity > -0.5 -> 1.0 // Stable/Montée : Garder 100%
                    state.bgVelocity < -1.5 -> 0.0 // Chute rapide : Autoriser 0%
                    else -> 0.5                     // Descente douce : Amortisseur à 50%
                }
            }
            else -> 0.0                        // Zone < 100mg/dL : Liberté totale (0%)
        }
        
        val floorTbr = profileBasal * floorMultiplier
        if (tbrUph < floorTbr) {
            tbrUph = floorTbr
        }

        val smbU = max(0.0, bestDose - (tbrUph / 12.0))
        val comparisonStr = "H:[60:${optimalDoses[60]!!.format(2)}|120:${optimalDoses[120]!!.format(2)}|180:${bestDose.format(2)}]"

        return AutoDriveCommand(
            temporaryBasalRate = tbrUph,
            scheduledMicroBolus = smbU,
            reason = "[MPC] $comparisonStr"
        )
    }

    /**
     * Simule la trajectoire pour une dose candidate U donnée et retourne le coût total J.
     */
    private fun simulateAndCost(doseU: Double, startState: AutoDriveState, activeTargetBg: Double, activeRInsulin: Double, lgsThreshold: Double, isNight: Boolean, customSteps: Int? = null): Double {
        var currentBg = startState.bg
        var currentIob = startState.iob + doseU
        var totalCost = 0.0
        val targetSteps = customSteps ?: steps

        // Modèle Métabolique Simplifié EKF/UKF (Bergman Minimal Model paramétré par state)
        // p1 = efficacité de base du glucose à se résorber seul (GEZI)
        val p1 = 0.015 
        
        // La sensibilité est dynamique, estimée dans l'état, mise à l'échelle (/10000)
        val si = startState.estimatedSI

        for (k in 1..targetSteps) {
            // -- Dynamique du Glucose (Simulation Euler sur 5 min) --
            // Formulation classique : dBG/dt = - [p1 + SI * IOB]*(BG) + p1*BG_Target + Ra
            // IMPORTANT : Limite biologique stricte à 0.0 pour empêcher l'explosion mathématique avec une IOB très négative
            val glucoseClearanceRate = max(0.0, p1 + (si * currentIob))
            val deltaBgPerMin = - (glucoseClearanceRate) * currentBg + (p1 * activeTargetBg) + startState.estimatedRa
            val deltaBgPer5 = deltaBgPerMin * stepMinutes

            currentBg += deltaBgPer5
            
            // -- Dynamique de l'Insuline --
            // Décroissance de l'IOB selon un modèle à 1 compartiment (demi-vie ~ 50 min)
            currentIob *= 0.90 

            // -- Évaluation du Coût (Weighted Horizon) --
            // On applique une pondération temporelle (W_k) pour privilégier la réactivité court-terme
            // tout en gardant une surveillance critique sur le stacking long-terme.
            val currentTimeMin = k * stepMinutes
            val timeWeight = when {
                currentTimeMin <= 60 -> 1.0     // 0-60m : Pleine réactivité
                currentTimeMin <= 120 -> 0.5    // 60-120m : Convergence stratégique
                else -> 0.2                     // 120-180m : Sécurité anti-stacking
            }

            // Pénalité asymétrique : l'hypo (BG < Cible) est lourdement pénalisée par rapport à l'hyper
            val errorBg = currentBg - activeTargetBg
            val scaledErrorBg = errorBg / 10.0 // Divide par 10 pour que le carré (divisé par 100) soit comparable au coût de RInsulin
            
            // 🚀 REACTIVITY BOOST: On triple le poids de l'erreur en hyper (>0) pour écraser le pic
            val qPenalty = if (errorBg < 0) qBg * 5.0 else qBg * 3.0
            
            totalCost += timeWeight * (qPenalty * scaledErrorBg * scaledErrorBg)
            // 🛡️ LETHAL SAFETY FALLBACK (Check throughout the horizon)
            // La simulation ne doit JAMAIS franchir le seuil létal absolu. 
            // La nuit, on renforce la marge à +10 mg/dL pour forcer la coupure préventive.
            val lgsBuffer = if (isNight) 10.0 else 5.0
            if (currentBg < lgsThreshold + lgsBuffer) {
                totalCost += 1_000_000.0
            }
        }

        // Ajout du coût de régularisation R
        // On réintroduit un léger terme linéaire pour tuer les micro-bolus inutiles (bruit)
        totalCost += (activeRInsulin * doseU * doseU) + (activeRInsulin * 0.1 * doseU)

        return totalCost
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
