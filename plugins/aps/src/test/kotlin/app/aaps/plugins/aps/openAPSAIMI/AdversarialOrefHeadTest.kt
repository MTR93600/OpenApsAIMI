package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ml.NeuralModelTrainer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp
import kotlin.random.Random

/**
 * ADVERSARIAL verification pass, added by a review agent. Temporary file.
 *
 * Q5, oref advisor head. That head trains the RAW output against 0/1 labels and then squashes the raw output with a
 * sigmoid at read time (`OrefPersonalMlTrainer.meanSigmoid`). The reported percentage therefore lives in 50..73 %
 * and can never go below 50 %. `PkpdAdvisor` compares that percentage against 48 % and 52 %.
 *
 * The training fix makes the head much better at matching the 0/1 labels, which moves the reported percentage from
 * "near 50 because nothing was learned" to "near sigmoid(base rate)". This test measures where it lands.
 */
class AdversarialOrefHeadTest {

    @Test
    fun `oref head reported percentage against the 48 and 52 advisor thresholds`() {
        for (baseRate in listOf(0.05, 0.15, 0.30, 0.50)) {
            val pct = trainAndReport(baseRate)
            println(
                "baseRate=%.2f -> reported signal %.2f %%  (>=48 gate: %s, >=52 gate: %s)".format(
                    baseRate, pct, pct >= 48.0, pct >= 52.0
                )
            )
            assertTrue(pct >= 50.0, "sigmoid of a non negative mean can never report below 50 %: got $pct")
        }
    }

    /** Same numbers, expressed as the raw model output, which is what the label actually lives in. */
    @Test
    fun `oref head raw output tracks the label while the sigmoid compresses it`() {
        val raw = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 1.0)
        println("raw label value -> reported percentage after the sigmoid")
        for (v in raw) println("  %.2f -> %.1f %%".format(v, sigmoid(v) * 100.0))
        // A perfectly trained head that predicts probability 1.0 reports only 73 %.
        assertTrue(sigmoid(1.0) * 100.0 < 74.0)
    }

    private fun trainAndReport(baseRate: Double): Double {
        val rng = Random(9)
        val rows = 400
        val inputs = ArrayList<FloatArray>(rows)
        val targets = ArrayList<DoubleArray>(rows)
        for (i in 0 until rows) {
            // One informative feature on a large raw scale, the rest small: the real oref feature mix.
            val bg = rng.nextDouble(50.0, 320.0)
            val row = FloatArray(FEATURES) { k ->
                when (k) {
                    0    -> bg.toFloat()
                    1    -> rng.nextDouble(0.0, 6.0).toFloat()
                    else -> rng.nextDouble(-1.0, 1.0).toFloat()
                }
            }
            // Hypo happens more often when bg is already low; base rate tuned by the threshold.
            val p = (baseRate * 2.0 * (1.0 - (bg - 50.0) / 270.0)).coerceIn(0.0, 1.0)
            inputs.add(row)
            targets.add(doubleArrayOf(if (rng.nextDouble() < p) 1.0 else 0.0))
        }

        val split = (rows * 0.85).toInt()
        val nn = AimiNeuralNetwork(
            FEATURES, 8, 1,
            // Exact oref head settings, see OrefPersonalMlTrainer.trainOneHead.
            TrainingConfig(
                learningRate = 0.002, epochs = 48, patience = 6, batchSize = 64,
                useBatchNorm = false, useDropout = false, weightDecay = 0.005
            ),
            regularizationLambda = 0.005
        )
        val (mean, std) = NeuralModelTrainer.inputNormalizationStats(inputs.subList(0, split), FEATURES)
        nn.setInputNormalization(mean, std)
        nn.trainWithValidation(
            inputs.subList(0, split), targets.subList(0, split),
            inputs.subList(split, rows), targets.subList(split, rows)
        )

        var s = 0.0
        for (row in inputs) s += sigmoid(nn.predict(row)[0])
        return s / inputs.size * 100.0
    }

    private fun sigmoid(raw: Double): Double = 1.0 / (1.0 + exp(-raw.coerceIn(-30.0, 30.0)))

    private companion object {

        const val FEATURES = 10
    }
}
