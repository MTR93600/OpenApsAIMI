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
import kotlin.math.exp
import kotlin.math.min

class AutodriveNeuralTrainerWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : LoggingWorker(appContext, workerParams, Dispatchers.IO) {

    override suspend fun doWorkAndLog(): Result {
        aapsLogger.debug(LTag.APS, "🧠 AutodriveNeuralTrainerWorker: Démarrage de l'entraînement du Cerveau Asynchrone")
        
        val externalDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS + "/AAPS") ?: applicationContext.filesDir
        val csvfile = File(externalDir, "oapsaimiML2_records.csv")
        val weightsFile = File(externalDir, "aimi_brain_weights.json")

        if (!csvfile.exists()) {
            aapsLogger.debug(LTag.APS, "🧠 CSV file not found: ${csvfile.absolutePath}")
            return Result.success()
        }

        val allLines = csvfile.readLines()
        if (allLines.isEmpty()) {
            aapsLogger.debug(LTag.APS, "🧠 CSV file is empty.")
            return Result.success()
        }

        val headerLine = allLines.first()
        val headers = headerLine.split(",").map { it.trim() }
        val requiredColumns = listOf(
            "bg", "iob", "cob", "delta", "shortAvgDelta", "longAvgDelta",
            "tdd7DaysPerHour", "tdd2DaysPerHour", "tddPerHour", "tdd24HrsPerHour",
            "predictedSMB", "smbGiven"
        )

        if (!requiredColumns.all { headers.contains(it) }) {
            aapsLogger.debug(LTag.APS, "🧠 CSV file is missing required columns. Training aborted.")
            return Result.success()
        }

        val colIndices = requiredColumns.map { headers.indexOf(it) }
        val targetColIndex = headers.indexOf("smbGiven")
        val inputs = mutableListOf<FloatArray>()
        val targets = mutableListOf<DoubleArray>()

        for (line in allLines.drop(1)) {
            val cols = line.split(",").map { it.trim() }
            val rawInput = colIndices.mapNotNull { idx -> cols.getOrNull(idx)?.toFloatOrNull() }.toFloatArray()
            
            // Pour l'entraînement offline, on approxime le trendIndicator passé car il n'est pas dans le CSV.
            val bg = headers.indexOf("bg").let { if (it >= 0) cols.getOrNull(it)?.toDoubleOrNull() ?: 100.0 else 100.0 }
            val delta = headers.indexOf("delta").let { if (it >= 0) cols.getOrNull(it)?.toFloatOrNull() ?: 0f else 0f }
            val shortAvgDelta = headers.indexOf("shortAvgDelta").let { if (it >= 0) cols.getOrNull(it)?.toFloatOrNull() ?: 0f else 0f }
            val longAvgDelta = headers.indexOf("longAvgDelta").let { if (it >= 0) cols.getOrNull(it)?.toFloatOrNull() ?: 0f else 0f }
            val iob = headers.indexOf("iob").let { if (it >= 0) cols.getOrNull(it)?.toDoubleOrNull() ?: 0.0 else 0.0 }
            // Basic trend indicator calculation simulation
            val combinedDelta = (delta + shortAvgDelta + longAvgDelta) / 3f
            val stressScore = if (bg > 150) 40.0 else 0.0
            val metabolicLoad = iob * 5.0
            val baseTrend = (combinedDelta * 5.0f) + (stressScore * 0.1).toFloat() - (metabolicLoad * 0.5).toFloat()
            val sig = (1f / (1f + exp(-baseTrend)))
            val trendIndicator = 0.5f + sig * 0.7f

            val enhancedInput = rawInput.copyOf(rawInput.size + 1)
            enhancedInput[rawInput.size] = trendIndicator
            
            val targetValue = cols.getOrNull(targetColIndex)?.toDoubleOrNull()
            if (targetValue != null) {
                inputs.add(enhancedInput)
                targets.add(doubleArrayOf(targetValue))
            }
        }

        if (inputs.isEmpty() || targets.isEmpty()) {
            aapsLogger.debug(LTag.APS, "🧠 Insufficient data for training.")
            return Result.success()
        }

        val maxK = 10
        val adjustedK = min(maxK, inputs.size)
        val foldSize = maxOf(1, inputs.size / adjustedK)
        var bestNetwork: AimiNeuralNetwork? = null
        var bestFoldValLoss = Double.MAX_VALUE

        // K-Fold Training
        for (k in 0 until adjustedK) {
            val validationInputs = inputs.subList(k * foldSize, min((k + 1) * foldSize, inputs.size))
            val validationTargets = targets.subList(k * foldSize, min((k + 1) * foldSize, targets.size))
            val trainingInputs = inputs.minus(validationInputs)
            val trainingTargets = targets.minus(validationTargets)
            if (validationInputs.isEmpty()) continue

            val tempNetwork = AimiNeuralNetwork(
                inputSize = inputs.first().size,
                hiddenSize = 5,
                outputSize = 1,
                config = TrainingConfig(
                    learningRate = 0.001,
                    epochs = 200
                ),
                regularizationLambda = 0.01
            )

            tempNetwork.trainWithValidation(trainingInputs, trainingTargets, validationInputs, validationTargets)
            val foldValLoss = tempNetwork.validate(validationInputs, validationTargets)

            if (foldValLoss < bestFoldValLoss) {
                bestFoldValLoss = foldValLoss
                bestNetwork = tempNetwork
            }
        }

        val adjustedLearningRate = if (bestFoldValLoss < 0.01) 0.0005 else 0.001
        val epochs = if (bestFoldValLoss < 0.01) 100 else 200

        if (bestNetwork != null) {
            val finalNetwork = AimiNeuralNetwork(
                inputSize = inputs.first().size,
                hiddenSize = 5,
                outputSize = 1,
                config = TrainingConfig(
                    learningRate = adjustedLearningRate,
                    beta1 = 0.9,
                    beta2 = 0.999,
                    epsilon = 1e-8,
                    patience = 10,
                    batchSize = 32,
                    weightDecay = 0.01,
                    epochs = epochs,
                    useBatchNorm = false,
                    useDropout = true,
                    dropoutRate = 0.3,
                    leakyReluAlpha = 0.01
                ),
                regularizationLambda = 0.01
            )
            finalNetwork.copyWeightsFrom(bestNetwork)
            finalNetwork.trainWithValidation(inputs, targets, inputs, targets)
            
            // Save Weights async to the JSON file
            finalNetwork.saveToFile(weightsFile)
            aapsLogger.debug(LTag.APS, "🧠 Neural Trainer Worker: Entraînement terminé avec succès. Poids sauvegardés -> aimi_brain_weights.json (ValLoss=${bestFoldValLoss})")
        }

        return Result.success()
    }
}
