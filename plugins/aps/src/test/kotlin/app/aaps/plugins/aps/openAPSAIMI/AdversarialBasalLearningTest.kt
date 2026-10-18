package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.ml.NeuralModelTrainer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Can the basal head learn on data shaped like the real CSV, and does the publish gate tell a model that learned
 * from a model that did not?
 *
 * The generator has: noisy labels, class imbalance (most rows sit near target so the label is neutral), three
 * constant zero features, two features correlated with bg, and a label driven by TWO features (bg and iob). Every
 * measurement is repeated over several seeds and the WORST run is what the assertions look at.
 *
 * The headline result of this file is a negative one, and it is the reason the publish gate gained a second check.
 * The spread probe does NOT separate a model that learned from a model that only fitted label noise. Measured with
 * the fixed training seed, the noise models move MORE across the bg anchors than the real ones (0.159 .. 0.208
 * against 0.073 .. 0.143). What does separate them, cleanly, is the held-out error against the best constant
 * predictor. Both numbers are asserted below so neither claim can rot.
 */
class AdversarialBasalLearningTest {

    @TempDir
    lateinit var dir: File

    private class RunResult(
        val seed: Int,
        /** Spread over the fixed clinical anchors: the shipped publish gate window. */
        val anchorSpread: Double,
        /** Spread over the training p10..p90 of the bg column: the OLD, per-patient window. */
        val legacySpread: Double,
        val mae: Double,
        val baselineMae: Double,
    ) {

        /** Held-out error as a fraction of the best constant predictor's. Below 1.0 = the model learned something. */
        val ratio: Double get() = mae / baselineMae
        override fun toString(): String =
            "seed=$seed anchorSpread=%.5f legacySpread=%.5f mae=%.4f baseline=%.4f ratio=%.3f"
                .format(anchorSpread, legacySpread, mae, baselineMae, ratio)
    }

    // ---------------------------------------------------------------- the spread window

    @Test
    fun `Q2 learns a two feature target on patient shaped data, worst of several seeds`() {
        val results = SEEDS.map { runOne(it) }
        results.forEach { println("Q2 $it") }

        val worstSpread = results.minOf { it.anchorSpread }
        val worstLegacy = results.minOf { it.legacySpread }
        val worstRatio = results.maxOf { it.ratio }
        println(
            "Q2 SUMMARY worstAnchorSpread=%.5f worstLegacySpread=%.5f worstRatio=%.3f".format(
                worstSpread, worstLegacy, worstRatio
            )
        )

        assertTrue(worstSpread >= BASAL_GATE, "worst bg spread $worstSpread is below the $BASAL_GATE publish gate")
        assertTrue(worstRatio < 1.0, "worst run did not beat the best constant predictor: ratio $worstRatio")
        // Why the window had to change: on the old per-patient window the same models did not clear the same gate.
        assertTrue(worstLegacy < BASAL_GATE, "the p10..p90 window no longer understates the response — re-measure")
    }

    // ---------------------------------------------------------------- what separates signal from noise

