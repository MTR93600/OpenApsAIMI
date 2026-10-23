package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The basal head shipped a model on 12 July 2026 that answers the SAME number (0.19918) for every input, and it
 * then blocked every successor for 40 days. Three holes let that happen and keep happening:
 *
 * 1. the bootstrap run skipped the output-range probe, so the first model published unchecked;
 * 2. the probe was a single averaged vector, which a constant model passes for free;
 * 3. the dead incumbent stayed on disk and gated every candidate by validation loss.
 *
 * These tests close all three. A fourth hole is covered too: the spread probe swept the p10 .. p90 of the
 * training bg column, so it measured a different window on every patient - and it does not separate a model that
 * learned from a model that fitted noise at all, which is what the held-out check against the median label is for.
 *
 * Port of `NeuralModelPublishGateTest` from `dev_OAPSAIMI`. What changed:
 * - `java.io.File` and `@TempDir` are replaced by [InMemoryAimiStorage] and an [AimiPath], because
 *   `NeuralModelTrainer.trainAndPublish` and [AimiNeuralModelStore] take an `AimiStorage` here.
 * - `org.json` in the fixture is replaced by kotlinx serialization.
 * - Truth is replaced by `kotlin.test`, names are camelCase, so the test runs on every target.
 * The production code under test is the same as on dev apart from the storage seam.
 */
class NeuralModelPublishGateTest {

    private val inputSize = 16
    private val bgIndex = 0
    private val minSpread = 0.05
    private val basalRange = 0.5..2.0

    private val storage = InMemoryAimiStorage()
    private val weights: AimiPath = storage.file("basal_adaptive_weights.json")

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

    private fun assertNear(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected +- $tolerance but was $actual")
    }

    // ---------------------------------------------------------------- G3: the spread probe

    @Test
    fun constantModelIsRejectedBySpreadProbe() {
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

        assertFalse(ok)
        assertTrue(lines.last().contains("spread 0.0"), lines.last())
    }

    @Test
    fun realDegenerateArtefactIsRejectedByFourOrdersOfMagnitude() {
        // The shipped file answers 0.19918 everywhere; its largest response to ANY feature over its full range is
        // 2.7e-06. That is not zero, so a naive "is it constant?" test passes it. It is still a constant in practice.
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
            outputRange = 0.0..1.0,             // range wide enough on purpose to let 0.19918 through
            spreadProbes = probes(rows),
            minOutputSpread = minSpread,
            log = { lines += it },
        )

