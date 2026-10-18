package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * The basal head shipped a model on 12 July 2026 that answers the SAME number (0.19918) for every input, and it
 * then blocked every successor for 40 days. Three holes let that happen and keep happening:
 *
 * 1. the bootstrap run skipped the output-range probe, so the first model published unchecked;
 * 2. the probe was a single averaged vector, which a constant model passes for free;
 * 3. the dead incumbent stayed on disk and gated every candidate by validation loss.
 *
 * These tests close all three. A fourth hole is covered here too: the spread probe swept the p10 .. p90 of the
 * training bg column, so it measured a different window on every patient — and it does not separate a model that
 * learned from a model that fitted noise at all, which is what the held-out check against the median label is for.
 */
class NeuralModelPublishGateTest {

    @TempDir
    lateinit var dir: File

    private val inputSize = 16
    private val bgIndex = 0
    private val minSpread = 0.05
    private val basalRange = 0.5..2.0

    /** The fixed clinical window both the publish gate and the runtime health probe sweep. */
    private val anchors = BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL

    /** Training rows shaped like the basal schema: bg first, then basal, accel, duraMin, duraAvg, iob, physio. */
    private fun basalRows(count: Int = 200): List<FloatArray> =
        (0 until count).map { i ->
            val bg = 80f + (220f * i / (count - 1))
            FloatArray(inputSize) { f ->
                when (f) {
                    bgIndex -> bg
                    1       -> 1.0f
                    2       -> 0.1f
                    3       -> 30f
                    4       -> 45f
                    5       -> 1.5f
                    else    -> 0.5f
                }
            }
        }

    /** Basal scale that really depends on bg, inside the accepted publish range. */
    private fun basalTargets(rows: List<FloatArray>): List<DoubleArray> =
        rows.map { doubleArrayOf((0.7 + (it[bgIndex] - 80f) / 220f * 0.8).coerceIn(0.5, 2.0)) }

    private fun constantTargets(rows: List<FloatArray>, value: Double): List<DoubleArray> =
        rows.map { doubleArrayOf(value) }

    private fun probes(rows: List<FloatArray>) =
        NeuralModelTrainer.spreadProbeVectors(rows, inputSize, bgIndex, anchors)

    // ---------------------------------------------------------------- G3: the spread probe

    @Test
    fun `a constant model is rejected by the spread probe`() {
        val rows = basalRows()
        val lines = mutableListOf<String>()

        val ok = NeuralModelTrainer.passesPublishProbes(
            predict = { doubleArrayOf(1.0) },   // in range, and identical for every input
            label = "candidate",
            inputSize = inputSize,
            probeInput = rows.first(),
            outputRange = basalRange,
            spreadProbes = probes(rows),
            minOutputSpread = minSpread,
            log = { lines += it },
        )

        assertThat(ok).isFalse()
        assertThat(lines.last()).contains("spread 0.0")
    }

    @Test
    fun `the real degenerate artefact is rejected by four orders of magnitude`() {
        // The shipped file answers 0.19918 everywhere; its largest response to ANY feature over its full range is
        // 2.7e-06. That is non-zero, so a naive "is it constant?" test passes it. It is still a constant in practice.
        val rows = basalRows()
        val artefact: (FloatArray) -> DoubleArray = { input ->
            doubleArrayOf(0.19918 + 2.7e-6 * ((input[bgIndex] - 40f) / 360f))
        }
        val lines = mutableListOf<String>()

        val ok = NeuralModelTrainer.passesPublishProbes(
            predict = artefact,
            label = "candidate",
            inputSize = inputSize,
            probeInput = rows.first(),
            outputRange = 0.0..1.0,             // range deliberately wide enough to let 0.19918 through
            spreadProbes = probes(rows),
            minOutputSpread = minSpread,
            log = { lines += it },
        )

        assertThat(ok).isFalse()
        val measured = probes(rows).map { artefact(it)[0] }
        val spread = measured.max() - measured.min()
        assertThat(spread).isGreaterThan(0.0)                 // a naive non-constant test would pass it
        assertThat(spread).isLessThan(minSpread / 1000.0)     // the actionable threshold rejects it by >1000x
    }

