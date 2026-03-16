package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import android.os.Environment
import androidx.work.WorkerParameters
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.objects.workflow.LoggingWorker
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * BasalAdaptiveTrainerWorker - Asynchronous background trainer for Universal Adaptive Basal.
 * 
 * Goal: Learn the subtle "Basal Scaling Factor" for all users to optimize stability.
 */
class BasalAdaptiveTrainerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : LoggingWorker(appContext, workerParams, Dispatchers.IO) {

    override suspend fun doWorkAndLog(): Result {
        aapsLogger.debug(LTag.APS, "🧠 Basal Adaptive Trainer: Starting training session")
        
        val externalDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS + "/AAPS") ?: applicationContext.filesDir
        val csvFile = File(externalDir, "basal_adaptive_records.csv")
        val weightsFile = File(externalDir, "basal_adaptive_weights.json")

        if (!csvFile.exists()) {
            aapsLogger.debug(LTag.APS, "🧠 Basal Adaptive CSV not found. Aborting.")
            return Result.success()
        }

        val allLines = csvFile.readLines()
        if (allLines.size < 100) { // Standard basal needs more data for subtle patterns
            aapsLogger.debug(LTag.APS, "🧠 Insufficient Basal data (${allLines.size} rows). Need 100.")
            return Result.success()
        }

        val header = allLines.first().split(",")
        val dataLines = allLines.drop(1)
        
        val inputs = mutableListOf<FloatArray>()
        val targets = mutableListOf<DoubleArray>()

        // Column indices
        val iBg = header.indexOf("bg")
        val iEventual = header.indexOf("eventualBg")
        val iBasal = header.indexOf("basal")
        val iTarget = header.indexOf("target")
        val iAccel = header.indexOf("accel")
        val iDuraMin = header.indexOf("duraMin")
        val iDuraAvg = header.indexOf("duraAvg")
        val iIob = header.indexOf("iob")
        val iBasalScale = header.indexOf("basalScale")

        for (line in dataLines) {
            val cols = line.split(",")
            if (cols.size < header.size) continue

            // 1. Prepare Inputs (6 features)
            val inputFeatures = floatArrayOf(
                cols[iBg].toFloat(),
                cols[iBasal].toFloat(),
                cols[iAccel].toFloat(),
                cols[iDuraMin].toFloat(),
                cols[iDuraAvg].toFloat(),
                cols[iIob].toFloat()
            )

            // 2. Labeling (The "Target")
            // Ideal Basal Scale calculation (more conservative than T3C)
            val bgBefore = cols[iBg].toDouble()
            val bgAfter = cols[iEventual].toDouble()
            val targetBg = cols[iTarget].toDouble()
            val currentScale = cols[iBasalScale].toDouble()
            
            val actualDelta = bgBefore - bgAfter
            val neededDelta = bgBefore - targetBg
            
            // Label: Ideal Basal Scaling
            // Use a dampening factor (0.5) for universal scaling to stay safe
            val rawWeight = if (abs(neededDelta) < 3.0) 1.0 else (neededDelta / actualDelta.coerceAtLeast(1.0))
            val adjustedWeight = 1.0 + (rawWeight - 1.0) * 0.5 
            val idealScale = (currentScale * adjustedWeight).coerceIn(0.7, 1.5)

            inputs.add(inputFeatures)
            targets.add(doubleArrayOf(idealScale))
        }

        if (inputs.isEmpty()) return Result.success()

        // 3. Training
        val net = AimiNeuralNetwork(
            inputSize = inputs.first().size,
            hiddenSize = 8,
            outputSize = 1,
            config = TrainingConfig(
                learningRate = 0.0005, // Slower learning for stability
                epochs = 200,
                patience = 20
            )
        )

        val split = (inputs.size * 0.8).toInt()
        net.trainWithValidation(
            inputs.subList(0, split), targets.subList(0, split),
            inputs.subList(split, inputs.size), targets.subList(split, targets.size)
        )

        // 4. Save Weights
        net.saveToFile(weightsFile)
        aapsLogger.debug(LTag.APS, "🧠 Basal Adaptive Trainer: Training complete. Weights saved to basal_adaptive_weights.json")

        return Result.success()
    }
}