        assertFalse(ok)
        val measured = probes(rows).map { artefact(it)[0] }
        val spread = measured.max() - measured.min()
        assertTrue(spread > 0.0)                    // a naive non-constant test would pass it
        assertTrue(spread < minSpread / 1000.0)     // the real threshold rejects it by more than 1000x
    }

    @Test
    fun modelWithRealBgResponsePasses() {
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

        assertTrue(ok)
    }

    @Test
    fun spreadProbesMoveOneAxisOnlyAndHoldTheRestAtTheTrainingMedian() {
        val rows = basalRows()

        val vectors = probes(rows)

        assertEquals(anchors.size, vectors.size)
        // bg really moves
        assertTrue(vectors[2][bgIndex] > vectors[0][bgIndex])
        // every other feature is the same across the three vectors
        for (f in 0 until inputSize) {
            if (f == bgIndex) continue
            assertEquals(vectors[0][f], vectors[1][f])
            assertEquals(vectors[0][f], vectors[2][f])
        }
    }

    /**
     * The window is FIXED, not taken from the data. The rows here span bg 80 .. 300, so their own p10 and p90 are
     * nowhere near the anchors: a per-patient window would move with the training set and mean a different thing on
     * every patient, and it is narrowest for a well-controlled one.
     */
    @Test
    fun spreadProbeSweepsTheFixedClinicalAnchorsAndNotTheTrainingPercentiles() {
        val rows = basalRows()

        val fixed = probes(rows).map { it[bgIndex].toDouble() }
        val perPatient = NeuralModelTrainer.spreadProbeVectors(rows, inputSize, bgIndex).map { it[bgIndex].toDouble() }

        assertEquals(anchors, fixed)
        assertNotEquals(anchors, perPatient)
        // The per-patient window really is the narrower one on this training set.
        assertTrue(perPatient.max() - perPatient.min() < anchors.max() - anchors.min())
    }

    @Test
    fun spreadProbesAreEmptyWhenTheFeatureIndexCannotBeUsed() {
        val rows = basalRows()

        assertTrue(NeuralModelTrainer.spreadProbeVectors(rows, inputSize, null, anchors).isEmpty())
        assertTrue(NeuralModelTrainer.spreadProbeVectors(rows, inputSize, 99, anchors).isEmpty())
        assertTrue(NeuralModelTrainer.spreadProbeVectors(emptyList(), inputSize, bgIndex, anchors).isEmpty())
    }

    /**
     * With the spread gate armed and no fixed sweep values, nothing is published. The old code fell back to the
     * training percentiles here, which is exactly the per-patient window this change removes.
     */
    @Test
    fun publishGateRefusesToRunTheSpreadCheckWithoutFixedSweepValues() {
        val rows = basalRows()
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            storage = storage,
            weightsPath = weights,
            split = NeuralModelTrainer.split80_20(rows, basalTargets(rows)),
            config = TrainingConfig(learningRate = 0.01, epochs = 200, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,
            probeInput = rows.first(),
            spreadFeatureIndex = bgIndex,
            minOutputSpread = minSpread,
            log = { lines += it },
        )

        assertNull(published)
        assertFalse(storage.exists(weights))
        assertTrue(lines.any { it.contains("no fixed sweep values") }, lines.toString())
    }

    // ---------------------------------------------------------------- G6: beat the best constant predictor

    /**
     * The held-out check that really separates a model that learned from one that fitted noise. The spread probe
     * cannot: measured over 10 seeds of patient-shaped data, the noise models move MORE across the anchors
     * (0.159 .. 0.208) than the models that learned (0.073 .. 0.143).
     */
    @Test
    fun heldOutErrorIsMeasuredAgainstTheMedianTrainingLabel() {
        val rows = basalRows(10)
        val targets = rows.map { doubleArrayOf(if (it[bgIndex] < 200f) 1.0 else 1.4) }
        val split = NeuralModelTrainer.split80_20(rows, targets)

        // A predictor that answers the median label scores exactly the baseline.
        val (medianMae, baseline) = NeuralModelTrainer.heldOutMaeAgainstConstant({ doubleArrayOf(1.0) }, split)
        assertNear(baseline, medianMae, 1e-9)

        // A perfect predictor scores zero.
        val (perfect, _) = NeuralModelTrainer.heldOutMaeAgainstConstant(
            { input -> doubleArrayOf(if (input[bgIndex] < 200f) 1.0 else 1.4) }, split
        )
        assertNear(0.0, perfect, 1e-9)
        assertTrue(baseline > 0.0)
    }

    @Test
    fun candidateThatCannotBeatTheMedianLabelIsNotPublished() {
        // Labels that no model can predict from these features: a fixed pseudo-random value per row. The spread
        // probe lets this through (a noise-fitted model has a large apparent bg slope), so this gate is the only
        // thing standing between it and the pump.
        val rows = basalRows()
        val rng = Random(7)
        val targets = rows.map { doubleArrayOf(1.0 + rng.nextDouble(-0.2, 0.2)) }
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            storage = storage,
            weightsPath = weights,
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

        assertNull(published)
        assertFalse(storage.exists(weights))
        assertTrue(lines.any { it.contains("does not beat the median label") }, lines.toString())
    }

    @Test
    fun oneSingleLabelValueIsRefusedInsteadOfBeingCalledAWin() {
        // Every label the same number: the baseline error is 0, so "better than the baseline" is impossible. The
        // old code had no opinion here, which is how a constant model could look like a fitted one.
        val rows = basalRows()
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            storage = storage,
            weightsPath = weights,
            split = NeuralModelTrainer.split80_20(rows, constantTargets(rows, 1.0)),
            config = TrainingConfig(learningRate = 0.01, epochs = 50, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,
            probeInput = rows.first(),
            maxBaselineErrorRatio = 0.95,
            log = { lines += it },
        )

        assertNull(published)
        assertTrue(lines.any { it.contains("nothing to learn") }, lines.toString())
    }

    @Test
    fun candidateThatBeatsTheMedianLabelPublishes() {
        val rows = basalRows()
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            storage = storage,
            weightsPath = weights,
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

        assertNotNull(published, lines.toString())
        assertTrue(lines.any { it.contains("published") }, lines.toString())
    }

    // ---------------------------------------------------------------- G2: the range probe on bootstrap

    @Test
    fun rangeProbeFiresOnTheBootstrapPathWhenThereIsNoIncumbent() {
        val rows = basalRows()
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            storage = storage,
            weightsPath = weights,
            split = NeuralModelTrainer.split80_20(rows, constantTargets(rows, 0.19918)),
            config = TrainingConfig(learningRate = 0.01, epochs = 200, patience = 20),
            inputSize = inputSize,
            outputRange = basalRange,                 // 0.5..2.0 - the label 0.19918 can never be inside it
            probeInput = rows.first(),
            requireIncumbentBeat = false,             // bootstrap: no model stored yet
            log = { lines += it },
        )

        assertNull(published)
        assertFalse(storage.exists(weights))          // nothing published is a valid, safe outcome
        assertTrue(lines.any { it.contains("outside") }, lines.toString())
    }

    // ---------------------------------------------------------------- G4: a dead incumbent must not block

    @Test
    fun degenerateIncumbentDoesNotBlockAHealthyCandidateAndIsRemoved() {
        val rows = basalRows()
        writeConstantModel(weights, 0.19918)
        // The incumbent is served today, constant for every input.
        val incumbent = AimiNeuralModelStore.load(storage, weights, inputSize)
        assertNotNull(incumbent)
        assertNear(0.19918, incumbent.predict(rows.last())[0], 1e-9)
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            storage = storage,
            weightsPath = weights,
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
        assertTrue(lines.any { it.contains("is dead") }, lines.toString())
        // ...its validation loss never gated the candidate...
        assertFalse(lines.any { it.contains("> incumbent=") }, lines.toString())
        // ...so the healthy candidate took its place. Before the fix this was impossible: the constant model beat
        // every candidate on validation loss and stayed forever.
        assertNotNull(published, lines.toString())
        val served = AimiNeuralModelStore.load(storage, weights, inputSize)
        assertNotNull(served)
        assertTrue(abs(served.predict(rows.last())[0] - 0.19918) > 1e-6)
        val response = probes(rows).map { served.predict(it)[0] }
        assertTrue(response.max() - response.min() >= minSpread)
    }

    @Test
    fun candidateThatFailsTheProbesIsNotPublishedEvenWhenTheIncumbentIsDead() {
        val rows = basalRows()
        writeConstantModel(weights, 0.19918)
        val lines = mutableListOf<String>()

        val published = NeuralModelTrainer.trainAndPublish(
            storage = storage,
            weightsPath = weights,
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

        assertNull(published)
        assertTrue(lines.any { it.contains("is dead") }, lines.toString())
        // No hole: the replacement had to pass the probes too, so we now serve no model at all.
        assertNull(AimiNeuralModelStore.load(storage, weights, inputSize))
    }

    // ---------------------------------------------------------------- G5: the backup must not come back

    @Test
    fun loadFallsBackToTheBackupAndDeleteRemovesBoth() {
        val target = storage.file("weights.json")
        val bak = storage.sibling(target, ".bak")
        writeConstantModel(bak, 0.8)

        assertFalse(storage.exists(target))
        assertNotNull(AimiNeuralModelStore.load(storage, target, inputSize))   // served from the backup

        assertTrue(AimiNeuralModelStore.delete(storage, target))
        assertFalse(storage.exists(bak))
        assertNull(AimiNeuralModelStore.load(storage, target, inputSize))      // cannot come back
    }

    @Test
    fun fileThatCannotBeReadDoesNotCrashTheCaller() {
        val target = storage.file("weights.json")
        val bak = storage.sibling(target, ".bak")
        storage.writeText(target, "this is not json")
        writeConstantModel(bak, 0.9)

        // Unreadable target -> the backup is used instead of an exception.
        assertNotNull(AimiNeuralModelStore.load(storage, target, inputSize))

        storage.writeText(bak, "{\"inputSize\":3}")                            // schema not complete
        assertNull(AimiNeuralModelStore.load(storage, target, inputSize))
    }

    // ---------------------------------------------------------------- G1: input normalization stats

    @Test
    fun normalizationStatsGiveThePerFeatureMeanAndStandardDeviation() {
        val rows = listOf(
            floatArrayOf(100f, 2f),
            floatArrayOf(200f, 2f),
            floatArrayOf(300f, 2f),
        )

        val (mean, std) = NeuralModelTrainer.inputNormalizationStats(rows, 2)

        assertNear(200.0, mean[0], 1e-9)
        assertNear(81.649658, std[0], 1e-6)     // population sd of 100/200/300
        assertNear(2.0, mean[1], 1e-9)
        assertEquals(1.0, std[1])               // constant column -> identity, never a divide by zero
    }

    @Test
    fun normalizationStatsAreSafeOnAnEmptyOrMalformedTrainSet() {
        val (mean, std) = NeuralModelTrainer.inputNormalizationStats(emptyList(), 3)
        assertEquals(listOf(0.0, 0.0, 0.0), mean.toList())
        assertEquals(listOf(1.0, 1.0, 1.0), std.toList())

        val (m2, s2) = NeuralModelTrainer.inputNormalizationStats(listOf(floatArrayOf(1f)), 3)
        assertEquals(listOf(0.0, 0.0, 0.0), m2.toList())   // wrong row length is skipped
        assertEquals(listOf(1.0, 1.0, 1.0), s2.toList())
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Writes the exact shape of the shipped dead model: all input to hidden weights zero, identical hidden biases
     * and zero hidden to output weights, so the output is always the output bias.
     */
    private fun writeConstantModel(path: AimiPath, value: Double) {
        val hiddenSize = 8
        fun vector(size: Int, v: Double) = JsonArray(List(size) { JsonPrimitive(v) })
        fun matrix(rowCount: Int, colCount: Int, v: Double) = JsonArray(List(rowCount) { vector(colCount, v) })

        val root = buildJsonObject {
            put("schemaVersion", AimiNeuralNetwork.SCHEMA_VERSION)
            put("inputSize", inputSize)
            put("hiddenSize", hiddenSize)
            put("outputSize", 1)
            put("weightsInputHidden", matrix(inputSize, hiddenSize, 0.0))
            put("biasHidden", vector(hiddenSize, 0.01))
            put("weightsHiddenOutput", matrix(hiddenSize, 1, 0.0))
            put("biasOutput", vector(1, value))
            put("inputMean", vector(inputSize, 0.0))
            put("inputStd", vector(inputSize, 1.0))
        }
        storage.writeText(path, root.toString())

        // Guard the fixture itself: it must really be a constant.
        val net = AimiNeuralNetwork.fromJsonString(root.toString())
        assertNotNull(net)
        assertNear(value, net.predict(FloatArray(inputSize) { 400f })[0], 1e-12)
    }

    // --- SMB head -------------------------------------------------------------------------------
    //
    // The SMB head ran with every liveness gate off. Measured before this change: trained on the single
    // constant label 0.42 with the head's own arguments, it PUBLISHED, and its answer moved 0.0125 U over
    // random inputs. `refine` only ever moves a dose by min(0.05 U, 25 %), so such a model cannot steer
    // anything - it can only add the same offset to every dose.

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
        path: AimiPath,
        log: (String) -> Unit = {},
    ): AimiNeuralNetwork? = NeuralModelTrainer.trainAndPublish(
        storage = storage,
        weightsPath = path,
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
    fun smbHeadRefusesAConstantModel() {
        val rows = smbRows()
        val constant = rows.map { doubleArrayOf(0.42) }
        val path = storage.file("smb_constant.json")

        val published = smbGate(rows, constant, path)

        assertNull(published)
        assertFalse(storage.exists(path))
    }

    @Test
    fun smbHeadPublishesAModelWhoseDoseFollowsBg() {
        val rows = smbRows()
        // A dose that really depends on bg, spanning far more than the 0.05 U runtime correction clamp.
        val targets = rows.map { doubleArrayOf(0.20 + (it[bgIndex] - 80f) / 220f * 1.60) }
        val path = storage.file("smb_live.json")

        val notes = mutableListOf<String>()
        val published = smbGate(rows, targets, path) { notes += it }

        assertNotNull(published, "gate notes: $notes")
        assertTrue(storage.exists(path))
    }

    @Test
    fun smbHeadSweepsTheSameClinicalAnchorsAsTheBasalHead() {
        // One shared anchor list. If a head sweeps its own window, the publish gate and the runtime probe
        // can disagree, and the app can publish a model its own loader then refuses.
        assertEquals(BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL, anchors)
        assertEquals(listOf(70.0, 140.0, 250.0), anchors)
    }
}
