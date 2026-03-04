package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * 🚪 Mechanism Attention Gate (Cerveau IA V3)
 * 
 * Sa mission : Lire les Poids (Weights) calculés hors-ligne par le réseau de neurones (NeuralTrainer)
 * et moduler drastiquement la sensibilité perçue par le système en fonction du risque 
 * calculé pour la situation physiologique actuelle.
 */
@Singleton
class MechanismAttentionGate @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper
) {
    private val weightsFileName = "autodrive_attention_weights.json"
    
    // Cache en mémoire pour ne pas tuer les I/O à chaque tique de 5 minutes
    private var weightsCache: AttentionWeights? = null
    private var lastLoadTime: Long = 0

    init {
        loadWeights()
    }

    /**
     * Module l'état perçu par l'Autodrive avant même que le MPC ou l'UKF ne le traitent.
     * C'est ici que l'Intelligence Artificielle Pure modifie la trajectoire du Contrôleur Automatique.
     */
    fun applyAttention(state: AutoDriveState): AutoDriveState {
        val weights = getWeights()
        
        // Si la V3 n'a pas encore eu le temps de s'entraîner (Data Gate fermée), on reste en V2 = Neutre
        if (weights == null || state.physiologicalStressMask.isEmpty()) {
            return state
        }

        // 1. Calcul de l'Attention (Pondération des signaux)
        val mask = state.physiologicalStressMask
        var z = weights.bias
        
        if (mask.size > 0) z += weights.wHr * mask[0]
        if (mask.size > 1) z += weights.wInflammation * mask[1]
        if (mask.size > 2) z += weights.wHormonal * mask[2]
        
        // 2. Fonction d'activation (Sigmoid) -> Probabilité de Crash (Hypo)
        val hypoRiskScore = sigmoid(z)

        // 3. Modulation du SI
        // Si l'IA prédit un crash (Score > 0.5), elle AUGMENTE techniquement le SI
        // pour dire au MPC "Le patient va aspirer cette insuline, calme-toi".
        val attentionMultiplier = if (hypoRiskScore > 0.5) {
            // Amplification défensive (Ex: risque très fort = x1.5 sensibilité)
            1.0 + (hypoRiskScore - 0.5)
        } else {
            // Le patient est safe, mais on peut réduire la prudence (jusqu'à x0.8 SI)
            // pour taper un peu plus fort si on sait qu'il résiste
            1.0 - (0.5 - hypoRiskScore) * 0.4 
        }

        val modulatedSI = state.estimatedSI * attentionMultiplier

        return state.copy(estimatedSI = modulatedSI)
    }

    private fun sigmoid(z: Double): Double {
        val safeZ = z.coerceIn(-20.0, 20.0)
        return 1.0 / (1.0 + exp(-safeZ))
    }

    private fun getWeights(): AttentionWeights? {
        val now = System.currentTimeMillis()
        // On recharge les poids du JSON au maximum une fois par heure
        if (weightsCache == null || (now - lastLoadTime > 60 * 60 * 1000L)) {
            loadWeights()
        }
        return weightsCache
    }

    private fun loadWeights() {
        try {
            val file = storageHelper.getAimiFile(weightsFileName)
            if (!file.exists()) return

            val content = file.readText()
            if (content.isBlank()) return

            val json = JSONObject(content)
            weightsCache = AttentionWeights(
                bias = json.optDouble("bias", 0.0),
                wHr = json.optDouble("weight_hr", 0.0),
                wInflammation = json.optDouble("weight_inflammation", 0.0),
                wHormonal = json.optDouble("weight_hormonal", 0.0)
            )
            lastLoadTime = System.currentTimeMillis()
            aapsLogger.info(LTag.APS, "IA Attention Gate: Nouveaux Poids d'Apprentissage chargés en mémoire.")
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Erreur lecture des Poids IA de l'Attention Gate: ${e.message}")
        }
    }

    private data class AttentionWeights(
        val bias: Double,
        val wHr: Double,
        val wInflammation: Double,
        val wHormonal: Double
    )
}
