package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import android.content.Context
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.ml.NeuralModelTrainer
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

/**
 * Trains small on-device MLPs on OREF feature rows (same 35 inputs as ONNX) for hypo/hyper 4h labels.
 * Weights stored under `filesDir`/oref_personal/ — no Python, no cloud.
 *
 * ## The output of this head is NOT a probability
 *
 * There is a mismatch between how the head is trained and how it is read back:
 *
 * - **Objective:** [trainOneHead] fits the network's **raw** linear output to the 0/1 labels of
 *   [OrefOutcomeComputer] with a squared-error loss. There is no sigmoid in the loss, so the network learns to
 *   output numbers near 0 and near 1.
 * - **Readback:** [meanSigmoid] then pushes that raw output through [sigmoid] before reporting it as a
 *   percentage.
 *
 * A raw 0 (the model saying "no event") is reported as 50 %, and a raw 1 ("event for sure") as only about 73 %.
 * So the reported number cannot go below 50 %, cannot get near 100 %, and in practice tracks
 * `sigmoid(base rate)`: measured on this exact configuration, a true event rate of 0.05 reports 50.6 %, 0.15
 * reports 52.5 %, 0.30 reports 57.0 % and 0.50 reports 62.4 %.
 *
 * **This value is therefore not calibrated and must not be read as a risk.** It is informational only. Decision
 * code reads it through [OrefPersonalSignalGate], which holds it off until the objective and the readback agree;
 * user-facing text must not print it as a percentage. Fixing the objective (train through the sigmoid, or keep
 * the raw output and calibrate it) is a separate change with its own measurements.
 */
object OrefPersonalMlTrainer {

    /** Per head (hypo and hyper); ~10 days of active looping often reaches this. */
    const val MIN_LABELLED_SAMPLES = 120
    private const val HIDDEN = 16

    private fun dir(ctx: Context): File = File(ctx.filesDir, "oref_personal").apply { mkdirs() }

    // These two files are written after every training run but nothing loads them back yet: the head is always
    // retrained from the current window. They are kept so a later change can reuse the weights.
    fun hypoFile(ctx: Context): File = File(dir(ctx), "personal_hypo_mlp.json")
    fun hyperFile(ctx: Context): File = File(dir(ctx), "personal_hyper_mlp.json")

    data class PersonalMlOutcome(
        val status: OrefPersonalMlStatus,
        /** Uncalibrated score in 50..73, **not** a probability. See the class KDoc. */
        val meanHypoSignalPct: Double? = null,
        /** Uncalibrated score in 50..73, **not** a probability. See the class KDoc. */
        val meanHyperSignalPct: Double? = null,
        val detail: String? = null,
    )

    fun trainAndSummarize(
        ctx: Context,
        slices: List<Triple<Int, DoubleArray, Long>>,
        outcomePerSlice: List<OrefOutcomeComputer.Outcome>,
    ): PersonalMlOutcome {
        val hypoPairs = ArrayList<Pair<FloatArray, Double>>()
        val hyperPairs = ArrayList<Pair<FloatArray, Double>>()
        for (i in slices.indices) {
            val x = toFloatInput(slices[i].second)
            val o = outcomePerSlice[i]
            o.hypo4h?.let { y -> hypoPairs += x to y }
            o.hyper4h?.let { y -> hyperPairs += x to y }
        }
        if (hypoPairs.size < MIN_LABELLED_SAMPLES || hyperPairs.size < MIN_LABELLED_SAMPLES) {
            return PersonalMlOutcome(
                OrefPersonalMlStatus.INSUFFICIENT_DATA,
                detail = "labelled_hypo=${hypoPairs.size} labelled_hyper=${hyperPairs.size} (need >=$MIN_LABELLED_SAMPLES each)",
            )
        }
        return try {
            val hypoNet = trainOneHead(hypoPairs, Random(42L))
            hypoNet.saveToFile(hypoFile(ctx))
            val hyperNet = trainOneHead(hyperPairs, Random(43L))
            hyperNet.saveToFile(hyperFile(ctx))

            // Uncalibrated scores, not risk percentages — see the class KDoc before using them anywhere.
            val meanH = meanSigmoid(hypoNet, slices)
            val meanHy = meanSigmoid(hyperNet, slices)
            PersonalMlOutcome(OrefPersonalMlStatus.TRAINED_AND_USED, meanH * 100.0, meanHy * 100.0, null)
        } catch (t: Throwable) {
            PersonalMlOutcome(OrefPersonalMlStatus.TRAIN_FAILED, detail = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun trainOneHead(pairs: List<Pair<FloatArray, Double>>, rng: Random): AimiNeuralNetwork {
        val cfg = TrainingConfig(
            learningRate = 0.002,
            epochs = 48,
            patience = 6,
            batchSize = 64,
            useBatchNorm = false,
            useDropout = false,
            weightDecay = 0.005,
        )
        val net = AimiNeuralNetwork(OrefModelFeatures.COUNT, HIDDEN, 1, cfg, regularizationLambda = 0.005)
        val idx = pairs.indices.shuffled(rng)
        val split = (idx.size * 0.85).toInt().coerceAtLeast(1)
        val trIdx = idx.take(split)
        val vaIdx = idx.drop(split).ifEmpty { trIdx.takeLast(1) }
        val trainInputs = trIdx.map { pairs[it].first }
        val trainTargets = trIdx.map { doubleArrayOf(pairs[it].second) }
        val valInputs = vaIdx.map { pairs[it].first }
        val valTargets = vaIdx.map { doubleArrayOf(pairs[it].second) }
        // This head builds its network directly instead of going through NeuralModelTrainer, so it has
        // to install the input scaling itself. Without it the raw feature sizes differ by orders of
        // magnitude and the widest feature alone drives the hidden layer.
        val (mean, std) = NeuralModelTrainer.inputNormalizationStats(trainInputs, OrefModelFeatures.COUNT)
        net.setInputNormalization(mean, std)
        net.trainWithValidation(trainInputs, trainTargets, valInputs, valTargets)
        return net
    }

    /**
     * Mean of `sigmoid(raw output)` over every slice.
     *
     * The sigmoid here does **not** turn the output into a probability: the head was fitted on the raw output
     * (see [trainOneHead]), so this squashes an already 0..1-shaped number into 0.5..0.73. The result is an
     * uncalibrated score. See the class KDoc.
     */
    private fun meanSigmoid(net: AimiNeuralNetwork, slices: List<Triple<Int, DoubleArray, Long>>): Double {
        if (slices.isEmpty()) return 0.0
        var s = 0.0
        for ((_, f, _) in slices) {
            val p = sigmoid(net.predict(toFloatInput(f))[0])
            s += p
        }
        return s / slices.size
    }

    fun toFloatInput(row: DoubleArray): FloatArray {
        val n = OrefModelFeatures.COUNT
        return FloatArray(n) { j ->
            val v = row.getOrNull(j) ?: Double.NaN
            if (v.isFinite()) v.toFloat() else 0f
        }
    }

    private fun sigmoid(raw: Double): Double {
        val x = raw.coerceIn(-30.0, 30.0)
        return 1.0 / (1.0 + exp(-x))
    }
}
