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
    private val horizonMinutes = 60          // On vérifie sur 60 minutes
    private val stepMinutes = 5              // Pas de simulation
    private val steps = horizonMinutes / stepMinutes
    private val targetBg = 100.0             // Cible agnostique dure
    private val maxSmbPer5Min = 2.0          // Limite dure d'optimisation (exploration)

    // Poids de la fonction de coût (J)
    private val qBg = 1.0                    // Pénalité pour l'écart au Target BG
    private val rInsulin = 50.0              // Pénalité pour injecter de l'insuline (évite l'agressivité max)

    /**
     * Calcule la dose optimale pour les 5 prochaines minutes (Receding Horizon).
     */
    fun calculateOptimalDose(state: AutoDriveState, profileBasal: Double): AutoDriveCommand {
        var bestDose = 0.0
        var minCost = Double.MAX_VALUE

        // SQP très primitif (Grid Search) pour éviter d'importer un solver lourd NLOPT.
        // On teste des doses potentielles de 0.0U à `maxSmbPer5Min` U avec un pas de 0.1U
        val doseCandidates = generateSequence(0.0) { it + 0.1 }.takeWhile { it <= maxSmbPer5Min }.toList()

        for (candidateDose in doseCandidates) {
            val cost = simulateAndCost(candidateDose, state)
            if (cost < minCost) {
                minCost = cost
                bestDose = candidateDose
            }
        }

        aapsLogger.debug(
            LTag.APS,
            "🧮 [MPC] Optimal U=${bestDose.format(2)} found with Cost=$minCost"
        )

        // On convertit la dose optimale en Commande Autodrive
        // Pour simplifier l'intégration dans APS, si c'est petit on met en Basal (TBR),
        // sinon on envoie un SMB massif.
        val microbolus = if (bestDose >= 0.1) bestDose else 0.0
        val tbr = if (bestDose < 0.1) (bestDose * 12.0) else profileBasal // Dose/5min * 12 = U/h

        return AutoDriveCommand(
            temporaryBasalRate = tbr,
            scheduledMicroBolus = microbolus,
            reason = "[MPC] Cost=$minCost | H=60"
        )
    }

    /**
     * Simule la trajectoire pour une dose candidate U donnée et retourne le coût total J.
     */
    private fun simulateAndCost(doseU: Double, startState: AutoDriveState): Double {
        var currentBg = startState.bg
        var currentIob = startState.iob + doseU
        var totalCost = 0.0

        // Paramètres Bergman Modifiés
        val p1 = 0.01

        for (k in 1..steps) {
            // Modèle de simulation (Dérivée Euler simple)
            // dBG/dt = - (p1 + SI * IOB) * BG + p1 * Target + Ra
            val deltaBgPerMin = - (p1 + startState.estimatedSI * currentIob) * currentBg + (p1 * targetBg) + startState.estimatedRa
            val deltaBgPer5 = deltaBgPerMin * stepMinutes

            currentBg += deltaBgPer5
            
            // Decroissance naïve de l'IOB (Approx)
            currentIob *= 0.95 

            // Somme des coûts au stade k
            val errorBg = (currentBg - targetBg)
            totalCost += (qBg * errorBg * errorBg)
        }

        // Ajout du coût de l'action (Regularisation de l'agressivité)
        totalCost += (rInsulin * doseU * doseU)

        return totalCost
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
