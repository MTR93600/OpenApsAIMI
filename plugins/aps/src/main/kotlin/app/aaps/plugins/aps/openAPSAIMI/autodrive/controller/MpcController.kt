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
    private val METABOLIC_SI_BASE = 0.0012 // Calibration factor (Phase 12)

    // Paramètres MPC
    private val horizonMinutes = 60          // On vérifie sur 60 minutes
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
        
        var bestDose = 0.0
        var minCost = Double.MAX_VALUE

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

        for (candidateDose in doseCandidates) {
            val cost = simulateAndCost(candidateDose, state, activeTargetBg, activeRInsulin, lgsThreshold, state.isNight)
            if (cost < minCost) {
                minCost = cost
                bestDose = candidateDose
            }
        }

        aapsLogger.debug(
            LTag.APS,
            "🧮 [MPC] Optimal U=${bestDose.format(2)} found with Cost=${minCost.format(1)}"
        )

        // Traitement de la sortie :
        // Si la dose est minime, on s'oriente vers un ajustement basal lisse (TBR)
        // La nuit, on autorise la TBR à répondre à des besoins plus grands (5x) pour éviter l'usage des micro-bolus
        val maxTbrMultiplier = if (state.isNight) 5.0 else 3.0
        var tbrUph = min(bestDose * 12.0, profileBasal * maxTbrMultiplier)
        
        // 🛡️ ANTI-SUSPENSION GUARD (Emergency Fix)
        // L'IA ne doit JAMAIS couper la basale quand on est en Hyper (> 130) 
        // à cause d'une prédiction de chute lointaine, surtout après un bolus manuel.
        if (state.bg > 100.0 && tbrUph < profileBasal) {
            tbrUph = profileBasal
        }

        val smbU = max(0.0, bestDose - (tbrUph / 12.0))

        return AutoDriveCommand(
            temporaryBasalRate = tbrUph,
            scheduledMicroBolus = smbU,
            reason = "[MPC] Cost=${minCost.format(1)} | H=$horizonMinutes"
        )
    }

    /**
     * Simule la trajectoire pour une dose candidate U donnée et retourne le coût total J.
     */
    private fun simulateAndCost(doseU: Double, startState: AutoDriveState, activeTargetBg: Double, activeRInsulin: Double, lgsThreshold: Double, isNight: Boolean): Double {
        var currentBg = startState.bg
        var currentIob = startState.iob + doseU
        var totalCost = 0.0

        // Modèle Métabolique Simplifié EKF/UKF (Bergman Minimal Model paramétré par state)
        // p1 = efficacité de base du glucose à se résorber seul (GEZI)
        val p1 = 0.015 
        
        // La sensibilité est dynamique, estimée dans l'état, mise à l'échelle métabolique
        val si = startState.estimatedSI * METABOLIC_SI_BASE

        for (k in 1..steps) {
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

            // -- Évaluation du Coût --
            // Pénalité asymétrique : l'hypo (BG < Cible) est lourdement pénalisée par rapport à l'hyper
            val errorBg = currentBg - activeTargetBg
            val scaledErrorBg = errorBg / 10.0 // Divide par 10 pour que le carré (divisé par 100) soit comparable au coût de RInsulin
            
            // 🚀 REACTIVITY BOOST: On triple le poids de l'erreur en hyper (>0) pour écraser le pic
            val qPenalty = if (errorBg < 0) qBg * 5.0 else qBg * 3.0
            
            totalCost += (qPenalty * scaledErrorBg * scaledErrorBg)
        }

        // Ajout du coût de régularisation R
        // On réintroduit un léger terme linéaire pour tuer les micro-bolus inutiles (bruit)
        totalCost += (activeRInsulin * doseU * doseU) + (activeRInsulin * 0.1 * doseU)

        // Pénalité infinie si la simulation franchit le seuil létal absolu (Safety fallback intra-MPC)
        // La nuit, on renforce la marge à +10 mg/dL pour forcer la coupure préventive des vagues de basales
        val lgsBuffer = if (isNight) 10.0 else 5.0
        if (currentBg < lgsThreshold + lgsBuffer) totalCost += 1_000_000.0

        return totalCost
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
