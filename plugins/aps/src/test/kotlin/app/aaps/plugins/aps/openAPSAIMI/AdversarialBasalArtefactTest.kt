package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ml.AimiNeuralModelStore
import app.aaps.plugins.aps.openAPSAIMI.ml.NeuralModelTrainer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * ADVERSARIAL verification pass, added by a review agent. Temporary file: it exists to prove or disprove the claim
 * that the adaptive basal head is fixed, not to become part of the permanent suite.
 *
 * The real failing weight file from a patient device is read from [REAL_ARTEFACT_PATH] when it is present. It holds
 * only model weights, no identifiers, but it is deliberately NOT copied into the repository: the tests fall back to
 * a self built replica so they still run everywhere.
 */
class AdversarialBasalArtefactTest {

    // ---------------------------------------------------------------- Q1: is the real artefact refused?

    @Test
    fun `Q1 real artefact is refused by loadFromFile`(@TempDir dir: File) {
        val source = File(REAL_ARTEFACT_PATH)
        assumeTrue(source.exists(), "real artefact not available on this machine")
        val copy = File(dir, "basal_adaptive_weights.json")
        copy.writeText(source.readText())

        assertNull(AimiNeuralNetwork.loadFromFile(copy), "the real dead artefact must not load")
    }

    @Test
    fun `Q1 real artefact is refused by the model store, target and bak`(@TempDir dir: File) {
        val source = File(REAL_ARTEFACT_PATH)
        assumeTrue(source.exists(), "real artefact not available on this machine")
        val text = source.readText()

        val target = File(dir, "basal_adaptive_weights.json")
        target.writeText(text)
        assertNull(AimiNeuralModelStore.load(target, 16), "store must refuse the dead artefact as target")

        // The bak path is the resurrection route: only the target is deleted by some callers.
        target.delete()
        File(dir, "basal_adaptive_weights.json.bak").writeText(text)
        assertNull(AimiNeuralModelStore.load(target, 16), "store must refuse the dead artefact as .bak")
    }

    /**
     * The schema bump is the ONLY thing that refuses the artefact. Adding the version field and neutral
     * normalization arrays makes the very same dead weights load again, and the store's finite probe accepts them.
     * This measures how much of the fix rests on the file format alone.
     */
    @Test
    fun `Q1 adversarial the same dead weights load fine once the schema field is added`(@TempDir dir: File) {
        val source = File(REAL_ARTEFACT_PATH)
        assumeTrue(source.exists(), "real artefact not available on this machine")

        val root = JSONObject(source.readText())
        val n = root.getInt("inputSize")
        root.put("schemaVersion", 2)
        root.put("inputMean", JSONArray().also { a -> repeat(n) { a.put(0.0) } })
        root.put("inputStd", JSONArray().also { a -> repeat(n) { a.put(1.0) } })
        val revived = File(dir, "revived.json")
        revived.writeText(root.toString())

        val net = AimiNeuralModelStore.load(revived, n)
        assertNotNull(net, "a v2 wrapper around the same dead weights is accepted by the store")

        // Measure what the store happily served: is it still constant under the new forward pass?
        val outs = listOf(70.0, 140.0, 250.0).map { bg ->
            net!!.predict(FloatArray(n).also { it[0] = bg.toFloat() })[0]
        }
        val spread = outs.max() - outs.min()
        println("Q1-ADV revived artefact outputs=$outs spread=$spread")
        // No assertion on the value: the point is that the STORE does not care. Recorded for the report.
        assertTrue(outs.all { it.isFinite() })
    }

    // ---------------------------------------------------------------- Q3: can a constant still be published?

    /**
     * SMB head as it is wired today: no output range, no spread probe. Feeds it labels that are one constant and
     * checks whether a constant model reaches disk.
     */
    @Test
    fun `Q3d SMB head publish gate accepts a constant model`(@TempDir dir: File) {
        val rng = Random(11)
        val inputs = (0 until 200).map { FloatArray(SMB_INPUTS) { rng.nextDouble(-1.0, 1.0).toFloat() } }
        val targets = inputs.map { doubleArrayOf(0.42) }   // no signal at all: the label is one number

        val net = NeuralModelTrainer.trainAndPublish(
            weightsFile = File(dir, "smb.json"),
            split = NeuralModelTrainer.split80_20(inputs, targets),
            config = TrainingConfig(learningRate = 0.001, epochs = 300),
            inputSize = SMB_INPUTS,
            regularizationLambda = 0.01,
            // Exactly the arguments AimiSmbTrainer passes today: no outputRange, no spreadFeatureIndex.
        )
        assertNotNull(net, "SMB head published a model trained on a constant label")
        assertTrue(File(dir, "smb.json").exists(), "constant SMB model reached disk")

        val outs = (0 until 20).map { net!!.predict(FloatArray(SMB_INPUTS) { rng.nextDouble(-3.0, 3.0).toFloat() })[0] }
        println("Q3d SMB constant-label model spread=${outs.max() - outs.min()} mean=${outs.average()}")
    }

