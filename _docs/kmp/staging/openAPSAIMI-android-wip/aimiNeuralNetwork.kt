package app.aaps.plugins.aps.openAPSAIMI

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small one hidden layer network used by every AIMI on device learner (SMB refinement, basal, T3C, oref heads).
 *
 * Layout: `input -> z-score -> linear -> LeakyReLU -> (optional layer norm) -> (optional dropout) -> linear -> output`.
 *
 * Training notes:
 * - Inputs are z-scored with [inputMean] / [inputStd]. The caller must feed the training statistics with
 *   [setInputNormalization] **before** [trainWithValidation]. Without it the network keeps the identity transform
 *   (mean 0, std 1) and behaves like the old raw input model.
 * - Weights **and** biases are trained by one Adam path. The output bias carries the mean of the label, so it must
 *   be trainable, otherwise the network can never reach the label range.
 * - Adam and the weight decay run once per mini batch, on the summed gradient of that batch.
 * - Every random draw comes from one seeded generator, so the same data trains the same model twice. See [random].
 */
class AimiNeuralNetwork(
    private val inputSize: Int,
    private val hiddenSize: Int,
    private val outputSize: Int,
    private val config: TrainingConfig = TrainingConfig(),
    // Kept for source compatibility with the callers. Since the decay became decoupled AdamW it changes no weight
    // and no loss any more; it only scales the diagnostic number of l2WeightPenalty().
    private val regularizationLambda: Double = 0.01
) {

    /**
     * Source of every random draw of this network: the start weights, the epoch shuffle and the dropout mask.
     *
     * Training has to give the same model twice for the same data. Without that the publish gate is a coin flip:
     * two runs on the same seeded data gave a worst case output spread of 0.0149 and then 0.0299, on either side of
     * the 0.05 gate. The seed comes from [TrainingConfig.randomSeed] and has a fixed default, so training is
     * reproducible with no extra work from the caller. [trainWithValidation] sets this generator back to the seed on
     * every call, so a run does not depend on how many draws happened before it.
     *
     * Declared before the weights on purpose: Kotlin builds the properties in order, and the weight init draws from
     * it.
     */
    private var random: Random = Random(config.randomSeed)

    // Weights and biases
    private var weightsInputHidden = Array(inputSize) {
        DoubleArray(hiddenSize) { random.nextDouble(-sqrt(2.0 / inputSize), sqrt(2.0 / inputSize)) }
    }
    private var biasHidden = DoubleArray(hiddenSize) { INITIAL_BIAS }

    private var weightsHiddenOutput = Array(hiddenSize) {
        DoubleArray(outputSize) { random.nextDouble(-sqrt(2.0 / hiddenSize), sqrt(2.0 / hiddenSize)) }
    }
    private var biasOutput = DoubleArray(outputSize) { INITIAL_BIAS }

    /** Mean of every input feature. Default 0 = no shift. */
    private var inputMean = DoubleArray(inputSize) { 0.0 }

    /** Standard deviation of every input feature. Default 1 = no scaling. A degenerate value is replaced by 1. */
    private var inputStd = DoubleArray(inputSize) { 1.0 }

    /** True when the parameters come from a file or from another network, so training must not re-init the biases. */
    private var parametersRestored = false

    // History, for debug or tracking
    private val trainingLossHistory = mutableListOf<Double>()
    private var bestValLoss = Double.MAX_VALUE

    /**
     * Set the z-score statistics used on every input.
     *
     * Raw glucose runs from 40 to 400 while the physiological features run from 0 to 1. Without this the hidden layer
     * is driven by the one feature with the largest raw scale.
     *
     * Arrays of the wrong size are ignored, so a wiring mistake keeps the identity transform instead of crashing.
     *
     * A standard deviation that is not usable (not finite, or at or below [MIN_INPUT_STD]) is replaced by **1**, the
     * identity scale, and not by [MIN_INPUT_STD] itself. Flooring at 1e-6 would divide that feature by 1e-6, so a
     * column that is constant in the training data but moves at inference time would be amplified a million times
     * and would take over the hidden layer. Leaving it unscaled is the safe answer. This is also what
     * `NeuralModelTrainer.inputNormalizationStats` already does, so both sides now agree.
     */
    fun setInputNormalization(mean: DoubleArray, std: DoubleArray) {
        if (mean.size != inputSize || std.size != inputSize) return
        for (i in 0 until inputSize) {
            val m = mean[i]
            val s = abs(std[i])
            inputMean[i] = if (m.isFinite()) m else 0.0
            inputStd[i] = if (s.isFinite() && s > MIN_INPUT_STD) s else 1.0
        }
    }

    // Activation
    private fun leakyRelu(x: Double): Double = if (x >= 0) x else config.leakyReluAlpha * x

    private fun leakyReluDerivative(preActivation: Double): Double = if (preActivation >= 0) 1.0 else config.leakyReluAlpha

    fun predict(input: FloatArray): DoubleArray = forwardPass(input, inferenceMode = true).output

    /** Everything one forward pass produced. The backward pass needs the intermediate values, not only the output. */
    private class Activations(
        val normalizedInput: DoubleArray,
        val preActivation: DoubleArray,
        val normalized: DoubleArray,
        val normDenom: Double,
        val dropScale: DoubleArray,
        val hidden: DoubleArray,
        val output: DoubleArray
    )

    /**
     * @throws IllegalArgumentException when [input] does not have [inputSize] values. The model stores rely on that
     *   to reject a saved model whose feature schema no longer matches the caller.
     */
    private fun forwardPass(input: FloatArray, inferenceMode: Boolean): Activations {
        require(input.size == inputSize) { "AimiNeuralNetwork expects $inputSize inputs but got ${input.size}" }

        val normalizedInput = DoubleArray(inputSize) { i -> (input[i] - inputMean[i]) / inputStd[i] }

        // Hidden layer: weighted sum + bias
        val preActivation = DoubleArray(hiddenSize)
        for (h in 0 until hiddenSize) {
            var sum = 0.0
            for (i in 0 until inputSize) {
                sum += normalizedInput[i] * weightsInputHidden[i][h]
            }
            preActivation[h] = sum + biasHidden[h]
        }

        // LeakyReLU
        val normalized = DoubleArray(hiddenSize) { h -> leakyRelu(preActivation[h]) }

        // Layer normalization, if enabled. It must also run during inference.
        var normDenom = 1.0
        if (config.useBatchNorm) {
            var mean = 0.0
            for (h in 0 until hiddenSize) mean += normalized[h]
            mean /= hiddenSize

            var sumSq = 0.0
            for (h in 0 until hiddenSize) {
                val diff = normalized[h] - mean
                sumSq += diff * diff
            }
            normDenom = sqrt(sumSq / hiddenSize + 1e-8)
            for (h in 0 until hiddenSize) {
                normalized[h] = (normalized[h] - mean) / normDenom
            }
        }

        // Inverted dropout. Kept as a per unit scale so the backward pass can use the same mask.
        val dropScale = DoubleArray(hiddenSize) { 1.0 }
        if (!inferenceMode && config.useDropout && config.dropoutRate > 0.0) {
            val keepProb = max(1.0 - config.dropoutRate, MIN_KEEP_PROB)
            for (h in 0 until hiddenSize) {
                dropScale[h] = if (random.nextDouble() < config.dropoutRate) 0.0 else 1.0 / keepProb
            }
        }
        val hidden = DoubleArray(hiddenSize) { h -> normalized[h] * dropScale[h] }

        // Output layer
        val output = DoubleArray(outputSize)
        for (o in 0 until outputSize) {
            var sum = 0.0
            for (h in 0 until hiddenSize) {
                sum += hidden[h] * weightsHiddenOutput[h][o]
            }
            output[o] = sum + biasOutput[o]
        }

        return Activations(normalizedInput, preActivation, normalized, normDenom, dropScale, hidden, output)
    }

    // Loss and regularization
    private fun hybridLoss(output: DoubleArray, target: DoubleArray): Double {
        val mae = maeLoss(output, target)
        val mse = output.indices.sumOf { i -> (output[i] - target[i]).pow(2.0) } / output.size
        return MAE_WEIGHT * mae + MSE_WEIGHT * mse
    }

    /** Derivative of [hybridLoss] for one output. Must stay in step with [hybridLoss] or training follows a different loss. */
    private fun hybridLossGradient(error: Double): Double = MAE_WEIGHT * sign(error) + MSE_WEIGHT * 2.0 * error

    private fun maeLoss(output: DoubleArray, target: DoubleArray): Double {
        var sumAbs = 0.0
        for (i in output.indices) {
            sumAbs += abs(output[i] - target[i])
        }
        return sumAbs / output.size
    }

    /**
     * Sum of the squared weights times [regularizationLambda]. **Diagnostics only.** It is not part of the training
     * loss and not part of [validate] any more, so it never changes a weight or a publish decision.
     */
    internal fun l2WeightPenalty(): Double {
        var reg = 0.0
        weightsInputHidden.forEach { row -> row.forEach { w -> reg += w * w } }
        weightsHiddenOutput.forEach { row -> row.forEach { w -> reg += w * w } }
        return reg * regularizationLambda
    }

    // Gradients
    /** Summed gradient of one mini batch. Reused between batches to keep the allocation count low. */
    private class Gradients(inputSize: Int, hiddenSize: Int, outputSize: Int) {

        val inputHidden = Array(inputSize) { DoubleArray(hiddenSize) }
        val biasHidden = DoubleArray(hiddenSize)
        val hiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) }
        val biasOutput = DoubleArray(outputSize)

        fun clear() {
            inputHidden.forEach { it.fill(0.0) }
            biasHidden.fill(0.0)
            hiddenOutput.forEach { it.fill(0.0) }
            biasOutput.fill(0.0)
        }
    }

    /**
     * Backward pass through the layer normalization.
     *
     * With `y = (a - mean(a)) / denom`, the gradient on `a` is
     * `(gradY - mean(gradY) - y * mean(gradY * y)) / denom`.
     */
    private fun layerNormBackward(gradY: DoubleArray, y: DoubleArray, denom: Double): DoubleArray {
        var meanGrad = 0.0
        var meanGradY = 0.0
        for (h in 0 until hiddenSize) {
            meanGrad += gradY[h]
            meanGradY += gradY[h] * y[h]
        }
        meanGrad /= hiddenSize
        meanGradY /= hiddenSize
        return DoubleArray(hiddenSize) { h -> (gradY[h] - meanGrad - y[h] * meanGradY) / denom }
    }

    /** Add the gradient of one sample into [grads] and return the loss of that sample. */
    private fun accumulateGradients(input: FloatArray, target: DoubleArray, grads: Gradients): Double {
        val act = forwardPass(input, inferenceMode = false)

        val gradOutput = DoubleArray(outputSize) { o -> hybridLossGradient(act.output[o] - target[o]) }

        for (h in 0 until hiddenSize) {
            for (o in 0 until outputSize) {
                grads.hiddenOutput[h][o] += gradOutput[o] * act.hidden[h]
            }
        }
        for (o in 0 until outputSize) {
            grads.biasOutput[o] += gradOutput[o]
        }

        // Back through the dropout mask
        val gradNormalized = DoubleArray(hiddenSize) { h ->
            var sum = 0.0
            for (o in 0 until outputSize) sum += gradOutput[o] * weightsHiddenOutput[h][o]
            sum * act.dropScale[h]
        }

        val gradActivated = if (config.useBatchNorm) layerNormBackward(gradNormalized, act.normalized, act.normDenom) else gradNormalized

        // The LeakyReLU derivative must use the value before the activation, not the value after it.
        val gradPreActivation = DoubleArray(hiddenSize) { h -> gradActivated[h] * leakyReluDerivative(act.preActivation[h]) }

        for (i in 0 until inputSize) {
            val x = act.normalizedInput[i]
            for (h in 0 until hiddenSize) {
                grads.inputHidden[i][h] += gradPreActivation[h] * x
            }
        }
        for (h in 0 until hiddenSize) {
            grads.biasHidden[h] += gradPreActivation[h]
        }

        return hybridLoss(act.output, target)
    }

    // Adam state. Every trained parameter needs its own moment buffers, biases included.
    private val mInputHidden = Array(inputSize) { DoubleArray(hiddenSize) }
    private val vInputHidden = Array(inputSize) { DoubleArray(hiddenSize) }
    private val mHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) }
    private val vHiddenOutput = Array(hiddenSize) { DoubleArray(outputSize) }
    private val mBiasHidden = arrayOf(DoubleArray(hiddenSize))
    private val vBiasHidden = arrayOf(DoubleArray(hiddenSize))
    private val mBiasOutput = arrayOf(DoubleArray(outputSize))
    private val vBiasOutput = arrayOf(DoubleArray(outputSize))
    private var adamStep = 0

    /**
     * One Adam update of one parameter group. The caller raises [adamStep] once per mini batch, so every group of the
     * same batch shares one step number and the bias correction stays right.
     *
     * [scale] turns the summed batch gradient into the mean batch gradient. [applyDecay] uses the decoupled AdamW
     * form `w -= learningRate * weightDecay * w`: without the learning rate factor the decay balances the Adam step
     * at `|w| = learningRate / weightDecay`, which caps every weight at 0.05 for the shipped basal config.
     */
    private fun adamUpdate(
        weights: Array<DoubleArray>,
        grads: Array<DoubleArray>,
        m: Array<DoubleArray>,
        v: Array<DoubleArray>,
        scale: Double,
        applyDecay: Boolean
    ) {
        val beta1 = config.beta1
        val beta2 = config.beta2
        val eps = config.epsilon
        val correction1 = 1 - beta1.pow(adamStep.toDouble())
        val correction2 = 1 - beta2.pow(adamStep.toDouble())
        for (i in weights.indices) {
            for (j in weights[i].indices) {
                val grad = grads[i][j] * scale
                m[i][j] = beta1 * m[i][j] + (1 - beta1) * grad
                v[i][j] = beta2 * v[i][j] + (1 - beta2) * grad * grad

                val mHat = m[i][j] / correction1
                val vHat = v[i][j] / correction2

                weights[i][j] -= config.learningRate * (mHat / (sqrt(vHat) + eps))
                if (applyDecay) {
                    weights[i][j] -= config.learningRate * config.weightDecay * weights[i][j]
                }
            }
        }
    }

    /** Copy of every trained parameter, used to keep the best epoch. */
    private class Snapshot(
        val weightsInputHidden: Array<DoubleArray>,
        val biasHidden: DoubleArray,
        val weightsHiddenOutput: Array<DoubleArray>,
        val biasOutput: DoubleArray
    )

    private fun snapshotParameters() = Snapshot(
        Array(inputSize) { weightsInputHidden[it].copyOf() },
        biasHidden.copyOf(),
        Array(hiddenSize) { weightsHiddenOutput[it].copyOf() },
        biasOutput.copyOf()
    )

    private fun restoreParameters(snapshot: Snapshot) {
        for (i in 0 until inputSize) snapshot.weightsInputHidden[i].copyInto(weightsInputHidden[i])
        snapshot.biasHidden.copyInto(biasHidden)
        for (h in 0 until hiddenSize) snapshot.weightsHiddenOutput[h].copyInto(weightsHiddenOutput[h])
        snapshot.biasOutput.copyInto(biasOutput)
    }

    /**
     * Train on [trainInputs] with early stopping on [valInputs]. The parameters of the best validation epoch are put
     * back before returning, so the published model is the best one and not the last one.
     *
     * On a fresh network the output bias starts at the mean of the labels. The layer output is centred on that bias,
     * so this saves the optimizer the long walk from 0.01 to the label range. A network restored from a file keeps
     * its own bias.
     *
     * The run is deterministic: [random] is set back to [TrainingConfig.randomSeed] here, so the same data and the
     * same seed give the same model.
     *
     * @param log optional sink for one summary line per run. This class has no logger, and it runs inside a
     *   background worker on a phone, so it must never write to stdout. The caller decides where the line goes.
     */
    fun trainWithValidation(
        trainInputs: List<FloatArray>,
        trainTargets: List<DoubleArray>,
        valInputs: List<FloatArray>,
        valTargets: List<DoubleArray>,
        log: (String) -> Unit = {}
    ) {
        if (trainInputs.isEmpty()) {
            log("no training data, nothing trained")
            return
        }

        trainingLossHistory.clear()
        bestValLoss = Double.MAX_VALUE
        adamStep = 0
        random = Random(config.randomSeed)

        if (!parametersRestored) {
            for (o in 0 until outputSize) {
                var sum = 0.0
                var count = 0
                for (target in trainTargets) {
                    if (o < target.size) {
                        sum += target[o]
                        count++
                    }
                }
                if (count > 0) biasOutput[o] = sum / count
            }
        }

        val totalEpochs = if (config.epochs <= 0) 1000 else config.epochs
        val batchSize = if (config.batchSize <= 0) 32 else config.batchSize
        var epochsWithoutImprovement = 0

        val grads = Gradients(inputSize, hiddenSize, outputSize)
        var best: Snapshot? = null
        var lastEpoch = 0
        var stoppedEarly = false

        for (epoch in 1..totalEpochs) {
            lastEpoch = epoch
            val indices = trainInputs.indices.shuffled(random)
            var totalLoss = 0.0

            indices.chunked(batchSize).forEach { batchIdx ->
                grads.clear()
                batchIdx.forEach { idx ->
                    totalLoss += accumulateGradients(trainInputs[idx], trainTargets[idx], grads)
                }

                // One Adam step and one decay per batch, on the mean gradient of the batch.
                adamStep++
                val scale = 1.0 / batchIdx.size
                adamUpdate(weightsInputHidden, grads.inputHidden, mInputHidden, vInputHidden, scale, applyDecay = true)
                adamUpdate(weightsHiddenOutput, grads.hiddenOutput, mHiddenOutput, vHiddenOutput, scale, applyDecay = true)
                adamUpdate(arrayOf(biasHidden), arrayOf(grads.biasHidden), mBiasHidden, vBiasHidden, scale, applyDecay = false)
                adamUpdate(arrayOf(biasOutput), arrayOf(grads.biasOutput), mBiasOutput, vBiasOutput, scale, applyDecay = false)
            }

            val avgTrainLoss = totalLoss / trainInputs.size
            trainingLossHistory.add(avgTrainLoss)

            val valLoss = validate(valInputs, valTargets)

            if (valLoss < bestValLoss) {
                bestValLoss = valLoss
                epochsWithoutImprovement = 0
                best = snapshotParameters()
            } else {
                epochsWithoutImprovement++
                if (epochsWithoutImprovement >= config.patience) {
                    stoppedEarly = true
                    break
                }
            }
        }

        best?.let { restoreParameters(it) }
        log("trained $lastEpoch/$totalEpochs epochs, bestValLoss=$bestValLoss, earlyStop=$stoppedEarly")
    }

    /** Best validation loss from the most recent [trainWithValidation] run. */
    fun lastBestValidationLoss(): Double = bestValLoss

    /**
     * Mean [hybridLoss] over the validation set. Fit only: no weight penalty is added.
     *
     * The L2 term used to be added once for the whole set and then divided by n, so the reported number moved with
     * the size of the set. Measured with `regularizationLambda = 1.0` on identical rows: n=1 gave 4.1865 and n=50
     * gave 0.5364, a 7.8x swing from set size alone. Two more problems came with it: the candidate and the incumbent
     * have different weights, so the publish comparison was partly a contest of weight norms and not of fit; and the
     * per epoch early stopping saw a term that grows as training grows the weights (about 20% of the objective at
     * the shipped lambda 0.01 with ~136 weights and |w| ~ 0.1), which pushed it toward flatter models, exactly the
     * models the publish spread probe then rejects.
     *
     * A held out number must only say how well the model fits held out rows, so the penalty is gone from here. Use
     * [l2WeightPenalty] if you want that number on its own.
     */
    fun validate(valInputs: List<FloatArray>, valTargets: List<DoubleArray>): Double {
        if (valInputs.isEmpty()) return 0.0

        var totalLoss = 0.0
        for (i in valInputs.indices) {
            val out = forwardPass(valInputs[i], inferenceMode = true).output
            totalLoss += hybridLoss(out, valTargets[i])
        }
        return totalLoss / valInputs.size
    }

    fun saveToFile(file: File) {
        val root = JSONObject()
        root.put("schemaVersion", SCHEMA_VERSION)
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
        root.put("inputMean", inputMean.toJsonArray())
        root.put("inputStd", inputStd.toJsonArray())

        file.writeText(root.toString())
    }

    /** Copy of the output bias. For tests and diagnostics only. */
    internal fun outputBiasCopy(): DoubleArray = biasOutput.copyOf()

    /** Copy of the hidden bias. For tests and diagnostics only. */
    internal fun hiddenBiasCopy(): DoubleArray = biasHidden.copyOf()

    /** Copy of the input to hidden weights. For tests and diagnostics only. */
    internal fun inputHiddenWeightsCopy(): Array<DoubleArray> = Array(inputSize) { weightsInputHidden[it].copyOf() }

    /** Copy of the hidden to output weights. For tests and diagnostics only. */
    internal fun hiddenOutputWeightsCopy(): Array<DoubleArray> = Array(hiddenSize) { weightsHiddenOutput[it].copyOf() }

    companion object {

        /**
         * Version of the saved weight file.
         *
         * Version 2 adds the trained biases, the input normalization arrays and this field itself. A file without the
         * field, or with another value, is refused by [loadFromFile] and the caller falls back to no model. Every
         * weight file written before this version is therefore dropped on the first load after the update, on every
         * device and for every head (basal, T3C, SMB), backup files included. That is on purpose: those files hold a
         * constant model that could not be trained by the old code, and they must not survive the fix.
         */
        const val SCHEMA_VERSION = 2

        /**
         * Smallest standard deviation still treated as real. Anything at or below it is a constant column, and the
         * z-score falls back to the identity scale of 1 instead of dividing by a near zero number.
         */
        private const val MIN_INPUT_STD = 1e-6

        /** Smallest keep probability of the dropout, so a rate of 1.0 cannot divide by zero. */
        private const val MIN_KEEP_PROB = 1e-3

        private const val INITIAL_BIAS = 0.01

        /** Weight of the MAE part of the hybrid loss. */
        private const val MAE_WEIGHT = 0.3

        /** Weight of the MSE part of the hybrid loss. */
        private const val MSE_WEIGHT = 1.0 - MAE_WEIGHT

        fun loadFromFile(file: File): AimiNeuralNetwork? {
            if (!file.exists()) return null
            try {
                val root = JSONObject(file.readText())
                if (root.optInt("schemaVersion", 0) != SCHEMA_VERSION) return null
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
                nn.setInputNormalization(
                    parseDoubleArray(root.getJSONArray("inputMean")),
                    parseDoubleArray(root.getJSONArray("inputStd"))
                )
                nn.parametersRestored = true

                return nn
            } catch (_: Exception) {
                // A file we cannot read is simply "no model". AimiNeuralModelStore.load then tries the .bak and
                // finally lets the caller run on its neutral path. This class has no logger, and it must not write
                // to stdout from a background worker on a phone.
                return null
            }
        }
    }

    /** Copy every trained parameter and the input statistics from [other]. Sizes must match. */
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
        setInputNormalization(other.inputMean, other.inputStd)
        parametersRestored = true
    }

}