    @Test
    fun `a model with a real bg response passes`() {
        val rows = basalRows()

        val ok = NeuralModelTrainer.passesPublishProbes(
            predict = { input -> doubleArrayOf(0.7 + (input[bgIndex] - 80f) / 220f * 0.8) },
            label = "candidate",
            inputSize = inputSize,
            probeInput = rows.first(),
            outputRange = basalRange,
            spreadProbes = probes(rows),
            minOutputSpread = minSpread,
        )

        assertThat(ok).isTrue()
    }

    @Test
    fun `spread probes move one axis only and hold the rest at the training median`() {
        val rows = basalRows()

        val vectors = probes(rows)

        assertThat(vectors).hasSize(anchors.size)
        // bg really moves
        assertThat(vectors[2][bgIndex]).isGreaterThan(vectors[0][bgIndex])
        // every other feature is identical across the three vectors
        for (f in 0 until inputSize) {
            if (f == bgIndex) continue
            assertThat(vectors[1][f]).isEqualTo(vectors[0][f])
            assertThat(vectors[2][f]).isEqualTo(vectors[0][f])
        }
    }

    /**
     * The window is FIXED, not taken from the data. The rows here span bg 80 .. 300, so their own p10 and p90 are
     * nowhere near the anchors: a per-patient window would move with the training set and mean a different thing on
     * every patient, and it is narrowest for a well-controlled one.
     */
    @Test
    fun `the spread probe sweeps the fixed clinical anchors and not the training percentiles`() {
        val rows = basalRows()

        val fixed = probes(rows).map { it[bgIndex].toDouble() }
        val perPatient = NeuralModelTrainer.spreadProbeVectors(rows, inputSize, bgIndex).map { it[bgIndex].toDouble() }

        assertThat(fixed).isEqualTo(anchors)
        assertThat(perPatient).isNotEqualTo(anchors)
        // The per-patient window really is the narrower one on this training set.
        assertThat(perPatient.max() - perPatient.min()).isLessThan(anchors.max() - anchors.min())
    }

    @Test
    fun `spread probes are empty when the feature index cannot be used`() {
        val rows = basalRows()

        assertThat(NeuralModelTrainer.spreadProbeVectors(rows, inputSize, null, anchors)).isEmpty()
        assertThat(NeuralModelTrainer.spreadProbeVectors(rows, inputSize, 99, anchors)).isEmpty()
        assertThat(NeuralModelTrainer.spreadProbeVectors(emptyList(), inputSize, bgIndex, anchors)).isEmpty()
    }

    /**
     * With the spread gate armed and no fixed sweep values, nothing is published. The old code fell back to the
     * training percentiles here, which is exactly the per-patient window this change removes.
     */
    @Test
    fun `the publish gate refuses to run the spread check without fixed sweep values`() {
        val rows = basalRows()
        val weights = File(dir, "basal_adaptive_weights.json")
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            weightsFile = weights,
            split = NeuralModelTrainer.split80_20(rows, basalTargets(rows)),
            config = TrainingConfig(learningRate = 0.01, epochs = 200, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,
            probeInput = rows.first(),
            spreadFeatureIndex = bgIndex,
            minOutputSpread = minSpread,
            log = { lines += it },
        )

        assertThat(published).isNull()
        assertThat(weights.exists()).isFalse()
        assertThat(lines.any { it.contains("no fixed sweep values") }).isTrue()
    }

    // ---------------------------------------------------------------- G6: beat the best constant predictor

    /**
     * The held-out check that actually separates a model that learned from one that fitted noise. The spread probe
     * cannot: measured over 10 seeds of patient-shaped data, the noise models move MORE across the anchors
     * (0.159 .. 0.208) than the models that learned (0.073 .. 0.143). See `AdversarialBasalLearningTest`.
     */
    @Test
    fun `the held out error is measured against the median training label`() {
        val rows = basalRows(10)
        val targets = rows.map { doubleArrayOf(if (it[bgIndex] < 200f) 1.0 else 1.4) }
        val split = NeuralModelTrainer.split80_20(rows, targets)

        // A predictor that answers the median label scores exactly the baseline.
        val (medianMae, baseline) = NeuralModelTrainer.heldOutMaeAgainstConstant({ doubleArrayOf(1.0) }, split)
        assertThat(medianMae).isWithin(1e-9).of(baseline)

        // A perfect predictor scores zero.
        val (perfect, _) = NeuralModelTrainer.heldOutMaeAgainstConstant(
            { input -> doubleArrayOf(if (input[bgIndex] < 200f) 1.0 else 1.4) }, split
        )
        assertThat(perfect).isWithin(1e-9).of(0.0)
        assertThat(baseline).isGreaterThan(0.0)
    }