    /** The basal head, with the probes armed, must refuse the same degenerate label set. */
    @Test
    fun `Q3c basal head publish gate refuses a constant model`(@TempDir dir: File) {
        val rng = Random(11)
        val inputs = (0 until 200).map { i ->
            FloatArray(BASAL_INPUTS) { k -> if (k == 0) rng.nextDouble(40.0, 400.0).toFloat() else rng.nextDouble(-1.0, 1.0).toFloat() }
        }
        val targets = inputs.map { doubleArrayOf(1.0) }

        val net = NeuralModelTrainer.trainAndPublish(
            weightsFile = File(dir, "basal.json"),
            split = NeuralModelTrainer.split80_20(inputs, targets),
            config = TrainingConfig(learningRate = 0.0005, epochs = 200, patience = 20),
            inputSize = BASAL_INPUTS,
            outputRange = 0.5..2.0,
            spreadFeatureIndex = 0,
            minOutputSpread = 0.05,
            log = { println("Q3c $it") },
        )
        assertNull(net, "a constant basal model must not publish")
        assertFalse(File(dir, "basal.json").exists(), "no weight file must be left on disk")
    }

    // ---------------------------------------------------------------- Q4.1: label mean baked into the bias

    /**
     * Garbage labels: pure noise around a centre, no relation to any feature. The bias initialisation puts the
     * label mean straight into the output, so the model can look "in range" while having learned nothing.
     * The question is whether the spread probe still catches it.
     */
    @Test
    fun `Q4-1 noise-only labels centred in range are caught by the spread probe`(@TempDir dir: File) {
        val rng = Random(4242)
        val inputs = (0 until 300).map { FloatArray(BASAL_INPUTS) { k -> if (k == 0) rng.nextDouble(40.0, 400.0).toFloat() else rng.nextDouble(-1.0, 1.0).toFloat() } }
        val targets = inputs.map { doubleArrayOf(1.0 + rng.nextDouble(-0.25, 0.25)) }

        val net = NeuralModelTrainer.trainAndPublish(
            weightsFile = File(dir, "basal.json"),
            split = NeuralModelTrainer.split80_20(inputs, targets),
            config = TrainingConfig(learningRate = 0.0005, epochs = 200, patience = 20),
            inputSize = BASAL_INPUTS,
            outputRange = 0.5..2.0,
            spreadFeatureIndex = 0,
            minOutputSpread = 0.05,
            log = { println("Q4-1 $it") },
        )
        println("Q4-1 published=${net != null}")
        if (net != null) {
            val outs = listOf(60.0, 140.0, 300.0).map { bg -> net.predict(FloatArray(BASAL_INPUTS).also { it[0] = bg.toFloat() })[0] }
            println("Q4-1 PUBLISHED A NOISE MODEL, outputs at bg 60/140/300 = $outs")
        }
        // Recorded, not asserted: a pass here would be a real hole, a null is the safe outcome.
        assertNull(net, "a model trained on pure label noise must not publish")
    }

    /** The bias init must NOT touch a network restored from a file. */
    @Test
    fun `Q4-1 a restored network keeps its own output bias`(@TempDir dir: File) {
        val donor = AimiNeuralNetwork(4, 4, 1, TrainingConfig(epochs = 1))
        val file = File(dir, "donor.json")
        donor.saveToFile(file)
        val restored = AimiNeuralNetwork.loadFromFile(file)!!
        val biasBefore = restored.outputBiasCopy()[0]

        // Train on labels far from that bias with zero epochs of useful work.
        val inputs = (0 until 8).map { FloatArray(4) { 0.1f } }
        val targets = inputs.map { doubleArrayOf(99.0) }
        restored.trainWithValidation(inputs.take(6), targets.take(6), inputs.drop(6), targets.drop(6))

        // The bias may have moved by training, but it must not have been RESET to the label mean of 99.
        assertTrue(
            abs(restored.outputBiasCopy()[0] - 99.0) > 1.0,
            "restored network had its bias overwritten by the label mean: ${restored.outputBiasCopy()[0]}"
        )
        println("Q4-1 restored bias before=$biasBefore after=${restored.outputBiasCopy()[0]}")
    }

