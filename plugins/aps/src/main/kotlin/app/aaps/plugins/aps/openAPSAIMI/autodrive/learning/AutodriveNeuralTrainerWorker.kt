package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.LTag

/**
 * 🌙 Autodrive Neural Trainer Worker (Deep Learning Différé)
 * 
 * S'exécute en tâche de fond pendant la nuit (téléphone en charge et inactif) 
 * pour entraîner les poids synaptiques de l'Attention Gate (V3) à partir 
 * du fichier CSV validé.
 */
class AutodriveNeuralTrainerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val backfiller = AutodriveDataBackfiller.instance
            val trainer = AutodriveNeuralTrainer.instance

            if (trainer == null || backfiller == null) {
                // Singletons non initialisés
                return Result.retry()
            }

            // Phase 8 Sécurité Volumétrique : La Gate !
            if (!backfiller.isDatasetReadyForTraining(2880)) {
                // Pas assez de données validées (10 jours), on annule l'apprentissage
                // pour éviter l'overfitting.
                return Result.success() // Success car le comportement est voulu
            }

            // Exécute la Descente de Gradient sur le CPU
            val success = trainer.trainAttentionWeights()
            
            if (success) Result.success() else Result.retry()
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