    @Test
    fun `a candidate that cannot beat the median label is not published`() {
        // Labels that no model can predict from these features: a fixed pseudo-random value per row. The spread
        // probe lets this through (a noise-fitted model has a large apparent bg slope), so this gate is the only
        // thing standing between it and the pump.
        val rows = basalRows()
        val rng = Random(7)
        val targets = rows.map { doubleArrayOf(1.0 + rng.nextDouble(-0.2, 0.2)) }
        val weights = File(dir, "basal_adaptive_weights.json")
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            weightsFile = weights,
            split = NeuralModelTrainer.split80_20(rows, targets),
            config = TrainingConfig(learningRate = 0.0005, epochs = 200, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,
            probeInput = rows.first(),
            spreadFeatureIndex = bgIndex,
            spreadSweepValues = anchors,
            minOutputSpread = minSpread,
            maxBaselineErrorRatio = 0.95,
            log = { lines += it },
        )

        assertThat(published).isNull()
        assertThat(weights.exists()).isFalse()
        assertThat(lines.any { it.contains("does not beat the median label") }).isTrue()
    }

    @Test
    fun `one single label value is refused instead of being called a win`() {
        // Every label the same number: the baseline error is 0, so "better than the baseline" is impossible. The
        // old code had no opinion here, which is how a constant model could look like a fitted one.
        val rows = basalRows()
        val weights = File(dir, "basal_adaptive_weights.json")
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            weightsFile = weights,
            split = NeuralModelTrainer.split80_20(rows, constantTargets(rows, 1.0)),
            config = TrainingConfig(learningRate = 0.01, epochs = 50, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,
            probeInput = rows.first(),
            maxBaselineErrorRatio = 0.95,
            log = { lines += it },
        )

        assertThat(published).isNull()
        assertThat(lines.any { it.contains("nothing to learn") }).isTrue()
    }

    @Test
    fun `a candidate that beats the median label publishes`() {
        val rows = basalRows()
        val weights = File(dir, "basal_adaptive_weights.json")
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            weightsFile = weights,
            split = NeuralModelTrainer.split80_20(rows, basalTargets(rows)),
            config = TrainingConfig(learningRate = 0.01, epochs = 200, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,
            probeInput = rows.first(),
            spreadFeatureIndex = bgIndex,
            spreadSweepValues = anchors,
            minOutputSpread = minSpread,
            maxBaselineErrorRatio = 0.95,
            log = { lines += it },
        )

        assertThat(published).isNotNull()
        assertThat(lines.any { it.contains("published") }).isTrue()
    }

    // ---------------------------------------------------------------- G2: the range probe on bootstrap

    @Test
    fun `the range probe fires on the bootstrap path when there is no incumbent`() {
        val rows = basalRows()
        val weights = File(dir, "basal_adaptive_weights.json")
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            weightsFile = weights,
            split = NeuralModelTrainer.split80_20(rows, constantTargets(rows, 0.19918)),
            config = TrainingConfig(learningRate = 0.01, epochs = 200, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,                 // 0.5..2.0 — the label 0.19918 can never satisfy it
            probeInput = rows.first(),
            requireIncumbentBeat = false,             // bootstrap: no model on disk yet
            log = { lines += it },
        )

        assertThat(published).isNull()
        assertThat(weights.exists()).isFalse()        // nothing published is a valid, safe outcome
        assertThat(lines.any { it.contains("outside") }).isTrue()
    }

    // ---------------------------------------------------------------- G4: a dead incumbent must not block

