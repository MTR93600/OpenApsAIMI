package app.aaps.plugins.aps.openAPSAIMI

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class aimiNeuralNetworkTest {

    @Test
    fun `test forward pass shape`() {
        val nn = AimiNeuralNetwork(inputSize = 3, hiddenSize = 5, outputSize = 1)
        val input = floatArrayOf(1.0f, 2.0f, 3.0f)
        val output = nn.predict(input)
        assertEquals(1, output.size)
    }

    @Test
    fun `test training reduces loss`() {
        // Simple regression: y = x1 + x2
        val nn = AimiNeuralNetwork(
            inputSize = 2,
            hiddenSize = 4,
            outputSize = 1,
            config = TrainingConfig(
                epochs = 100,
                learningRate = 0.01,
                batchSize = 1
            )
        )

        val inputs = listOf(
            floatArrayOf(0.0f, 0.0f),
            floatArrayOf(1.0f, 0.0f),
            floatArrayOf(0.0f, 1.0f),
            floatArrayOf(1.0f, 1.0f)
        )
        val targets = listOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0)
        )

        val initialLoss = nn.validate(inputs, targets)
        nn.trainWithValidation(inputs, targets, inputs, targets)
        val finalLoss = nn.validate(inputs, targets)

        assertTrue(finalLoss < initialLoss, "Loss should decrease")
    }

    /**
     * Acceptance test of the training fix.
     *
     * The shipped basal model was a constant function: its output moved by less than 1e-7 over the whole glucose
     * range. This test builds a data set where the label is a clear monotone function of feature 0, trains with the
     * real basal settings, and checks that the model really tracks that label.
     *
     * It fails on the old code, where the output bias was never trained, the weight decay ran once per sample, and
     * the layer normalization pinned the mean of the output to that frozen bias.
     */
    @Test
    fun `network learns a monotone target with the basal training config`() {
        val rng = Random(20260821)
        val inputs = ArrayList<FloatArray>(ROW_COUNT)
        val targets = ArrayList<DoubleArray>(ROW_COUNT)
        for (i in 0 until ROW_COUNT) {
            val bg = rng.nextDouble(BG_MIN, BG_MAX)
            inputs.add(syntheticRow(bg, rng))
            targets.add(doubleArrayOf(label(bg)))
        }

        val splitIdx = (ROW_COUNT * 0.8).toInt()
        val trainInputs = inputs.subList(0, splitIdx)
        val trainTargets = targets.subList(0, splitIdx)
        val valInputs = inputs.subList(splitIdx, ROW_COUNT)
        val valTargets = targets.subList(splitIdx, ROW_COUNT)

        val nn = AimiNeuralNetwork(
            inputSize = INPUT_SIZE,
            hiddenSize = 8,
            outputSize = 1,
            // Real basal head settings, see BasalMlTrainingCoordinator.
            config = TrainingConfig(learningRate = 0.0005, weightDecay = 0.01, batchSize = 32, epochs = 200, patience = 20)
        )
        val (mean, std) = featureStatistics(trainInputs)
        nn.setInputNormalization(mean, std)
        nn.trainWithValidation(trainInputs, trainTargets, valInputs, valTargets)

        // (a) The model is not constant and moves in the direction of the label.
        val noise = syntheticRow(0.0, Random(7))
        val lowBg = noise.copyOf().also { it[BG_INDEX] = 80f }
        val highBg = noise.copyOf().also { it[BG_INDEX] = 250f }
        val spread = nn.predict(highBg)[0] - nn.predict(lowBg)[0]
        assertTrue(
            spread >= 0.05,
            "Output must rise with glucose. predict(250) - predict(80) = $spread"
        )

        // (b) Mean absolute error on the held out slice.
        // On this data set the best possible constant model, the one that always answers the median label, has an
        // error of 0.227. The threshold of 0.08 is about a third of that, so only a model that follows the label can
        // pass. A working model lands near 0.03.
        var absError = 0.0
        for (i in valInputs.indices) {
            absError += abs(nn.predict(valInputs[i])[0] - valTargets[i][0])
        }
        val mae = absError / valInputs.size
        assertTrue(mae < 0.08, "Validation MAE too high: $mae")

        // (c) The output bias left its start value, so the model can reach the label range.
        val outputBias = nn.outputBiasCopy()[0]
        assertTrue(abs(outputBias - 0.01) > 0.1, "Output bias was not trained: $outputBias")

        // (d) No weight fell into the denormal range, which is what an unconditional per sample decay produces
        // on a feature that never gets a gradient.
        for (row in nn.inputHiddenWeightsCopy()) {
            for (w in row) assertTrue(abs(w) > 1e-300, "Input weight underflowed: $w")
        }
        for (row in nn.hiddenOutputWeightsCopy()) {
            for (w in row) assertTrue(abs(w) > 1e-300, "Output weight underflowed: $w")
        }
    }

    @Test
    fun `wrong sized normalization arrays are ignored`() {
        val nn = AimiNeuralNetwork(inputSize = 3, hiddenSize = 4, outputSize = 1)
        val before = nn.predict(floatArrayOf(1.0f, 2.0f, 3.0f))[0]
        nn.setInputNormalization(doubleArrayOf(0.0, 0.0), doubleArrayOf(1.0, 1.0))
        val after = nn.predict(floatArrayOf(1.0f, 2.0f, 3.0f))[0]
        assertEquals(before, after, 1e-12)
    }

    @Test
    fun `a constant feature does not divide by zero`() {
        val nn = AimiNeuralNetwork(inputSize = 2, hiddenSize = 4, outputSize = 1)
        nn.setInputNormalization(doubleArrayOf(5.0, 0.0), doubleArrayOf(0.0, 1.0))
        val out = nn.predict(floatArrayOf(5.0f, 1.0f))[0]
        assertTrue(out.isFinite(), "Output must stay finite for a constant feature")
    }

    /**
     * A standard deviation of 0 must mean "do not scale this feature", the same answer
     * `NeuralModelTrainer.inputNormalizationStats` gives. Flooring it at 1e-6 instead would divide the feature by
     * 1e-6, so a column that is constant while training but moves at inference time would be amplified a million
     * times and would take over the hidden layer.
     */
    @Test
    fun `a degenerate std falls back to the identity scale`() {
        val zeroStd = AimiNeuralNetwork(inputSize = 2, hiddenSize = 4, outputSize = 1)
        zeroStd.setInputNormalization(doubleArrayOf(5.0, 0.0), doubleArrayOf(0.0, 1.0))

        val identityStd = AimiNeuralNetwork(inputSize = 2, hiddenSize = 4, outputSize = 1)
        identityStd.setInputNormalization(doubleArrayOf(5.0, 0.0), doubleArrayOf(1.0, 1.0))

        // Feature 0 is far from its mean, so a 1e-6 divisor would show up as a huge output.
        val input = floatArrayOf(9.0f, 1.0f)
        assertEquals(
            identityStd.predict(input)[0],
            zeroStd.predict(input)[0],
            0.0,
            "std 0 must behave exactly like std 1, not like std 1e-6"
        )
    }

    /**
     * Training must give the same model twice for the same data.
     *
     * Before the seed was threaded through, the epoch shuffle used `indices.shuffled()` and the dropout mask used
     * `Random.nextDouble()`, both on the global generator. Two runs on identical seeded data gave a worst case
     * output spread of 0.0149 and then 0.0299, on either side of the 0.05 publish gate, so whether a trained model
     * published was a coin flip. A gate that is not reproducible cannot be tuned, validated or supported.
     *
     * This test fails on the old code: it needs the start weights, the shuffle and the dropout mask to all come from
     * the seeded generator.
     */
    @Test
    fun `the same data and seed train a bit-identical model`() {
        val (inputs, targets) = determinismDataSet()
        val config = { TrainingConfig(learningRate = 0.001, batchSize = 16, epochs = 40, patience = 40) }

        val first = AimiNeuralNetwork(inputSize = INPUT_SIZE, hiddenSize = 8, outputSize = 1, config = config())
        val second = AimiNeuralNetwork(inputSize = INPUT_SIZE, hiddenSize = 8, outputSize = 1, config = config())
        for (nn in listOf(first, second)) {
            val (mean, std) = featureStatistics(inputs)
            nn.setInputNormalization(mean, std)
            nn.trainWithValidation(inputs, targets, inputs, targets)
        }

        for (probe in determinismProbes()) {
            assertEquals(
                first.predict(probe)[0],
                second.predict(probe)[0],
                0.0,
                "two runs on the same data and seed must predict exactly the same value"
            )
        }
        assertEquals(first.lastBestValidationLoss(), second.lastBestValidationLoss(), 0.0)
    }

    /** The seed has to be the thing that decides the run, so another seed must give another model. */
    @Test
    fun `another seed trains another model`() {
        val (inputs, targets) = determinismDataSet()
        val base = TrainingConfig(learningRate = 0.001, batchSize = 16, epochs = 40, patience = 40)
        val other = base.copy(randomSeed = base.randomSeed + 1)

        val nets = listOf(base, other).map { cfg ->
            AimiNeuralNetwork(inputSize = INPUT_SIZE, hiddenSize = 8, outputSize = 1, config = cfg).also { nn ->
                val (mean, std) = featureStatistics(inputs)
                nn.setInputNormalization(mean, std)
                nn.trainWithValidation(inputs, targets, inputs, targets)
            }
        }

        val different = determinismProbes().any { probe ->
            nets[0].predict(probe)[0] != nets[1].predict(probe)[0]
        }
        assertTrue(different, "a different seed must not give the very same model")
    }

    /**
     * The reported validation loss must be a pure fit number.
     *
     * It used to add the L2 weight penalty once for the whole set and then divide by n, so the number moved with the
     * size of the set: with `regularizationLambda = 1.0` on identical rows, n=1 gave 4.1865 and n=50 gave 0.5364.
     */
    @Test
    fun `the reported validation loss does not depend on the validation set size`() {
        val nn = AimiNeuralNetwork(inputSize = 4, hiddenSize = 4, outputSize = 1, regularizationLambda = 1.0)
        val row = FloatArray(4) { 0.5f }
        val target = doubleArrayOf(1.0)

        val lossOne = nn.validate(listOf(row), listOf(target))
        val lossFifty = nn.validate((0 until 50).map { row }, (0 until 50).map { target })

        assertEquals(lossOne, lossFifty, 1e-12, "identical rows must give the same loss for every set size")
        assertTrue(nn.l2WeightPenalty() > 0.0, "the weight penalty is still available on its own, just not in the loss")
    }

    @Test
    fun `a weight file without the schema version is refused`(@TempDir dir: File) {
        val legacy = File(dir, "legacy_weights.json")
        legacy.writeText(
            """
            {"inputSize":2,"hiddenSize":2,"outputSize":1,
             "weightsInputHidden":[[0.1,0.1],[0.1,0.1]],"biasHidden":[0.01,0.01],
             "weightsHiddenOutput":[[0.1],[0.1]],"biasOutput":[0.01]}
            """.trimIndent()
        )
        assertNull(AimiNeuralNetwork.loadFromFile(legacy), "Old schema files must not be loaded")
    }

    @Test
    fun `save and load keeps the model and its normalization`(@TempDir dir: File) {
        val nn = AimiNeuralNetwork(inputSize = 3, hiddenSize = 4, outputSize = 1)
        nn.setInputNormalization(doubleArrayOf(100.0, 0.5, 2.0), doubleArrayOf(40.0, 0.25, 1.0))
        val input = floatArrayOf(120f, 0.75f, 3f)
        val expected = nn.predict(input)[0]

        val file = File(dir, "weights.json")
        nn.saveToFile(file)
        val loaded = AimiNeuralNetwork.loadFromFile(file)
        assertTrue(loaded != null, "A file written by this version must load back")
        assertEquals(expected, loaded!!.predict(input)[0], 1e-9)
    }

    // Synthetic data helpers

    /** Small data set with a real signal on feature 0, big enough to run several batches per epoch. */
    private fun determinismDataSet(): Pair<List<FloatArray>, List<DoubleArray>> {
        val rng = Random(DETERMINISM_DATA_SEED)
        val inputs = ArrayList<FloatArray>(DETERMINISM_ROWS)
        val targets = ArrayList<DoubleArray>(DETERMINISM_ROWS)
        for (i in 0 until DETERMINISM_ROWS) {
            val bg = rng.nextDouble(BG_MIN, BG_MAX)
            inputs.add(syntheticRow(bg, rng))
            targets.add(doubleArrayOf(label(bg)))
        }
        return inputs to targets
    }

    /** A few fixed inputs used to compare two trained models. */
    private fun determinismProbes(): List<FloatArray> {
        val rng = Random(DETERMINISM_PROBE_SEED)
        return (0 until 10).map { syntheticRow(rng.nextDouble(BG_MIN, BG_MAX), rng) }
    }

    private fun label(bg: Double): Double = (1.0 + (bg - 120.0) / 400.0).coerceIn(0.7, 1.5)

    /** One row: feature 0 is glucose, two features are always zero, the rest is noise. */
    private fun syntheticRow(bg: Double, rng: Random): FloatArray = FloatArray(INPUT_SIZE) { i ->
        when {
            i == BG_INDEX            -> bg.toFloat()
            i in CONSTANT_FEATURES   -> 0f
            else                     -> rng.nextDouble(-1.0, 1.0).toFloat()
        }
    }

    /** Mean and standard deviation of every feature, the same statistics the trainer has to feed the network. */
    private fun featureStatistics(rows: List<FloatArray>): Pair<DoubleArray, DoubleArray> {
        val mean = DoubleArray(INPUT_SIZE)
        val std = DoubleArray(INPUT_SIZE)
        for (i in 0 until INPUT_SIZE) {
            var sum = 0.0
            for (row in rows) sum += row[i]
            mean[i] = sum / rows.size
            var sumSq = 0.0
            for (row in rows) {
                val d = row[i] - mean[i]
                sumSq += d * d
            }
            std[i] = sqrt(sumSq / rows.size)
        }
        return mean to std
    }

    private companion object {

        const val INPUT_SIZE = 16
        const val BG_INDEX = 0
        const val ROW_COUNT = 400
        const val BG_MIN = 40.0
        const val BG_MAX = 400.0

        const val DETERMINISM_ROWS = 120
        const val DETERMINISM_DATA_SEED = 20260822L
        const val DETERMINISM_PROBE_SEED = 31337L

        /** Features that stay at zero, to reproduce the denormal weight case of the shipped model. */
        val CONSTANT_FEATURES = setOf(5, 11)
    }
}
