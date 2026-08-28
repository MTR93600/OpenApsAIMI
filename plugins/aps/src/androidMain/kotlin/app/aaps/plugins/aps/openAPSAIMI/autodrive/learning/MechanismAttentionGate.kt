package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.data.json.OrgJsonCompat.optDoubleCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import kotlin.math.exp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * 🚪 Mechanism Attention Gate (Cerveau IA V3)
 * 
 * Sa mission : Lire les Poids (Weights) calculés hors-ligne par le réseau de neurones (NeuralTrainer)
 * et moduler drastiquement la sensibilité perçue par le système en fonction du risque 
 * calculé pour la situation physiologique actuelle.
 */
@SingleIn(AppScope::class)
class MechanismAttentionGate @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper
) {
    private val weightsFileName = AutodriveNeuralTrainer.WEIGHTS_FILE_NAME
    
    // Cache en mémoire pour ne pas tuer les I/O à chaque tique de 5 minutes
    private var weightsCache: AttentionWeights? = null
    private var lastLoadTime: Long = 0
    // Pour l'UI
    var lastAttentionMultiplier: Double = 1.0
        private set

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
            lastAttentionMultiplier = 1.0
            return state
        }

        // 1. Calcul de l'Attention (Pondération des signaux)
        val mask = state.physiologicalStressMask
        var z = weights.bias
        
        if (mask.size > 0) z += weights.wHr * mask[0]
        if (mask.size > 1) z += weights.wInflammation * mask[1]
        if (mask.size > 2) z += weights.wHormonal * mask[2]
        // Inference always happens on an engaged tick, so this feature is 1 here. It exists so the
        // model can separate "risk given this physiology" from "risk given the gate opened", instead
        // of the training set carrying that difference silently.
        z += weights.wEngaged * 1.0
        
        // 2. Fonction d'activation (Sigmoid) -> Probabilité de Crash (Hypo)
        val hypoRiskScore = sigmoid(z)

        // 3. Modulation du SI
        // Si l'IA prédit un crash (Score > 0.5), elle AUGMENTE techniquement le SI
        // pour dire au MPC "Le patient va aspirer cette insuline, calme-toi".
        val attentionMultiplier = if (hypoRiskScore > 0.5) {
            // Amplification défensive (Ex: risque très fort = x1.5 sensibilité)
            1.0 + (hypoRiskScore - 0.5)
        } else {
            // ⚠️ Defensive-only until the classifier is validated.
            //
            // This arm used to return `1.0 - (0.5 - hypoRiskScore) * 0.4`, down to ×0.8 — "the patient
            // is safe, we can lower caution to hit a bit harder". Lowering `estimatedSI` does not only
            // push the MPC: it shrinks `lgh = -siMetabolic * bg` in `ControlBarrierShield`, so the
            // safety barrier permits a **larger** dose.
            //
            // The classifier is trained from scratch each run on a ~3 % positive class with no feature
            // normalisation, so its fitted probability sits below 0.5 almost always — meaning that arm
            // would fire on essentially every engaged tick, driven by the base rate of hypoglycaemia
            // rather than by any measured resistance. It never actually ran, because the trainer was
            // never instantiated and the weights file never existed; this keeps it that way rather
            // than switching on an untested permissive path.
            //
            // Restore it only with per-feature normalisation, a class-balance-aware objective, and the
            // barrier reading the un-attenuated SI — the same rule already applied to `estimatedRa`.
            1.0
        }

        val modulatedSI = state.estimatedSI * attentionMultiplier
        lastAttentionMultiplier = attentionMultiplier

        return state.copy(estimatedSI = modulatedSI)
    }

    private fun sigmoid(z: Double): Double {
        val safeZ = z.coerceIn(-20.0, 20.0)
        return 1.0 / (1.0 + exp(-safeZ))
    }

    private fun getWeights(): AttentionWeights? {
        val now = aimiWallClockMs()
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

            val json = Json.parseToJsonElement(content).jsonObject
            weightsCache = AttentionWeights(
                bias = json.optDoubleCompat("bias", 0.0),
                wHr = json.optDoubleCompat("weight_hr", 0.0),
                wInflammation = json.optDoubleCompat("weight_inflammation", 0.0),
                wHormonal = json.optDoubleCompat("weight_hormonal", 0.0),
                // Absent from weight files written before the dataset carried the engagement column.
                // 0.0 keeps those files behaving exactly as they did.
                wEngaged = json.optDoubleCompat("weight_engaged", 0.0)
            )
            lastLoadTime = aimiWallClockMs()
            aapsLogger.info(LTag.AIMI, "IA Attention Gate: Nouveaux Poids d'Apprentissage chargés en mémoire.")
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "Erreur lecture des Poids IA de l'Attention Gate: ${e.message}")
        }
    }

    private data class AttentionWeights(
        val bias: Double,
        val wHr: Double,
        val wInflammation: Double,
        val wHormonal: Double,
        /** Weight of the "Autodrive engaged" feature. 0.0 for weight files written before it existed. */
        val wEngaged: Double = 0.0,
    )
}
