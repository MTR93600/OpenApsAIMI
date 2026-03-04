package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import android.os.Environment
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper // 🛡️ Unification du Storage
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🗂️ Autodrive Data Lake (Data Logger CSV)
 * 
 * Enregistreur silencieux et brut de l'état système et algorithmique (Features / Actions).
 * Ce fichier CSV permet d'historiser le comportement métabolique afin d'entraîner off-line
 * le système d'Attention Gate (V3) grâce à un réseau de neurones.
 */
@Singleton
class AutodriveDataLake @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper // Injection du centralisateur
) {

    private val logFile by lazy {
        storageHelper.getAimiFile("autodrive_dataset.csv").apply {
            if (!exists()) {
                // Création du Header structuré selon le Blueprint
                writeText(
                    "Timestamp_Epoch,Date," +
                    "BG_Current,BG_Velocity,IOB_Net,COB,Estimated_SI,Estimated_Ra,Patient_Weight," +
                    "Physio_Mask," +
                    "MPC_Raw_SMB,MPC_Raw_TBR," +
                    "CBF_Safe_SMB,CBF_Safe_TBR,CBF_Intervention," +
                    "Future_BG_45m,Hypo_Occurred,Hyper_Occurred\n"
                )
            }
        }
    }

    /**
     * Enregistre un Snapshot à l'instant T de l'état du patient et de la décision.
     * Les valeurs Future_BG_45m, Hypo et Hyper sont inscrites à vide/false,
     * elles devront être "back-propagées" plus tard par un outil externe.
     */
    fun recordSnapshot(
        state: AutoDriveState,
        rawCommand: AutoDriveCommand,
        safeCommand: AutoDriveCommand,
        currentTimestamp: Long = System.currentTimeMillis()
    ) {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val dateStr = sdf.format(Date(currentTimestamp))

            // Calcul de l'Intervention CBF (Est-ce que le bouclier a frappé ?)
            val cbfIntervention = if (
                rawCommand.scheduledMicroBolus > safeCommand.scheduledMicroBolus || 
                rawCommand.temporaryBasalRate > safeCommand.temporaryBasalRate
            ) 1 else 0

            val maskStr = if (state.physiologicalStressMask.isNotEmpty()) {
                state.physiologicalStressMask.joinToString("|")
            } else {
                "0"
            }

            val line = listOf(
                currentTimestamp.toString(),
                dateStr,
                "%.1f".format(Locale.US, state.bg),
                "%.3f".format(Locale.US, state.bgVelocity),
                "%.3f".format(Locale.US, state.iob),
                "%.1f".format(Locale.US, state.cob),
                "%.4f".format(Locale.US, state.estimatedSI),
                "%.3f".format(Locale.US, state.estimatedRa),
                "%.1f".format(Locale.US, state.patientWeightKg),
                maskStr,
                "%.3f".format(Locale.US, rawCommand.scheduledMicroBolus),
                "%.3f".format(Locale.US, rawCommand.temporaryBasalRate),
                "%.3f".format(Locale.US, safeCommand.scheduledMicroBolus),
                "%.3f".format(Locale.US, safeCommand.temporaryBasalRate),
                cbfIntervention.toString(),
                "", // Future_BG_45m (à remplir off-line)
                "0", // Hypo_Occurred (à remplir off-line)
                "0"  // Hyper_Occurred (à remplir off-line)
            ).joinToString(",") + "\n"

            FileWriter(logFile, true).use { it.append(line) }

        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Autodrive Data Lake Error: " + e.message)
            e.printStackTrace()
        }
    }
}