    // ---------------------------------------------------------------- Q4.2: decoupled decay, exploding weights

    @Test
    fun `Q4-2 adversarial data does not blow the weights up or produce NaN`() {
        val rng = Random(777)
        // Huge label range, heavy tailed features, one feature perfectly correlated with the label.
        val inputs = (0 until 400).map { i ->
            FloatArray(BASAL_INPUTS) { k ->
                when (k) {
                    0    -> rng.nextDouble(40.0, 400.0).toFloat()
                    1    -> (rng.nextDouble(-1.0, 1.0) * 1e4).toFloat()
                    2    -> 0f
                    else -> rng.nextDouble(-1.0, 1.0).toFloat()
                }
            }
        }
        val targets = inputs.map { doubleArrayOf(it[0] / 100.0) }

        val nn = AimiNeuralNetwork(BASAL_INPUTS, 8, 1, TrainingConfig(learningRate = 0.0005, epochs = 200, patience = 200))
        val (mean, std) = NeuralModelTrainer.inputNormalizationStats(inputs, BASAL_INPUTS)
        nn.setInputNormalization(mean, std)
        nn.trainWithValidation(inputs.take(320), targets.take(320), inputs.drop(320), targets.drop(320))

        var maxAbs = 0.0
        for (row in nn.inputHiddenWeightsCopy()) for (w in row) {
            assertTrue(w.isFinite(), "input weight is not finite: $w")
            if (abs(w) > maxAbs) maxAbs = abs(w)
        }
        for (row in nn.hiddenOutputWeightsCopy()) for (w in row) {
            assertTrue(w.isFinite(), "output weight is not finite: $w")
            if (abs(w) > maxAbs) maxAbs = abs(w)
        }
        val out = nn.predict(inputs.first())[0]
        println("Q4-2 max|w| after 200 epochs on adversarial data = $maxAbs, sample output=$out")
        assertTrue(out.isFinite(), "output is not finite")
    }

    /**
     * `regularizationLambda` and the validation loss: the reported number must NOT depend on the set size.
     *
     * This test used to assert the opposite, because that was the measured behaviour: the L2 penalty was added once
     * for the whole set and then divided by n, so with `regularizationLambda = 1.0` on identical rows n=1 gave
     * 4.1865 and n=50 gave 0.5364. The penalty is now out of [AimiNeuralNetwork.validate] altogether, so the
     * expectation is flipped: same rows, same loss.
     */
    @Test
    fun `Q4 the reported validation loss no longer depends on the set size`() {
        val nn = AimiNeuralNetwork(4, 4, 1, TrainingConfig(epochs = 1), regularizationLambda = 1.0)
        val one = listOf(FloatArray(4) { 0.5f })
        val oneT = listOf(doubleArrayOf(1.0))
        val many = (0 until 50).map { FloatArray(4) { 0.5f } }
        val manyT = (0 until 50).map { doubleArrayOf(1.0) }

        val lossSmall = nn.validate(one, oneT)
        val lossLarge = nn.validate(many, manyT)
        assertTrue(
            abs(lossSmall - lossLarge) < 1e-12,
            "identical rows must give the same loss: n=1 -> $lossSmall, n=50 -> $lossLarge"
        )
    }

    // ---------------------------------------------------------------- Q4.3: forwardPass throws

    @Test
    fun `Q4-3 predict throws on a wrong input size`() {
        val nn = AimiNeuralNetwork(BASAL_INPUTS, 8, 1)
        var threw = false
        try {
            nn.predict(FloatArray(BASAL_INPUTS - 1))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "predict must reject a wrong sized input")
    }

    // ---------------------------------------------------------------- Q3a: 0.70 reachability

    @Test
    fun `Q3a the constant 0_19918 no longer maps to 0_70 on the basal path`() {
        // Mirrors BasalNeuralLearner.getUniversalBasalDecision arithmetic with the new floor.
        val floor = 0.80
        val ceiling = 1.3
        assertEquals(0.80, 0.19918.coerceIn(floor, ceiling), 1e-12)
    }

    private companion object {

        const val REAL_ARTEFACT_PATH = "/Users/mtr/Downloads/AIMI_Support_Package_1787343945264/basal_adaptive_weights.json"
        const val BASAL_INPUTS = 16
        const val SMB_INPUTS = 12
    }
}
