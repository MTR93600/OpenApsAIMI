package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.LTag

/**
 * 🌙 Autodrive Backfill Worker (Apprentissage Différé)
 * 
 * S'exécute en tâche de fond pendant la nuit (téléphone en charge et inactif) 
 * pour analyser les conséquences des décisions de la journée et remplir les 
 * colonnes Future_BG du CSV d'entraînement.
 */
class AutodriveBackfillWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val backfiller = AutodriveDataBackfiller.instance

            if (backfiller == null) {
                // Le singleton n'est pas encore instancié par l'App, on réessaie plus tard
                return Result.retry()
            }

            // Exécute le calcul lourd sur le fichier CSV
            val linesModified = backfiller.processPendingLines()
            
            Result.success()
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