    /**
     * The measurement both thresholds are derived from: the spread and the held-out error of models that learned,
     * against the same two numbers for models trained on labels that carry no information at all.
     */
    @Test
    fun `separation of signal from noise on the clinical anchors`() {
        val signal = MEASURE_SEEDS.map { runOne(it) }
        val noise = MEASURE_SEEDS.map { runOne(it, signalFree = true) }

        println("seed | signalAnchor signalLegacy signalRatio | noiseAnchor noiseLegacy noiseRatio")
        for (i in MEASURE_SEEDS.indices) {
            println(
                "%4d | %12.5f %12.5f %11.3f | %11.5f %11.5f %10.3f".format(
                    MEASURE_SEEDS[i], signal[i].anchorSpread, signal[i].legacySpread, signal[i].ratio,
                    noise[i].anchorSpread, noise[i].legacySpread, noise[i].ratio,
                )
            )
        }

        val worstSignalSpread = signal.minOf { it.anchorSpread }
        val bestNoiseSpread = noise.maxOf { it.anchorSpread }
        val worstSignalRatio = signal.maxOf { it.ratio }
        val bestNoiseRatio = noise.minOf { it.ratio }

        println(
            "SPREAD  signal %.5f .. %.5f | noise %.5f .. %.5f".format(
                worstSignalSpread, signal.maxOf { it.anchorSpread }, noise.minOf { it.anchorSpread }, bestNoiseSpread
            )
        )
        println(
            "RATIO   signal %.3f .. %.3f | noise %.3f .. %.3f".format(
                signal.minOf { it.ratio }, worstSignalRatio, bestNoiseRatio, noise.maxOf { it.ratio }
            )
        )
        println(
            "SPREAD GATE %.3f -> signal %d/%d, noise %d/%d (a gate that cannot separate)".format(
                BASAL_GATE,
                signal.count { it.anchorSpread >= BASAL_GATE }, signal.size,
                noise.count { it.anchorSpread >= BASAL_GATE }, noise.size,
            )
        )
        println(
            "RATIO GATE  %.3f -> signal %d/%d, noise %d/%d (margins: signal %.3f, noise %.3f)".format(
                BASELINE_GATE,
                signal.count { it.ratio <= BASELINE_GATE }, signal.size,
                noise.count { it.ratio <= BASELINE_GATE }, noise.size,
                BASELINE_GATE - worstSignalRatio, bestNoiseRatio - BASELINE_GATE,
            )
        )

        // The negative result, asserted so it cannot be forgotten: spread alone is not a signal detector. The
        // distributions are not merely overlapping, they are ordered the wrong way round.
        assertTrue(
            bestNoiseSpread > worstSignalSpread,
            "noise spread $bestNoiseSpread no longer exceeds signal spread $worstSignalSpread — re-tune the gate"
        )
        // The gate that does the job: at least 9 of 10 real models admitted, 0 of 10 noise models admitted.
        assertTrue(signal.count { it.ratio <= BASELINE_GATE } >= 9, "only ${signal.count { it.ratio <= BASELINE_GATE }}/10 signal seeds beat the median label")
        assertTrue(noise.count { it.ratio <= BASELINE_GATE } == 0, "${noise.count { it.ratio <= BASELINE_GATE }}/10 noise seeds beat the median label")
    }

    /** Same measurement for the T3C head, whose label window is three times wider. */
    @Test
    fun `separation of signal from noise on the clinical anchors, T3C head`() {
        val signal = MEASURE_SEEDS.map { runOne(it, t3c = true) }
        val noise = MEASURE_SEEDS.map { runOne(it, signalFree = true, t3c = true) }

        for (i in MEASURE_SEEDS.indices) {
            println(
                "T3C seed=%4d signalSpread=%.5f signalRatio=%.3f | noiseSpread=%.5f noiseRatio=%.3f".format(
                    MEASURE_SEEDS[i], signal[i].anchorSpread, signal[i].ratio, noise[i].anchorSpread, noise[i].ratio
                )
            )
        }
        println(
            "T3C SPREAD signal %.5f .. %.5f | noise %.5f .. %.5f".format(
                signal.minOf { it.anchorSpread }, signal.maxOf { it.anchorSpread },
                noise.minOf { it.anchorSpread }, noise.maxOf { it.anchorSpread }
            )
        )
        println(
            "T3C RATIO  signal %.3f .. %.3f | noise %.3f .. %.3f".format(
                signal.minOf { it.ratio }, signal.maxOf { it.ratio }, noise.minOf { it.ratio }, noise.maxOf { it.ratio }
            )
        )
        println(
            "T3C SPREAD GATE %.3f -> signal %d/%d, noise %d/%d".format(
                BASAL_GATE,
                signal.count { it.anchorSpread >= BASAL_GATE }, signal.size,
                noise.count { it.anchorSpread >= BASAL_GATE }, noise.size,
            )
        )
        println(
            "T3C RATIO GATE %.3f -> signal %d/%d, noise %d/%d".format(
                BASELINE_GATE,
                signal.count { it.ratio <= BASELINE_GATE }, signal.size,
                noise.count { it.ratio <= BASELINE_GATE }, noise.size,
            )
        )

        // The shared 0.05 spread floor costs the T3C head a few real models. It is kept anyway: the runtime health
        // probe uses the same floor for both heads, so a lower one here would publish what the loader then refuses.
        assertTrue(
            signal.count { it.anchorSpread >= BASAL_GATE } < signal.size,
            "the shared spread floor no longer costs the T3C head anything — the KDoc must be updated"
        )
        assertTrue(signal.count { it.ratio <= BASELINE_GATE } >= 9, "only ${signal.count { it.ratio <= BASELINE_GATE }}/10 T3C signal seeds beat the median label")
        assertTrue(noise.count { it.ratio <= BASELINE_GATE } == 0, "${noise.count { it.ratio <= BASELINE_GATE }}/10 T3C noise seeds beat the median label")
    }