    @Test
    fun `a degenerate incumbent does not block a healthy candidate and is removed`() {
        val rows = basalRows()
        val weights = File(dir, "basal_adaptive_weights.json")
        writeConstantModel(weights, 0.19918)
        // The incumbent is served today, constant for every input.
        assertThat(AimiNeuralModelStore.load(weights, inputSize)!!.predict(rows.last())[0])
            .isWithin(1e-9).of(0.19918)
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            weightsFile = weights,
            split = NeuralModelTrainer.split80_20(rows, basalTargets(rows)),
            config = TrainingConfig(learningRate = 0.01, epochs = 200, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,
            probeInput = rows.first(),
            spreadFeatureIndex = bgIndex,
            spreadSweepValues = anchors,
            minOutputSpread = minSpread,
            requireIncumbentBeat = true,
            valLossTolerance = 1.05,
            log = { lines += it },
        )

        // The dead incumbent was named and removed...
        assertThat(lines.any { it.contains("is dead") }).isTrue()
        // ...its validation loss never gated the candidate...
        assertThat(lines.any { it.contains("> incumbent=") }).isFalse()
        // ...so the healthy candidate took its place. Before the fix this was impossible: the constant model beat
        // every candidate on validation loss and stayed forever.
        assertThat(published).isNotNull()
        val served = AimiNeuralModelStore.load(weights, inputSize)!!
        assertThat(abs(served.predict(rows.last())[0] - 0.19918)).isGreaterThan(1e-6)
        val response = probes(rows).map { served.predict(it)[0] }
        assertThat(response.max() - response.min()).isAtLeast(minSpread)
    }

    @Test
    fun `a candidate that fails the probes is not published even when the incumbent is dead`() {
        val rows = basalRows()
        val weights = File(dir, "basal_adaptive_weights.json")
        writeConstantModel(weights, 0.19918)
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            weightsFile = weights,
            split = NeuralModelTrainer.split80_20(rows, constantTargets(rows, 0.19918)),
            config = TrainingConfig(learningRate = 0.01, epochs = 200, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,
            probeInput = rows.first(),
            spreadFeatureIndex = bgIndex,
            spreadSweepValues = anchors,
            minOutputSpread = minSpread,
            requireIncumbentBeat = true,
            valLossTolerance = 1.05,
            log = { lines += it },
        )

        assertThat(published).isNull()
        assertThat(lines.any { it.contains("is dead") }).isTrue()
        // No hole: the replacement had to pass the probes too, so we now serve no model at all.
        assertThat(AimiNeuralModelStore.load(weights, inputSize)).isNull()
    }

    // ---------------------------------------------------------------- G5: the backup must not resurrect

    @Test
    fun `load falls back to the backup and delete removes both`() {
        val target = File(dir, "weights.json")
        val bak = File(dir, "weights.json.bak")
        writeConstantModel(bak, 0.8)

        assertThat(target.exists()).isFalse()
        assertThat(AimiNeuralModelStore.load(target, inputSize)).isNotNull()   // served from the backup

        assertThat(AimiNeuralModelStore.delete(target)).isTrue()
        assertThat(bak.exists()).isFalse()
        assertThat(AimiNeuralModelStore.load(target, inputSize)).isNull()      // cannot come back
    }

    @Test
    fun `a file that cannot be read does not crash the caller`() {
        val target = File(dir, "weights.json")
        target.writeText("this is not json")
        writeConstantModel(File(dir, "weights.json.bak"), 0.9)

        // Unreadable target → the backup is used instead of an exception.
        assertThat(AimiNeuralModelStore.load(target, inputSize)).isNotNull()

        File(dir, "weights.json.bak").writeText("{\"inputSize\":3}")           // schema-incomplete
        assertThat(AimiNeuralModelStore.load(target, inputSize)).isNull()
    }

    // ---------------------------------------------------------------- G1: input normalization stats

    @Test
    fun `normalization stats give the per feature mean and standard deviation`() {
        val rows = listOf(
            floatArrayOf(100f, 2f),
            floatArrayOf(200f, 2f),
            floatArrayOf(300f, 2f),
        )

        val (mean, std) = NeuralModelTrainer.inputNormalizationStats(rows, 2)

        assertThat(mean[0]).isWithin(1e-9).of(200.0)
        assertThat(std[0]).isWithin(1e-6).of(81.649658)     // population sd of 100/200/300
        assertThat(mean[1]).isWithin(1e-9).of(2.0)
        assertThat(std[1]).isEqualTo(1.0)                    // constant column → identity, never a divide by zero
    }

