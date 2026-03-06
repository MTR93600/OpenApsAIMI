package app.aaps.plugins.aps.openAPSAIMI

import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

class AimiNeuralNetwork(
    private val inputSize: Int,
    private val hiddenSize: Int,
    private val outputSize: Int,
    private val config: TrainingConfig = TrainingConfig(),
    private val regularizationLambda: Double = 0.01 // L2 reg (optionnel)
) {

    // Poids et biais
    private var weightsInputHidden = Array(inputSize) {
        DoubleArray(hiddenSize) { Random.nextDouble(-sqrt(2.0 / inputSize), sqrt(2.0 / inputSize)) }
    }
    private var biasHidden = DoubleArray(hiddenSize) { 0.01 }

    private var weightsHiddenOutput = Array(hiddenSize) {
        DoubleArray(outputSize) { Random.nextDouble(-sqrt(2.0 / hiddenSize), sqrt(2.0 / hiddenSize)) }
    }
    private var biasOutput = DoubleArray(outputSize) { 0.01 }

    // Historique pour debug ou tracking
    private val trainingLossHistory = mutableListOf<Double>()
    private var bestValLoss = Double.MAX_VALUE

    // Fonctions d'activation et normalisation
    private fun leakyRelu(x: Double, alpha: Double = config.leakyReluAlpha): Double {
        return if (x >= 0) x else alpha * x
    }

    private fun batchNormalization(values: DoubleArray): DoubleArray {
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2.0) }.average()
        return values.map { (it - mean) / sqrt(variance + 1e-8) }.toDoubleArray()
    }

    private fun applyDropout(values: DoubleArray, dropoutRate: Double): DoubleArray {
        return values.map { if (Random.nextDouble() < dropoutRate) 0.0 else it }.toDoubleArray()
    }

    fun predict(input: FloatArray): DoubleArray {
         return forwardPass(input, inferenceMode = true).second
    }
    private fun forwardPass(
        input: FloatArray,
        inferenceMode: Boolean = false
    ): Pair<DoubleArray, DoubleArray> {
        // Calcul de la couche cachée : somme pondérée + biais
        val hidden = DoubleArray(hiddenSize)
        for (h in 0 until hiddenSize) {
            var sum = 0.0
            for (i in input.indices) {
                sum += input[i] * weightsInputHidden[i][h]
            }
            hidden[h] = sum + biasHidden[h]
        }

        // Activation LeakyReLU in place
        for (h in 0 until hiddenSize) {
            val v = hidden[h]
            hidden[h] = if (v >= 0) v else config.leakyReluAlpha * v
        }

        // Layer normalization (in place) si activée. DOIT s'appliquer en inférence !
        if (config.useBatchNorm) {
            var sum = 0.0
            for (h in 0 until hiddenSize) {
                sum += hidden[h]
            }
            val mean = sum / hiddenSize

            var sumSq = 0.0
            for (h in 0 until hiddenSize) {
                val diff = hidden[h] - mean
                sumSq += diff * diff
            }
            val variance = sumSq / hiddenSize
            val denom = sqrt(variance + 1e-8)
            for (h in 0 until hiddenSize) {
                hidden[h] = (hidden[h] - mean) / denom
            }
        }

        // Application du dropout (in place) avec inverted dropout scaling
        if (!inferenceMode && config.useDropout) {
            val keepProb = 1.0 - config.dropoutRate
            for (h in 0 until hiddenSize) {
                if (Random.nextDouble() < config.dropoutRate) {
                    hidden[h] = 0.0
                } else {
                    hidden[h] = hidden[h] / keepProb
                }
            }
        }

        // Calcul de la couche de sortie
        val output = DoubleArray(outputSize)
        for (o in 0 until outputSize) {
            var sum = 0.0
            for (h in 0 until hiddenSize) {
                sum += hidden[h] * weightsHiddenOutput[h][o]
            }
            output[o] = sum + biasOutput[o]
        }

        return hidden to output
    }


    // Loss and regularization
    private fun hybridLoss(output: DoubleArray, target: DoubleArray): Double {
        val alpha = 0.3 // Vous pouvez ajuster ce coefficient selon vos besoins
        val mae = maeLoss(output, target)
        val mse = output.zip(target).sumOf { (o, t) -> (o - t).pow(2.0) } / output.size

        return alpha * mae + (1 - alpha) * mse
    }

    private fun maeLoss(output: DoubleArray, target: DoubleArray): Double {
        var sumAbs = 0.0
        for (i in output.indices) {
            sumAbs += abs(output[i] - target[i])
        }
        return sumAbs / output.size
    }

    private fun l2Regularization(): Double {
        var reg = 0.0
        weightsInputHidden.forEach { row -> row.forEach { w -> reg += w.pow(2.0) } }
        weightsHiddenOutput.forEach { row -> row.forEach { w -> reg += w.pow(2.0) } }
        return reg * regularizationLambda
    }

    // Backpropagation + ADAM
    private fun backpropagation(input: FloatArray, target: DoubleArray): Pair<Array<DoubleArray>, Array<DoubleArray>> {
        val (hidden, output) = forwardPass(input, inferenceMode = false)

        val gradOutput = DoubleArray(outputSize) { i ->
            sign(output[i] - target[i])
        }

        val gradHiddenOutput = Array(hiddenSize) { h ->
            DoubleArray(outputSize) { o ->
                gradOutput[o] * hidden[h]
            }
        }

        val gradHidden = DoubleArray(hiddenSize) { h ->
            val sum = gradOutput.indices.sumOf { o -> gradOutput[o] * weightsHiddenOutput[h][o] }
            if (hidden[h] >= 0) sum else sum * config.leakyReluAlpha
        }

        val gradInputHidden = Array(inputSize) { i ->
            DoubleArray(hiddenSize) { h ->
                gradHidden[h] * input[i]
            }
        }

        return gradInputHidden to gradHiddenOutput
    }

    private val mInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { 0.0 } }
    private val vInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { 0.0 } }
    private val mHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { 0.0 } }
    private val vHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { 0.0 } }
    private var adamStep = 0

    private fun adamUpdate(
        weights: Array<DoubleArray>,
        grads: Array<DoubleArray>,
        m: Array<DoubleArray>,
        v: Array<DoubleArray>
    ) {
        adamStep++
        val beta1 = config.beta1
        val beta2 = config.beta2
        val eps = config.epsilon
        for (i in weights.indices) {
            for (j in weights[i].indices) {
                m[i][j] = beta1 * m[i][j] + (1 - beta1) * grads[i][j]
                v[i][j] = beta2 * v[i][j] + (1 - beta2) * grads[i][j] * grads[i][j]

                val mHat = m[i][j] / (1 - beta1.pow(adamStep.toDouble()))
                val vHat = v[i][j] / (1 - beta2.pow(adamStep.toDouble()))

                weights[i][j] -= config.learningRate * (mHat / (sqrt(vHat) + eps))

                // L2 weight decay (optionnel)
                weights[i][j] -= config.weightDecay * weights[i][j]
            }
        }
    }

    fun trainWithValidation(
        trainInputs: List<FloatArray>,
        trainTargets: List<DoubleArray>,
        valInputs: List<FloatArray>,
        valTargets: List<DoubleArray>
    ) {
        if (trainInputs.isEmpty()) {
            println("No training data - aborting.")
            return
        }

        // Reset de l'historique
        trainingLossHistory.clear()
        bestValLoss = Double.MAX_VALUE
        adamStep = 0

        val totalEpochs = if (config.epochs <= 0) 1000 else config.epochs
        val batchSize = if (config.batchSize <= 0) 32 else config.batchSize
        var epochsWithoutImprovement = 0

        for (epoch in 1..totalEpochs) {
            val indices = trainInputs.indices.shuffled()
            var totalLoss = 0.0

            // mini-batch
            indices.chunked(batchSize).forEach { batchIdx ->
                batchIdx.forEach { idx ->
                    val input = trainInputs[idx]
                    val target = trainTargets[idx]
                    val (gradIH, gradHO) = backpropagation(input, target)

                    // Update input->hidden
                    adamUpdate(weightsInputHidden, gradIH, mInputHidden, vInputHidden)
                    // Update hidden->output
                    adamUpdate(weightsHiddenOutput, gradHO, mHiddenOutput, vHiddenOutput)

                    // recalc pour la loss
                    val out = forwardPass(input, inferenceMode = false).second
                    totalLoss += hybridLoss(out, target)
                }
            }

            val avgTrainLoss = totalLoss / trainInputs.size
            trainingLossHistory.add(avgTrainLoss)

            // Validation
            val valLoss = validate(valInputs, valTargets)
            println("Epoch $epoch/$totalEpochs - trainLoss=$avgTrainLoss - valLoss=$valLoss")

            if (valLoss < bestValLoss) {
                bestValLoss = valLoss
                epochsWithoutImprovement = 0
                // Optionnel : sauvegarder les poids
            } else {
                epochsWithoutImprovement++
                if (epochsWithoutImprovement >= config.patience) {
                    println("Early stopping at epoch $epoch (no improvement).")
                    break
                }
            }
        }
    }


    fun validate(valInputs: List<FloatArray>, valTargets: List<DoubleArray>): Double {
        if (valInputs.isEmpty()) return 0.0

        var totalLoss = 0.0
        for (i in valInputs.indices) {
            val out = forwardPass(valInputs[i], inferenceMode = true).second
            totalLoss += hybridLoss(out, valTargets[i])
        }
        totalLoss += l2Regularization()
        return totalLoss / valInputs.size
    }

    fun saveToFile(file: File) {
        val root = JSONObject()
        root.put("inputSize", inputSize)
        root.put("hiddenSize", hiddenSize)
        root.put("outputSize", outputSize)

        fun DoubleArray.toJsonArray(): JSONArray {
            val arr = JSONArray()
            this.forEach { arr.put(it) }
            return arr
        }

        fun Array<DoubleArray>.toJsonArray(): JSONArray {
            val arr = JSONArray()
            this.forEach { arr.put(it.toJsonArray()) }
            return arr
        }

        root.put("weightsInputHidden", weightsInputHidden.toJsonArray())
        root.put("biasHidden", biasHidden.toJsonArray())
        root.put("weightsHiddenOutput", weightsHiddenOutput.toJsonArray())
        root.put("biasOutput", biasOutput.toJsonArray())

        file.writeText(root.toString())
    }

    companion object {
        fun loadFromFile(file: File): AimiNeuralNetwork? {
            if (!file.exists()) return null
            try {
                val root = JSONObject(file.readText())
                val inputSize = root.getInt("inputSize")
                val hiddenSize = root.getInt("hiddenSize")
                val outputSize = root.getInt("outputSize")

                val nn = AimiNeuralNetwork(inputSize, hiddenSize, outputSize)

                fun parseDoubleArray(jsonArr: JSONArray): DoubleArray {
                    return DoubleArray(jsonArr.length()) { i -> jsonArr.getDouble(i) }
                }

                fun parseArrayOfDoubleArray(jsonArr: JSONArray): Array<DoubleArray> {
                    return Array(jsonArr.length()) { i ->
                        parseDoubleArray(jsonArr.getJSONArray(i))
                    }
                }

                nn.weightsInputHidden = parseArrayOfDoubleArray(root.getJSONArray("weightsInputHidden"))
                nn.biasHidden = parseDoubleArray(root.getJSONArray("biasHidden"))
                nn.weightsHiddenOutput = parseArrayOfDoubleArray(root.getJSONArray("weightsHiddenOutput"))
                nn.biasOutput = parseDoubleArray(root.getJSONArray("biasOutput"))

                return nn
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }

        fun refineSMB(smb: Float, nn: AimiNeuralNetwork, input: DoubleArray?): Float {
            if (input == null) return smb
            val floatInput = input.map { it.toFloat() }.toFloatArray()
            val prediction = nn.predict(floatInput)[0]
            return smb + prediction.toFloat()
        }
    }

    fun copyWeightsFrom(other: AimiNeuralNetwork) {
        for (i in weightsInputHidden.indices) {
            for (j in weightsInputHidden[i].indices) {
                weightsInputHidden[i][j] = other.weightsInputHidden[i][j]
            }
        }
        for (i in biasHidden.indices) {
            biasHidden[i] = other.biasHidden[i]
        }
        for (i in weightsHiddenOutput.indices) {
            for (j in weightsHiddenOutput[i].indices) {
                weightsHiddenOutput[i][j] = other.weightsHiddenOutput[i][j]
            }
        }
        for (i in biasOutput.indices) {
            biasOutput[i] = other.biasOutput[i]
        }
    }

}