    // ---------------------------------------------------------------- the whole shipped gate, end to end

    /**
     * The decision that reaches the disk, not one of its parts: the same call the coordinator makes, over 10 seeds
     * of real signal and 10 seeds of pure noise.
     */
    @Test
    fun `the shipped publish gate admits models that learned and refuses noise`() {
        var signalPublished = 0
        var noisePublished = 0
        val noiseReasons = mutableListOf<String>()

        for (seed in MEASURE_SEEDS) {
            if (publishOne(seed, signalFree = false, reasons = mutableListOf())) signalPublished++
            if (publishOne(seed, signalFree = true, reasons = noiseReasons)) noisePublished++
        }
        println("E2E published: signal $signalPublished/${MEASURE_SEEDS.size}, noise $noisePublished/${MEASURE_SEEDS.size}")
        noiseReasons.filter { it.contains("discard") }.take(3).forEach { println("E2E noise refused: $it") }

        assertTrue(signalPublished >= 9, "only $signalPublished/10 models that learned were published")
        assertTrue(noisePublished == 0, "$noisePublished/10 noise models reached the disk")
    }

    // ---------------------------------------------------------------- generator

    private fun publishOne(seed: Int, signalFree: Boolean, reasons: MutableList<String>): Boolean {
        val data = generate(seed, signalFree = signalFree, t3c = false)
        val published = NeuralModelTrainer.trainAndPublish(
            weightsFile = File(dir, "e2e_${if (signalFree) "noise" else "signal"}_$seed.json"),
            split = NeuralModelTrainer.split80_20(data.first, data.second),
            config = TrainingConfig(learningRate = 0.0005, epochs = 200, patience = 20),
            inputSize = INPUTS,
            outputRange = 0.5..2.0,
            spreadFeatureIndex = BG_INDEX,
            spreadSweepValues = BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL,
            minOutputSpread = BASAL_GATE,
            maxBaselineErrorRatio = BASELINE_GATE,
            log = { reasons += it },
        )
        return published != null
    }

    /** Rows and labels shaped like the real basal CSV. [signalFree] cuts every link between features and label. */
    private fun generate(seed: Int, signalFree: Boolean, t3c: Boolean): Pair<List<FloatArray>, List<DoubleArray>> {
        val rng = Random(seed)
        val inputs = ArrayList<FloatArray>(ROWS)
        val targets = ArrayList<DoubleArray>(ROWS)
        // The T3C label window (0.5 .. 2.0) is three times wider than the basal one (0.85 .. 1.35), so the label
        // slope and the label noise are scaled by the same factor. Anything else would compare two different
        // problems and hide which head the threshold really fits.
        val scale = if (t3c) T3C_WINDOW_WIDTH / BASAL_WINDOW_WIDTH else 1.0
        val labelMin = if (t3c) 0.5 else 0.85
        val labelMax = if (t3c) 2.0 else 1.35

        for (i in 0 until ROWS) {
            // Class imbalance: 70 % of rows sit near target, so their label is exactly neutral.
            val nearTarget = rng.nextDouble() < 0.70
            val bg = if (nearTarget) rng.nextDouble(95.0, 125.0) else rng.nextDouble(50.0, 330.0)
            val iob = rng.nextDouble(0.0, 6.0)

            val row = FloatArray(INPUTS) { k ->
                when {
                    k == BG_INDEX          -> bg.toFloat()
                    k == IOB_INDEX         -> iob.toFloat()
                    // Correlated with bg, as duraMin / duraAvg are in the real CSV.
                    k == CORR_A            -> (bg / 100.0 + rng.nextDouble(-0.05, 0.05)).toFloat()
                    k == CORR_B            -> (bg / 100.0 + rng.nextDouble(-0.08, 0.08)).toFloat()
                    k in CONSTANT_FEATURES -> 0f
                    else                   -> rng.nextDouble(-1.0, 1.0).toFloat()
                }
            }

            val noiseAmp = LABEL_NOISE * scale
            val clean = if (nearTarget) 1.0 else trueLabel(bg, iob, scale, labelMin, labelMax)
            val label = if (signalFree) 1.0 + rng.nextDouble(-noiseAmp, noiseAmp)
            else (clean + rng.nextDouble(-noiseAmp, noiseAmp)).coerceIn(labelMin, labelMax)

            inputs.add(row)
            targets.add(doubleArrayOf(label))
        }
        return inputs to targets
    }

