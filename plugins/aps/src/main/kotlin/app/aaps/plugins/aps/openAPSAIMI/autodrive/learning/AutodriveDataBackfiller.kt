package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🧹 Autodrive Data Backfiller
 * 
 * Sa mission : Rouvrir le fichier `autodrive_dataset.csv` et compléter les colonnes laissées vides
 * lors de la collecte initiale (Future_BG_45m, Hypo_Occurred, Hyper_Occurred).
 * Il regarde dans le "futur" (lignes suivantes du fichier) pour évaluer la décision passée.
 */
@Singleton
class AutodriveDataBackfiller @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper
) {
    companion object {
        @Volatile
        var instance: AutodriveDataBackfiller? = null
            internal set
    }

    init {
        instance = this
        scheduleNightlyWorker()
    }

    /**
     * Phase 9 : Night-Time Execution (Setup pour le Backfiller et futur Trainer)
     * Protège la batterie : le lourd calcul CSV / ML ne tourne QUE la nuit.
     */
    private fun scheduleNightlyWorker() {
        // Exige Téléphone branché + Écran éteint/Veille profonde
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresCharging(true)
            .build()
            
        // Boucle toutes les 12 heures, mais Android attendra la nuit physique via Constraintes
        val backfillRequest = PeriodicWorkRequestBuilder<AutodriveBackfillWorker>(
            12, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
            
        try {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "AIMI_AUTODRIVE_BACKFILLER",
                ExistingPeriodicWorkPolicy.KEEP,
                backfillRequest
            )
            aapsLogger.info(LTag.APS, "Autodrive V3 : Worker ML nocturne planifié avec protection Batterie.")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Erreur planification Autodrive Backfiller: ${e.message}")
        }
    }

    private val csvFileName = "autodrive_dataset.csv"
    private val tmpCsvFileName = "autodrive_dataset_tmp.csv"

    // Index des colonnes (basé sur AutodriveDataLake.kt)
    private val IDX_TIMESTAMP = 0
    private val IDX_BG = 2
    private val IDX_FUTURE_BG = 15
    private val IDX_HYPO = 16
    private val IDX_HYPER = 17

    private val MIN_MILLIS_FOR_FUTURE = 45 * 60 * 1000L // 45 minutes
    private val MAX_MILLIS_FOR_HYPO = 60 * 60 * 1000L   // 60 minutes de fenêtre 

    /**
     * Parse le fichier CSV, trouve les lignes incomplètes (récentes il y a > 45min),
     * les complète en lisant les glycémies futures, et réécrit le fichier proprement.
     * 
     * @return Le nombre de lignes back-fillées avec succès.
     */
    fun processPendingLines(): Int {
        val originalFile = storageHelper.getAimiFile(csvFileName)
        if (!originalFile.exists()) return 0

        val lines = try {
            originalFile.readLines().toMutableList()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Backfiller Error reading CSV: ${e.message}")
            return 0
        }

        if (lines.size <= 1) return 0 // Seulement le header

        val header = lines[0]
        var modifiedCount = 0

        // Parse lines for quick access
        val parsedLines = lines.drop(1).mapNotNull { line ->
            val cols = line.split(",")
            if (cols.size > IDX_HYPER) {
                val timestamp = cols[IDX_TIMESTAMP].toLongOrNull() ?: 0L
                val bg = cols[IDX_BG].toDoubleOrNull() ?: 0.0
                ParsedRow(cols.toMutableList(), timestamp, bg)
            } else null
        }

        // Parcourt les lignes du début à la fin
        for (i in parsedLines.indices) {
            val currentRow = parsedLines[i]
            val timestampNow = currentRow.timestamp
            val futureBgStr = currentRow.cols[IDX_FUTURE_BG]

            // Si la ligne n'a pas encore son 'Reward' validé
            if (futureBgStr.isBlank() && timestampNow > 0) {
                
                // Recherche dans le futur (lignes i+1 à EOF)
                val targetMillis = timestampNow + MIN_MILLIS_FOR_FUTURE
                val maxWindowMillis = timestampNow + MAX_MILLIS_FOR_HYPO
                
                var futureBgVal: Double? = null
                var hypoOccurred = false
                var hyperOccurred = false

                for (j in i + 1 until parsedLines.size) {
                    val futureRow = parsedLines[j]
                    val futureTs = futureRow.timestamp
                    val futureBg = futureRow.bg

                    // Check d'hypoglycémie dans la fenêtre de 60 minutes suivant la décision
                    if (futureTs in (timestampNow + 1)..maxWindowMillis) {
                        if (futureBg > 0 && futureBg < 70.0) {
                            hypoOccurred = true
                        }
                    }

                    // On cherche LA première glycémie qui est à +45min ou plus
                    if (futureBgVal == null && futureTs >= targetMillis) {
                        futureBgVal = futureBg
                        if (futureBg >= 180.0) {
                            hyperOccurred = true
                        }
                    }

                    // Une fois qu'on a dépassé la fenêtre totale (Future + Fenêtre de crash), on arrête la boucle interne
                    if (futureTs > maxWindowMillis) {
                        break
                    }
                }

                // Si on a bien trouvé une valeur dans le futur (au moins 45min plus tard)
                if (futureBgVal != null) {
                    currentRow.cols[IDX_FUTURE_BG] = futureBgVal.toString()
                    currentRow.cols[IDX_HYPO] = if (hypoOccurred) "1" else "0"
                    currentRow.cols[IDX_HYPER] = if (hyperOccurred) "1" else "0"
                    currentRow.isModified = true
                    modifiedCount++
                }
            }
        }

        // Si on a complété des données, on réécrit le fichier Atomiquement
        if (modifiedCount > 0) {
            val tmpFile = storageHelper.getAimiFile(tmpCsvFileName)
            try {
                tmpFile.bufferedWriter().use { writer ->
                    writer.write(header)
                    writer.newLine()
                    parsedLines.forEach { row ->
                        writer.write(row.cols.joinToString(","))
                        writer.newLine()
                    }
                }

                // Swap atomique
                if (tmpFile.renameTo(originalFile)) {
                    aapsLogger.info(LTag.APS, "Backfiller a complété $modifiedCount lignes d'entraînement.")
                } else {
                    // Fallback rename manuel sur certains Androids
                    tmpFile.copyTo(originalFile, overwrite = true)
                    tmpFile.delete()
                }

            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "Backfiller Error writing CSV: ${e.message}")
            }
        }

        return modifiedCount
    }

    /**
     * Phase 8 : Data Quality Gate (Sécurité Volumétrique)
     * Vérifie si le fichier CSV contient assez de lignes "Back-fillées" (avec Future_BG rempli)
     * pour autoriser un entraînement IA sans risque d'Overfitting.
     */
    fun isDatasetReadyForTraining(minimumValidLines: Int = 2880): Boolean {
        val file = storageHelper.getAimiFile(csvFileName)
        if (!file.exists()) return false

        return try {
            var validCount = 0
            file.useLines { lines ->
                // On passe le header
                lines.drop(1).forEach { line ->
                    val cols = line.split(",")
                    if (cols.size > IDX_FUTURE_BG && cols[IDX_FUTURE_BG].isNotBlank()) {
                        validCount++
                    }
                    // Early exit si on a atteint le quota
                    if (validCount >= minimumValidLines) return true
                }
            }
            false
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Gate Error: ${e.message}")
            false
        }
    }

    private data class ParsedRow(
        val cols: MutableList<String>,
        val timestamp: Long,
        val bg: Double,
        var isModified: Boolean = false
    )
}
