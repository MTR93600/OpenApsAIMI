package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🎓 Online Learner - Autodrive Phase 5
 * 
 * Remplace les MAJ asynchrones (6h/24h) par une descente de gradient continue.
 * L'objectif est d'ajuster lentement les "priors" de l'Estimator (Phase 2) ou les poids
 * physiologiques à chaque cycle de 5 minutes.
 * 
 * Mécanisme : On sauvegarde la prédiction faite à T=0 pour l'horizon T+30.
 * À T+30, on compare $BG_{pred}$ avec $BG_{actuel}$. Le gradient de cette erreur sert
 * à mettre à jour les hyperparamètres (comme l'efficacité de l'insuline basale du patient).
 */
@Singleton
class OnlineLearner @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    
    // Historique des prédictions (Pour comparer T-actuel avec T-30min)
    private val predictionHistory = mutableMapOf<Long, Double>()
    
    // Hyperparamètre appris continuellement (ex: Basal Endogène / Résistance)
    // On commence neutre (1.0)
    var learnedResistanceFactor: Double = 1.0
        private set

    private val learningRate = 0.005 // Descente de Gradient très lente (sécurité)

    /**
     * Appelé à chaque Tique (5 min).
     */
    fun learnAndUpdate(currentState: AutoDriveState, currentEpochMs: Long) {
        
        // 1. Enregistre une prédiction naïve pour le futur (Dans 30 minutes)
        // C'est un mock simple pour valider l'architecture. Le vrai système utiliserait
        // la trajectoire calculée par le MPC.
        val predictedBgIn30m = currentState.bg + (currentState.bgVelocity * 30.0)
        val futureTimeMs = currentEpochMs + (30 * 60 * 1000)
        predictionHistory[futureTimeMs] = predictedBgIn30m

        // 2. Recherche d'une prédiction passée correspondant au temps actuel (avec une petite tolérance)
        // On cherche une clé proche de `currentEpochMs` (± 2.5 min)
        val toleranceMs = 2.5 * 60 * 1000
        val matchedEntry = predictionHistory.entries.find { 
            Math.abs(it.key - currentEpochMs) < toleranceMs 
        }

        if (matchedEntry != null) {
            val pastPrediction = matchedEntry.value
            
            // 3. Calcul de l'erreur (Réalité - Prédiction)
            val error = currentState.bg - pastPrediction

            // 4. Update (Gradient Descent Step)
            // Si on a fini plus HAUT que prédit (error > 0), c'est qu'on est plus résistant
            // Si on a fini plus BAS que prédit (error < 0), c'est qu'on est plus sensible
            val gradient = error * 0.001 // Normalisation de l'erreur
            
            val previousFactor = learnedResistanceFactor
            learnedResistanceFactor += (learningRate * gradient)
            
            // Saturation de la variance apprise (Max ±50% d'écart pour la sécurité)
            learnedResistanceFactor = learnedResistanceFactor.coerceIn(0.5, 1.5)

            aapsLogger.debug(
                LTag.APS,
                "🎓 [ONLINE_LEARNING] Evaluation of T-30m pred: Pred=${pastPrediction.format(1)}, Act=${currentState.bg.format(1)} | " +
                "Err=${error.format(1)} -> ResFactor updated: ${previousFactor.format(3)} -> ${learnedResistanceFactor.format(3)}"
            )

            // Nettoyage de l'entrée consommée
            predictionHistory.remove(matchedEntry.key)
        }
        
        // Nettoyage des vieilles prédictions orphelines (Fuites mémoire)
        predictionHistory.entries.removeIf { it.key < currentEpochMs - (60 * 60 * 1000) }
    }

    private fun Double.format(digits: Int) = "%.${digits}f".format(this)
}