    @Test
    fun `normalization stats are safe on an empty or malformed train set`() {
        val (mean, std) = NeuralModelTrainer.inputNormalizationStats(emptyList(), 3)
        assertThat(mean.toList()).containsExactly(0.0, 0.0, 0.0)
        assertThat(std.toList()).containsExactly(1.0, 1.0, 1.0)

        val (m2, s2) = NeuralModelTrainer.inputNormalizationStats(listOf(floatArrayOf(1f)), 3)
        assertThat(m2.toList()).containsExactly(0.0, 0.0, 0.0)   // wrong row length is skipped
        assertThat(s2.toList()).containsExactly(1.0, 1.0, 1.0)
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Writes the exact shape of the shipped dead model: all input→hidden weights zero and identical hidden biases,
     * so layer normalization flattens the hidden layer and the output is always the output bias.
     */
    private fun writeConstantModel(file: File, value: Double) {
        val hiddenSize = 8
        fun matrix(rowCount: Int, colCount: Int, v: Double) = JSONArray().apply {
            repeat(rowCount) { put(JSONArray().apply { repeat(colCount) { put(v) } }) }
        }

        fun vector(size: Int, v: Double) = JSONArray().apply { repeat(size) { put(v) } }

        val root = JSONObject()
            .put("schemaVersion", AimiNeuralNetwork.SCHEMA_VERSION)
            .put("inputSize", inputSize)
            .put("hiddenSize", hiddenSize)
            .put("outputSize", 1)
            .put("weightsInputHidden", matrix(inputSize, hiddenSize, 0.0))
            .put("biasHidden", vector(hiddenSize, 0.01))
            .put("weightsHiddenOutput", matrix(hiddenSize, 1, 0.0))
            .put("biasOutput", vector(1, value))
            .put("inputMean", vector(inputSize, 0.0))
            .put("inputStd", vector(inputSize, 1.0))
        file.parentFile?.mkdirs()
        file.writeText(root.toString())

        // Guard the fixture itself: it must really be a constant.
        val net = AimiNeuralNetwork.loadFromFile(file)!!
        assertThat(net.predict(FloatArray(inputSize) { 400f })[0]).isWithin(1e-12).of(value)
    }

    // --- SMB head -------------------------------------------------------------------------------
    //
    // The SMB head ran with every liveness gate off. Measured before this change: trained on the single
    // constant label 0.42 with the head's own arguments, it PUBLISHED, and its answer moved 0.0125 U over
    // random inputs. `refine` only ever moves a dose by min(0.05 U, 25 %), so such a model cannot steer
    // anything — it can only add the same offset to every dose.

    private val smbInputSize = SmbRefinementFeatureSchema.INPUT_SIZE

    /** Rows shaped like the SMB schema: bg first, the rest held at plain values. */
    private fun smbRows(count: Int = 200): List<FloatArray> =
        (0 until count).map { i ->
            val bg = 80f + (220f * i / (count - 1))
            FloatArray(smbInputSize) { f -> if (f == bgIndex) bg else 0.5f }
        }

    private fun smbGate(
        rows: List<FloatArray>,
        targets: List<DoubleArray>,
        file: File,
        log: (String) -> Unit = {},
    ): AimiNeuralNetwork? = NeuralModelTrainer.trainAndPublish(
        weightsFile = file,
        split = NeuralModelTrainer.split80_20(rows, targets),
        config = TrainingConfig(learningRate = 0.001, epochs = 300),
        inputSize = smbInputSize,
        regularizationLambda = 0.01,
        outputRange = 0.0..5.0,
        spreadFeatureIndex = bgIndex,
        spreadSweepValues = anchors,
        minOutputSpread = 0.05,
        maxBaselineErrorRatio = 0.95,
        log = log,
    )

    @Test
    fun `the smb head refuses a constant model`() {
        val rows = smbRows()
        val constant = rows.map { doubleArrayOf(0.42) }
        val file = File(dir, "smb_constant.json")

        val published = smbGate(rows, constant, file)

        assertThat(published).isNull()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `the smb head publishes a model whose dose follows bg`() {
        val rows = smbRows()
        // A dose that really depends on bg, spanning far more than the 0.05 U runtime correction clamp.
        val targets = rows.map { doubleArrayOf(0.20 + (it[bgIndex] - 80f) / 220f * 1.60) }
        val file = File(dir, "smb_live.json")

        val notes = mutableListOf<String>()
        val published = smbGate(rows, targets, file) { notes += it }

        assertWithMessage("gate notes: $notes").that(published).isNotNull()
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `the smb head sweeps the same clinical anchors as the basal head`() {
        // One shared anchor list. If a head sweeps its own window, the publish gate and the runtime probe
        // can disagree, and the app can publish a model its own loader then refuses.
        assertThat(anchors).isEqualTo(BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL)
        assertThat(anchors).containsExactly(70.0, 140.0, 250.0).inOrder()
    }
}