    private fun runOne(seed: Int, signalFree: Boolean = false, t3c: Boolean = false): RunResult {
        val (inputs, targets) = generate(seed, signalFree, t3c)
        val split = (ROWS * 0.8).toInt()
        val trainInputs = inputs.subList(0, split)
        val trainTargets = targets.subList(0, split)
        val valInputs = inputs.subList(split, ROWS)
        val valTargets = targets.subList(split, ROWS)

        val nn = AimiNeuralNetwork(
            inputSize = INPUTS,
            hiddenSize = 8,
            outputSize = 1,
            // Exact shipped head settings, see BasalMlTrainingCoordinator.trainBasalHead / trainT3cHead.
            config = if (t3c) TrainingConfig(learningRate = 0.001, epochs = 300, patience = 20)
            else TrainingConfig(learningRate = 0.0005, epochs = 200, patience = 20)
        )
        val (mean, std) = NeuralModelTrainer.inputNormalizationStats(trainInputs, INPUTS)
        nn.setInputNormalization(mean, std)
        nn.trainWithValidation(trainInputs, trainTargets, valInputs, valTargets)

        // The shipped publish gate: fixed clinical bg anchors, every other feature at its training median.
        val anchorOuts = NeuralModelTrainer
            .spreadProbeVectors(trainInputs, INPUTS, BG_INDEX, BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL)
            .map { nn.predict(it)[0] }
        // The old gate, kept only to show what it measured instead.
        val legacyOuts = NeuralModelTrainer.spreadProbeVectors(trainInputs, INPUTS, BG_INDEX).map { nn.predict(it)[0] }

        var err = 0.0
        for (i in valInputs.indices) err += abs(nn.predict(valInputs[i])[0] - valTargets[i][0])

        // Best constant predictor on the held out slice: the median of the training labels.
        val sortedLabels = trainTargets.map { it[0] }.sorted()
        val median = sortedLabels[sortedLabels.size / 2]
        var baseErr = 0.0
        for (t in valTargets) baseErr += abs(median - t[0])

        return RunResult(
            seed = seed,
            anchorSpread = anchorOuts.max() - anchorOuts.min(),
            legacySpread = legacyOuts.max() - legacyOuts.min(),
            mae = err / valInputs.size,
            baselineMae = baseErr / valTargets.size,
        )
    }

    /** Label the pipeline is meant to recover: more basal when high, less when insulin is already on board. */
    private fun trueLabel(bg: Double, iob: Double, scale: Double, min: Double, max: Double): Double =
        (1.0 + ((bg - 110.0) / 500.0 - iob * 0.02) * scale).coerceIn(min, max)

    private companion object {

        const val INPUTS = 16
        const val BG_INDEX = 0
        const val IOB_INDEX = 5
        const val CORR_A = 3
        const val CORR_B = 4
        const val ROWS = 600
        const val LABEL_NOISE = 0.06
        const val BASAL_WINDOW_WIDTH = 1.35 - 0.85
        const val T3C_WINDOW_WIDTH = 2.0 - 0.5

        /** Mirror of `BasalMlTrainingCoordinator.MIN_OUTPUT_SPREAD`, kept in step by that class's own test. */
        const val BASAL_GATE = 0.05

        /** Mirror of `BasalMlTrainingCoordinator.MAX_BASELINE_MAE_RATIO`. */
        const val BASELINE_GATE = 0.95

        val CONSTANT_FEATURES = setOf(8, 11, 14)
        val SEEDS = listOf(1, 2, 3, 4, 5, 6, 7)
        val MEASURE_SEEDS = (1..10).toList()
    }
}
