package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ml.NeuralModelTrainer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * ADVERSARIAL verification pass, added by a review agent. Temporary file.
 *
 * Measures the p10..p90 spread probe against the full clinical bg range on the SAME trained model, and against a
 * signal free control, to see whether the 0.05 gate separates a model that learned from a model that did not.
 */
class AdversarialSpreadProbeTest {

    @Test
    fun `measure probe window versus full range response`() {
        println("seed | bgP10 bgP50 bgP90 | probeSpread | fullRangeSpread(40..400) | valMae | constMae")
        val probeSpreads = ArrayList<Double>()
        val fullSpreads = ArrayList<Double>()
        val noiseProbeSpreads = ArrayList<Double>()

        for (seed in 1..10) {
            val m = runOne(seed, signalFree = false)
            probeSpreads.add(m.probeSpread)
            fullSpreads.add(m.fullSpread)
            println(
                "%4d | %5.0f %5.0f %5.0f | %11.5f | %24.5f | %6.4f | %6.4f".format(
                    seed, m.p10, m.p50, m.p90, m.probeSpread, m.fullSpread, m.mae, m.constMae
                )
            )
            noiseProbeSpreads.add(runOne(seed, signalFree = true).probeSpread)
        }

        println()
        println("SIGNAL  probeSpread min=%.5f max=%.5f  -> runs below the 0.05 gate: %d/10".format(
            probeSpreads.min(), probeSpreads.max(), probeSpreads.count { it < 0.05 }
        ))
        println("SIGNAL  fullRangeSpread min=%.5f max=%.5f".format(fullSpreads.min(), fullSpreads.max()))
        println("NOISE   probeSpread min=%.5f max=%.5f  -> runs ABOVE the 0.05 gate: %d/10".format(
            noiseProbeSpreads.min(), noiseProbeSpreads.max(), noiseProbeSpreads.count { it >= 0.05 }
        ))
        println("OVERLAP: worst signal run %.5f vs best noise run %.5f".format(probeSpreads.min(), noiseProbeSpreads.max()))
        assertTrue(probeSpreads.isNotEmpty())
    }

    private class Measured(
        val p10: Double, val p50: Double, val p90: Double,
        val probeSpread: Double, val fullSpread: Double, val mae: Double, val constMae: Double
    )

    private fun runOne(seed: Int, signalFree: Boolean): Measured {
        val rng = Random(seed)
        val inputs = ArrayList<FloatArray>(ROWS)
        val targets = ArrayList<DoubleArray>(ROWS)
        for (i in 0 until ROWS) {
            val nearTarget = rng.nextDouble() < 0.70
            val bg = if (nearTarget) rng.nextDouble(95.0, 125.0) else rng.nextDouble(50.0, 330.0)
            val iob = rng.nextDouble(0.0, 6.0)
            val row = FloatArray(INPUTS) { k ->
                when {
                    k == BG_INDEX          -> bg.toFloat()
                    k == IOB_INDEX         -> iob.toFloat()
                    k == 3                 -> (bg / 100.0 + rng.nextDouble(-0.05, 0.05)).toFloat()
                    k == 4                 -> (bg / 100.0 + rng.nextDouble(-0.08, 0.08)).toFloat()
                    k in setOf(8, 11, 14)  -> 0f
                    else                   -> rng.nextDouble(-1.0, 1.0).toFloat()
                }
            }
            val clean = if (nearTarget) 1.0 else (1.0 + (bg - 110.0) / 500.0 - iob * 0.02).coerceIn(0.85, 1.35)
            val label = if (signalFree) 1.0 + rng.nextDouble(-0.06, 0.06)
            else (clean + rng.nextDouble(-0.06, 0.06)).coerceIn(0.85, 1.35)
            inputs.add(row)
            targets.add(doubleArrayOf(label))
        }

        val split = (ROWS * 0.8).toInt()
        val trainInputs = inputs.subList(0, split)
        val trainTargets = targets.subList(0, split)
        val valInputs = inputs.subList(split, ROWS)
        val valTargets = targets.subList(split, ROWS)

        val nn = AimiNeuralNetwork(INPUTS, 8, 1, TrainingConfig(learningRate = 0.0005, epochs = 200, patience = 20))
        val (mean, std) = NeuralModelTrainer.inputNormalizationStats(trainInputs, INPUTS)
        nn.setInputNormalization(mean, std)
        nn.trainWithValidation(trainInputs, trainTargets, valInputs, valTargets)

        val probes = NeuralModelTrainer.spreadProbeVectors(trainInputs, INPUTS, BG_INDEX)
        val probeOuts = probes.map { nn.predict(it)[0] }
        val probeSpread = probeOuts.max() - probeOuts.min()

        // Same base vector, but swept over the whole clinical range instead of p10..p90.
        val base = probes.first().copyOf()
        val fullOuts = listOf(40f, 100f, 180f, 260f, 400f).map { bg ->
            nn.predict(base.copyOf().also { it[BG_INDEX] = bg })[0]
        }
        val fullSpread = fullOuts.max() - fullOuts.min()

        var err = 0.0
        for (i in valInputs.indices) err += abs(nn.predict(valInputs[i])[0] - valTargets[i][0])
        val sorted = trainTargets.map { it[0] }.sorted()
        val median = sorted[sorted.size / 2]
        var baseErr = 0.0
        for (t in valTargets) baseErr += abs(median - t[0])

        return Measured(
            p10 = probes[0][BG_INDEX].toDouble(),
            p50 = probes[1][BG_INDEX].toDouble(),
            p90 = probes[2][BG_INDEX].toDouble(),
            probeSpread = probeSpread,
            fullSpread = fullSpread,
            mae = err / valInputs.size,
            constMae = baseErr / valTargets.size,
        )
    }

    private companion object {

        const val INPUTS = 16
        const val BG_INDEX = 0
        const val IOB_INDEX = 5
        const val ROWS = 600
    }
}
