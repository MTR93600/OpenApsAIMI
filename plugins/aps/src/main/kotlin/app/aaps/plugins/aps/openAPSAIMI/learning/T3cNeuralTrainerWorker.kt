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
 * T3cNeuralTrainerWorker - Asynchronous background trainer for T3C Brittle Mode.
 * 
 * Goal: Learn the ideal "Aggressiveness Factor" for a given physiological context.
 * 
 * Labeling Logic:
 * If actualDelta > expectedDelta -> aggressiveness was too high.
 * If actualDelta < expectedDelta -> aggressiveness was too low.
 */
class T3cNeuralTrainerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : LoggingWorker(appContext, workerParams, Dispatchers.IO) {

    override suspend fun doWorkAndLog(): Result {
        aapsLogger.debug(LTag.APS, "🧠 T3C Neural Trainer: Starting training session")
        
        val externalDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS + "/AAPS") ?: applicationContext.filesDir
        val csvFile = File(externalDir, "t3c_records.csv")
        val weightsFile = File(externalDir, "t3c_brain_weights.json")

        if (!csvFile.exists()) {
            aapsLogger.debug(LTag.APS, "🧠 T3C CSV not found. Aborting.")
            return Result.success()
        }

        val allLines = csvFile.readLines()
        if (allLines.size < 50) { // Minimum samples required
            aapsLogger.debug(LTag.APS, "🧠 Insufficient T3C data (${allLines.size} rows). Need 50.")
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
        val iCurrentAgg = header.indexOf("currentAggFactor")

        for (line in dataLines) {
            val cols = line.split(",")
            if (cols.size < header.size) continue

            // 1. Prepare Inputs
            val inputFeatures = floatArrayOf(
                cols[iBg].toFloat(),
                cols[iBasal].toFloat(),
                cols[iAccel].toFloat(),
                cols[iDuraMin].toFloat(),
                cols[iDuraAvg].toFloat(),
                cols[iIob].toFloat()
            )

            // 2. Labeling (The "Target")
            // How much should we have adjusted the aggressiveness to hit the target?
            val bgBefore = cols[iBg].toDouble()
            val bgAfter = cols[iEventual].toDouble()
            val targetBg = cols[iTarget].toDouble()
            val currentAgg = cols[iCurrentAgg].toDouble()
            
            val actualDelta = bgBefore - bgAfter
            val neededDelta = bgBefore - targetBg
            
            // Label: Ideal Aggressiveness Factor
            // If neededDelta is 50 and we only got 25 with agg 1.0, ideal was 2.0
            // Clamped and smoothed to avoid noisy training
            val weight = if (abs(neededDelta) < 5.0) 1.0 else (neededDelta / actualDelta.coerceAtLeast(1.0))
            val idealAgg = (currentAgg * weight).coerceIn(0.5, 2.0)

            inputs.add(inputFeatures)
            targets.add(doubleArrayOf(idealAgg))
        }

        if (inputs.isEmpty()) return Result.success()

        // 3. Training
        val net = AimiNeuralNetwork(
            inputSize = inputs.first().size,
            hiddenSize = 8,
            outputSize = 1,
            config = TrainingConfig(
                learningRate = 0.001,
                epochs = 300,
                patience = 20
            )
        )

        // Split 80/20
        val split = (inputs.size * 0.8).toInt()
        net.trainWithValidation(
            inputs.subList(0, split), targets.subList(0, split),
            inputs.subList(split, inputs.size), targets.subList(split, targets.size)
        )

        // 4. Save Weights
        net.saveToFile(weightsFile)
        aapsLogger.debug(LTag.APS, "🧠 T3C Neural Trainer: Training complete. Weights saved to t3c_brain_weights.json")

        return Result.success()
    }
}
