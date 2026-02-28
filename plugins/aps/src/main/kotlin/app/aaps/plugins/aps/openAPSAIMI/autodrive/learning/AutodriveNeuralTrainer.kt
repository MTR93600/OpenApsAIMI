package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * 🧠 Autodrive Neural Trainer (Apprentissage On-Device)
 * 
 * Ce composant exécute un algorithme de Machine Learning primitif (Descente de Gradient / Régression Logistique)
 * directement sur le téléphone. Il analyse l'historique CSV (Big Data) pour comprendre mathématiquement 
 * l'impact réel des signaux physiologiques (HR, Inflammation) sur les crashs glycémiques (Hypo).
 */
@Singleton
class AutodriveNeuralTrainer @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper
) {
    companion object {
        @Volatile
        var instance: AutodriveNeuralTrainer? = null
            internal set
    }

    init {
        instance = this
        scheduleNightlyTrainer()
    }

    /**
     * Phase 9 : Night-Time Execution
     * Le modèle neuronal prend plus de temps CPU. Il ne tourne QUE la nuit.
     */
    private fun scheduleNightlyTrainer() {
        // Exige Téléphone branché + Écran éteint/Veille profonde
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresCharging(true)
            .build()
            
        // Entraînement 1 fois par 24 heures maximum
        val trainerRequest = PeriodicWorkRequestBuilder<AutodriveNeuralTrainerWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
            
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "AIMI_AUTODRIVE_NEURAL_TRAINER",
                ExistingPeriodicWorkPolicy.KEEP,
                trainerRequest
            )
            aapsLogger.info(LTag.APS, "Autodrive V3 : Worker d'Apprentissage Neuronal planifié avec succès.")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Erreur planification Autodrive Neural Trainer: ${e.message}")
        }
    }

    private val csvFileName = "autodrive_dataset.csv"
    private val weightsFileName = "autodrive_attention_weights.json"

    // Index des colonnes (basé sur AutodriveDataLake.kt)
    private val IDX_PHYSIO_MASK = 9
    private val IDX_FUTURE_BG = 15
    private val IDX_HYPO = 16
    private val IDX_HYPER = 17

    // Hyperparamètres ML
    private val learningRate = 0.01
    private val epochs = 100 // On-Device : petit nombre d'epochs pour sauver la batterie

    /**
     * Lance l'entraînement du réseau de neurones.
     * Lit le Dataset, extrait les Features (Mask) et les Labels (Hypo/Hyper),
     * met à jour les poids par Gradient Descent, et sauvegarde le résultat.
     */
    fun trainAttentionWeights(): Boolean {
        val file = storageHelper.getAimiFile(csvFileName)
        if (!file.exists()) return false

        val dataset = mutableListOf<TrainingExample>()

        try {
            file.useLines { lines ->
                // On drop le header
                lines.drop(1).forEach { line ->
                    val cols = line.split(",")
                    // On ne s'entraîne que sur les données certifiées (Vraie BG 45m connue)
                    if (cols.size > IDX_HYPER && cols[IDX_FUTURE_BG].isNotBlank()) {
                        val physioStr = cols[IDX_PHYSIO_MASK]
                        val hypoVal = cols[IDX_HYPO].toDoubleOrNull() ?: 0.0
                        
                        // Parse du vecteur physiologique (Ex: "0.8|0.0|0.0")
                        val features = if (physioStr != "0" && physioStr.isNotBlank()) {
                            physioStr.split("|").map { it.toDoubleOrNull() ?: 0.0 }.toDoubleArray()
                        } else {
                            DoubleArray(3) { 0.0 } // [HR, Inflammation, WCycle] par défaut
                        }

                        // Label = 1.0 si crash Hypo, sinon 0.0
                        dataset.add(TrainingExample(features, hypoVal))
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "NeuralTrainer Error reading dataset: ${e.message}")
            return false
        }

        if (dataset.isEmpty()) {
            aapsLogger.warn(LTag.APS, "NeuralTrainer: Dataset validé vide, annulation de l'entraînement.")
            return false
        }

        // L'architecture de notre Attention Gate (ex: 3 features)
        val featureCount = dataset.first().features.size
        var weights = DoubleArray(featureCount) { 0.0 } // Init à 0
        var bias = 0.0

        aapsLogger.info(LTag.APS, "NeuralTrainer: Début Descente de Gradient sur ${dataset.size} lignes...")

        // Algorithme de Gradient Descent (Régression Logistique)
        for (epoch in 0 until epochs) {
            var totalLoss = 0.0
            
            val dw = DoubleArray(featureCount) { 0.0 }
            var db = 0.0

            for (example in dataset) {
                // Forward pass (Prédiction actuelle) : Sigmoid(W.X + b)
                var z = bias
                for (i in 0 until featureCount) {
                    // Sécurité anti-crash si la taille varie
                    if (i < example.features.size) {
                        z += weights[i] * example.features[i]
                    }
                }
                val prediction = sigmoid(z)
                
                // Calcul de l'erreur (Label - Prédiction)
                val error = prediction - example.targetHypo // Si Hypo = 1, et pred = 0, erreur = -1

                // Accumulation des gradients
                for (i in 0 until featureCount) {
                    if (i < example.features.size) {
                        dw[i] += error * example.features[i]
                    }
                }
                db += error
            }

            // Update des poids (Moyenne du gradient * Learning Rate)
            val m = dataset.size.toDouble()
            for (i in 0 until featureCount) {
                weights[i] -= learningRate * (dw[i] / m)
            }
            bias -= learningRate * (db / m)
        }

        aapsLogger.info(LTag.APS, "NeuralTrainer: Entraînement terminé ! Poids Hypo = ${weights.joinToString(", ")}")

        // Sauvegarde des poids appris dans le système de stockage robuste
        return saveLearnedWeights(weights, bias)
    }

    private fun sigmoid(z: Double): Double {
        // Anti-overflow
        val safeZ = z.coerceIn(-20.0, 20.0)
        return 1.0 / (1.0 + exp(-safeZ))
    }

    private fun saveLearnedWeights(weights: DoubleArray, bias: Double): Boolean {
        try {
            val json = JSONObject()
            json.put("bias", bias)
            
            // Mapping sémantique (Hypothèse standard sur notre UI)
            if (weights.isNotEmpty()) json.put("weight_hr", weights[0])
            if (weights.size > 1) json.put("weight_inflammation", weights[1])
            if (weights.size > 2) json.put("weight_hormonal", weights[2])
            
            json.put("timestamp", System.currentTimeMillis())

            val file = storageHelper.getAimiFile(weightsFileName)
            return storageHelper.saveFileSafe(file, json.toString(4))
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "NeuralTrainer Error saving weights: ${e.message}")
            return false
        }
    }

    private data class TrainingExample(
        val features: DoubleArray,
        val targetHypo: Double // 1.0 = Danger, 0.0 = Safe
    )
}
