package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class AimiNeuralNetwork(
    private val inputSize: Int,
    private val hiddenSize: Int,
    private val outputSize: Int,
    private var learningRate: Double = 0.01,
    private val regularizationLambda: Double = 0.01,
    private val dropoutRate: Double = 0.5
) {

    private var weightsInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { Random.nextDouble(-sqrt(6.0 / (inputSize + hiddenSize)), sqrt(6.0 / (inputSize + hiddenSize))) } }
    private var biasHidden = DoubleArray(hiddenSize) { 0.01 }
    private var weightsHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { Random.nextDouble(-sqrt(6.0 / (hiddenSize + outputSize)), sqrt(6.0 / (hiddenSize + outputSize))) } }
    private var biasOutput = DoubleArray(outputSize) { 0.01 }

    private var lastTrainingException: Exception? = null
    private var trainingLossHistory: MutableList<Double> = mutableListOf()

    companion object {

        fun refineSMB(smb: Float, neuralNetwork: AimiNeuralNetwork, input: FloatArray): Float {
            val prediction = neuralNetwork.predict(input)[0]
            return smb + prediction.toFloat()
        }

    }

    private fun applyDropout(layer: DoubleArray): DoubleArray {
        return layer.map { if (Random.nextDouble() > dropoutRate) it else 0.0 }.toDoubleArray()
    }

    private fun relu(x: Double) = max(0.0, x)

    private fun createNewFeature(inputData: List<FloatArray>): List<FloatArray> {
        return inputData.map { originalFeatures ->
            val newFeature = originalFeatures[0] * originalFeatures[1]
            originalFeatures + newFeature
        }
    }

    private fun zScoreNormalization(inputData: List<FloatArray>): List<FloatArray> {
        if (inputData.isEmpty()) {
            return emptyList()
        }
        val means = FloatArray(inputData.first().size) { 0f }
        val stdDevs = FloatArray(inputData.first().size) { 0f }

        inputData.forEach { features ->
            features.forEachIndexed { index, value ->
                means[index] += value
                stdDevs[index] += value * value
            }
        }

        means.indices.forEach { i ->
            means[i] /= inputData.size.toFloat()
            stdDevs[i] = sqrt(stdDevs[i] / inputData.size - means[i] * means[i])
        }

        return inputData.map { features ->
            FloatArray(features.size) { index ->
                if (stdDevs[index] != 0.0f) {
                    (features[index] - means[index]) / stdDevs[index]
                } else {
                    features[index] - means[index]
                }
            }
        }
    }

    private fun forwardPass(input: FloatArray): Pair<DoubleArray, DoubleArray> {
        val hidden = DoubleArray(hiddenSize) { i ->
            var sum = 0.0
            for (j in input.indices) {
                sum += input[j] * weightsInputHidden[j][i]
            }
            relu(sum + biasHidden[i])
        }
        val hiddenWithDropout = applyDropout(hidden)

        val hidden2 = DoubleArray(hiddenSize) { i ->
            var sum = 0.0
            for (j in hiddenWithDropout.indices) {
                sum += hiddenWithDropout[j] * weightsInputHidden[j][i]
            }
            relu(sum + biasHidden[i])
        }

        val output = DoubleArray(outputSize) { i ->
            var sum = 0.0
            for (j in hidden2.indices) {
                sum += hidden2[j] * weightsHiddenOutput[j][i]
            }
            sum + biasOutput[i]
        }

        return Pair(hidden2, output)
    }

    fun predict(input: FloatArray): DoubleArray {
        return forwardPass(input).second
    }

    private fun mseLoss(output: DoubleArray, target: DoubleArray): Double {
        return output.indices.sumOf { i -> (output[i] - target[i]).pow(2.0) } / output.size
    }

    private fun l2Regularization(): Double {
        var regularizationLoss = 0.0
        weightsInputHidden.forEach { layer ->
            layer.forEach { weight ->
                regularizationLoss += weight.pow(2.0)
            }
        }
        weightsHiddenOutput.forEach { layer ->
            layer.forEach { weight ->
                regularizationLoss += weight.pow(2.0)
            }
        }
        return regularizationLoss * regularizationLambda
    }

    fun train(
        inputs: List<FloatArray>,
        targets: List<DoubleArray>,
        validationRawInputs: List<FloatArray>,
        validationTargets: List<DoubleArray>,
        epochs: Int,
        batchSize: Float = 32.0f,
        patience: Int = 10
    ) {
        var bestLoss = Double.MAX_VALUE
        var epochsWithoutImprovement = 0
        val preparedInputs = createNewFeature(inputs)
        val normalizedInputs = zScoreNormalization(preparedInputs)
        val preparedValidationInputs = createNewFeature(validationRawInputs)
        val normalizedValidationInputs = zScoreNormalization(preparedValidationInputs)

        val m = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        val v = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        val mOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
        val vOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
        var t = 1

        for (epoch in 1..epochs) {
            var totalLoss = 0.0
            try {
                normalizedInputs.chunked(batchSize.toInt()).zip(targets.chunked(batchSize.toInt())).forEach { (batchInputs, batchTargets) ->
                    batchInputs.zip(batchTargets).forEach { (input, target) ->
                        val output = forwardPass(input).second
                        totalLoss += mseLoss(output, target) + l2Regularization()
                        backpropagation(input, target, m, v, mOutput, vOutput)
                    }
                }
                trainWithAdam(normalizedInputs, targets, normalizedValidationInputs, validationTargets, epochs - epoch + 1)
                val averageLoss = totalLoss / normalizedInputs.size
                trainingLossHistory.add(averageLoss)

                val validationLoss = validate(normalizedValidationInputs, validationTargets)
                println("Epoch $epoch, Training Loss: $averageLoss, Validation Loss: $validationLoss")

                if (validationLoss < bestLoss) {
                    bestLoss = validationLoss
                    epochsWithoutImprovement = 0
                } else {
                    epochsWithoutImprovement++
                    if (epochsWithoutImprovement >= patience) {
                        println("Early stopping triggered at epoch $epoch")
                        break
                    }
                }

                learningRate *= 0.95 // Réduction dynamique du taux d'apprentissage
                t++
            } catch (e: Exception) {
                lastTrainingException = e
                println("Exception during training at epoch $epoch: ${e.message}")
                break
            }
        }
    }

    private fun backpropagation(input: FloatArray, target: DoubleArray, m: Array<DoubleArray>, v: Array<DoubleArray>, mOutput: Array<DoubleArray>, vOutput: Array<DoubleArray>) {
        val (hidden, output) = forwardPass(input)
        val gradLossOutput = calculateOutputLayerGradient(output, target)
        updateWeightsAndBiasesForOutputLayer(hidden, gradLossOutput, mOutput, vOutput)

        val gradLossHidden = calculateHiddenLayerGradient(hidden, gradLossOutput)
        updateWeightsAndBiasesForHiddenLayer(input, hidden, gradLossHidden, m, v)
    }

    private fun calculateOutputLayerGradient(output: DoubleArray, target: DoubleArray): DoubleArray {
        return DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }
    }

    private fun updateWeightsAndBiasesForOutputLayer(hidden: DoubleArray, gradLossOutput: DoubleArray, mOutput: Array<DoubleArray>, vOutput: Array<DoubleArray>) {
        val gradHiddenOutput = calculateGradHiddenOutput(hidden, gradLossOutput)
        adam(weightsHiddenOutput, gradHiddenOutput, mOutput, vOutput, 1, learningRate)
        updateBiases(biasOutput, gradLossOutput)
    }

    private fun calculateHiddenLayerGradient(hidden: DoubleArray, gradLossOutput: DoubleArray): DoubleArray {
        return DoubleArray(hiddenSize) { j ->
            if (hidden[j] <= 0) 0.0
            else gradLossOutput.indices.sumOf { k -> gradLossOutput[k] * weightsHiddenOutput[j][k] }
        }
    }

    private fun updateWeightsAndBiasesForHiddenLayer(input: FloatArray, hidden: DoubleArray, gradLossHidden: DoubleArray, m: Array<DoubleArray>, v: Array<DoubleArray>) {
        val gradInputHidden = calculateGradInputHidden(input, hidden, gradLossHidden)
        adam(weightsInputHidden, gradInputHidden, m, v, 1, learningRate)
        updateBiasesForHiddenLayer(hidden, gradLossHidden)
    }

    private fun calculateGradHiddenOutput(hidden: DoubleArray, gradLossOutput: DoubleArray): Array<DoubleArray> {
        return Array(hiddenSize) { j ->
            DoubleArray(outputSize) { i ->
                gradLossOutput[i] * hidden[j]
            }
        }
    }

    private fun calculateGradInputHidden(input: FloatArray, hidden: DoubleArray, gradLossHidden: DoubleArray): Array<DoubleArray> {
        return Array(inputSize) { i ->
            DoubleArray(hiddenSize) { j ->
                val gradRelu = if (hidden[j] > 0) 1.0 else 0.001
                gradLossHidden[j] * input[i] * gradRelu
            }
        }
    }

    private fun updateBiases(biases: DoubleArray, gradLoss: DoubleArray) {
        for (i in biases.indices) {
            biases[i] -= learningRate * gradLoss[i]
        }
    }

    private fun updateBiasesForHiddenLayer(hidden: DoubleArray, gradLossHidden: DoubleArray) {
        for (j in hidden.indices) {
            val gradRelu = if (hidden[j] > 0) 1.0 else 0.001
            biasHidden[j] -= learningRate * gradLossHidden[j] * gradRelu
        }
    }

    private fun adam(
        params: Array<DoubleArray>,
        grads: Array<DoubleArray>,
        m: Array<DoubleArray>,
        v: Array<DoubleArray>,
        t: Int,
        learningRate: Double = 0.001,
        beta1: Double = 0.9,
        beta2: Double = 0.999,
        epsilon: Double = 1e-8
    ) {
        for (i in params.indices) {
            for (j in params[i].indices) {
                m[i][j] = beta1 * m[i][j] + (1 - beta1) * grads[i][j]
                v[i][j] = beta2 * v[i][j] + (1 - beta2) * grads[i][j] * grads[i][j]
                val mHat = m[i][j] / (1 - beta1.pow(t))
                val vHat = v[i][j] / (1 - beta2.pow(t))
                params[i][j] -= learningRate * mHat / (sqrt(vHat) + epsilon)
            }
        }
    }

    private fun trainWithAdam(
        inputs: List<FloatArray>,
        targets: List<DoubleArray>,
        validationRawInputs: List<FloatArray>,
        validationTargets: List<DoubleArray>,
        epochs: Int,
        batchSize: Int = 32,
        patience: Int = 10
    ) {
        val preparedInputs = createNewFeature(inputs)
        val normalizedInputs = zScoreNormalization(preparedInputs)
        val preparedValidationInputs = createNewFeature(validationRawInputs)
        val normalizedValidationInputs = zScoreNormalization(preparedValidationInputs)

        // Initialize Adam parameters
        val m = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        val v = Array(weightsInputHidden.size) { DoubleArray(weightsInputHidden[0].size) { 0.0 } }
        val mOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
        val vOutput = Array(weightsHiddenOutput.size) { DoubleArray(weightsHiddenOutput[0].size) { 0.0 } }
        var t = 0  // Initialize time step for Adam

        var bestLoss = Double.MAX_VALUE
        var epochsWithoutImprovement = 0

        for (epoch in 1..epochs) {
            var totalLoss = 0.0
            try {
                normalizedInputs.chunked(batchSize).zip(targets.chunked(batchSize)).forEach { (batchInputs, batchTargets) ->
                    batchInputs.zip(batchTargets).forEach { (input, target) ->
                        val (_, output) = forwardPass(input)
                        val gradOutputs = DoubleArray(outputSize) { i -> 2.0 * (output[i] - target[i]) }
                        val (gradInputHidden, gradHiddenOutput) = backpropagationWithAdam(input, gradOutputs)

                        // Update weights with Adam optimizer for both layers
                        updateWeightsAdam(weightsInputHidden, gradInputHidden, m, v, ++t, learningRate)
                        updateWeightsAdam(weightsHiddenOutput, gradHiddenOutput, mOutput, vOutput, t, learningRate)

                        totalLoss += mseLoss(output, target) + l2Regularization()
                    }
                }

                val averageLoss = totalLoss / normalizedInputs.size
                trainingLossHistory.add(averageLoss)

                val validationLoss = validate(normalizedValidationInputs, validationTargets)
                println("Epoch $epoch, Training Loss: $averageLoss, Validation Loss: $validationLoss")

                if (validationLoss < bestLoss) {
                    bestLoss = validationLoss
                    epochsWithoutImprovement = 0
                } else {
                    epochsWithoutImprovement++
                    if (epochsWithoutImprovement >= patience) {
                        println("Early stopping triggered at epoch $epoch")
                        break
                    }
                }

                learningRate *= 0.95  // Dynamically reduce the learning rate
            } catch (e: Exception) {
                lastTrainingException = e
                println("Exception during training at epoch $epoch: ${e.message}")
                break
            }
        }
    }

        private fun updateWeightsAdam(weights: Array<DoubleArray>, grads: Array<DoubleArray>, m: Array<DoubleArray>, v: Array<DoubleArray>, t: Int, learningRate: Double) {
            val beta1 = 0.9
            val beta2 = 0.999
            val epsilon = 1e-8

            for (i in weights.indices) {
                for (j in weights[i].indices) {
                    m[i][j] = beta1 * m[i][j] + (1 - beta1) * grads[i][j]
                    v[i][j] = beta2 * v[i][j] + (1 - beta2) * grads[i][j] * grads[i][j]

                    val mHat = m[i][j] / (1 - beta1.pow(t.toDouble()))
                    val vHat = v[i][j] / (1 - beta2.pow(t.toDouble()))

                    weights[i][j] -= learningRate * mHat / (sqrt(vHat) + epsilon)
                }
            }
        }

        private fun backpropagationWithAdam(input: FloatArray, gradOutputs: DoubleArray): Pair<Array<DoubleArray>, Array<DoubleArray>> {
            val (hidden, _) = forwardPass(input)
            val gradInputHidden = Array(inputSize) { DoubleArray(hiddenSize) { 0.0 } }
            val gradHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) { 0.0 } }

            // Calculate gradients for weights between the hidden layer and the output layer
            for (i in 0 until outputSize) {
                for (j in 0 until hiddenSize) {
                    gradHiddenOutput[j][i] = gradOutputs[i] * hidden[j]
                }
            }

            // Calculate gradients for weights between the input layer and the hidden layer
            for (i in 0 until inputSize) {
                for (j in 0 until hiddenSize) {
                    var gradLossHidden = 0.0
                    for (k in 0 until outputSize) {
                        gradLossHidden += gradOutputs[k] * weightsHiddenOutput[j][k]
                    }
                    // Correctly apply the ReLU derivative
                    val reluDerivative = if (hidden[j] > 0) 1.0 else 0.001
                    gradInputHidden[i][j] = gradLossHidden * reluDerivative * input[i]
                }
            }

            // Return both sets of gradients
            return Pair(gradInputHidden, gradHiddenOutput)
        }


    private fun validate(inputs: List<FloatArray>, targets: List<DoubleArray>): Double {
            var totalLoss = 0.0
            for ((input, target) in inputs.zip(targets)) {
                val output = forwardPass(input).second
                totalLoss += mseLoss(output, target) + l2Regularization()
            }
            return totalLoss / inputs.size
        }

    }

