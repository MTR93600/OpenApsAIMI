package app.aaps.plugins.aps.openAPSAIMI.ml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NeuralModelTrainerTest {

    @Test
    fun `split80_20 keeps chronology and puts 80 percent in the train set`() {
        val inputs = (0 until 10).map { floatArrayOf(it.toFloat()) }
        val targets = (0 until 10).map { doubleArrayOf(it.toDouble()) }

        val split = NeuralModelTrainer.split80_20(inputs, targets)

        assertThat(split.trainInputs).hasSize(8)
        assertThat(split.valInputs).hasSize(2)
        assertThat(split.trainInputs.first()[0]).isEqualTo(0f)  // chronological, oldest first
        assertThat(split.valInputs.first()[0]).isEqualTo(8f)    // validation = most recent 20%
    }

    @Test
    fun `split80_20 falls back to the train set when validation would be empty`() {
        val inputs = listOf(floatArrayOf(1f))
        val targets = listOf(doubleArrayOf(1.0))

        val split = NeuralModelTrainer.split80_20(inputs, targets)

        assertThat(split.trainInputs).hasSize(1)
        assertThat(split.valInputs).isSameInstanceAs(split.trainInputs) // never empty
    }

    private fun splitOf(valInputs: List<Float>, trainTargets: List<Double>, valTargets: List<Double>) =
        NeuralModelTrainer.Split(
            trainInputs = trainTargets.map { floatArrayOf(0f) },
            trainTargets = trainTargets.map { doubleArrayOf(it) },
            valInputs = valInputs.map { floatArrayOf(it) },
            valTargets = valTargets.map { doubleArrayOf(it) },
        )

    @Test
    fun `held-out spread is zero for a constant model and large for one that follows the rise`() {
        val split = splitOf(valInputs = (0 until 20).map { it.toFloat() }, trainTargets = listOf(0.0), valTargets = List(20) { 0.0 })

        val constant = NeuralModelTrainer.heldOutPredictionSpread({ doubleArrayOf(0.2) }, split)
        // Dose follows the input: 0.0 .. 1.9; top 2 mean 1.85, bottom 2 mean 0.05.
        val follows = NeuralModelTrainer.heldOutPredictionSpread({ doubleArrayOf(it[0] / 10.0) }, split)

        assertThat(constant).isWithin(1e-9).of(0.0)
        assertThat(follows).isWithin(1e-9).of(1.8)
    }

    /**
     * A label that is mostly zero: under MAE the median (0) cannot be beaten, under RMSE the mean can.
     * The shape of the 2026-10-15 SMB corpus, reduced to ten rows.
     */
    @Test
    fun `rmse against the mean rewards a model that finds the rare doses`() {
        val labels = listOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0)
        val split = splitOf(valInputs = labels.map { it.toFloat() }, trainTargets = labels, valTargets = labels)
        // Finds the doses, a little too low, and is never exactly zero.
        // MAE 0.26 vs median 0.20 (ratio 1.3); RMSE 0.26 vs mean 0.40 (ratio 0.65).
        val model: (FloatArray) -> DoubleArray = { doubleArrayOf(if (it[0] > 0.5f) 0.7 else 0.25) }

        val (mae, maeBaseline) = NeuralModelTrainer.heldOutMaeAgainstConstant(model, split)
        val (rmse, rmseBaseline) = NeuralModelTrainer.heldOutRmseAgainstMean(model, split)

        assertThat(mae / maeBaseline).isGreaterThan(0.95)
        assertThat(rmse / rmseBaseline).isLessThan(0.95)
    }

    @Test
    fun `rmse baseline is the mean of the training labels`() {
        val split = splitOf(valInputs = listOf(0f, 0f), trainTargets = listOf(0.0, 2.0), valTargets = listOf(1.0, 1.0))

        val (_, baseline) = NeuralModelTrainer.heldOutRmseAgainstMean({ doubleArrayOf(0.0) }, split)

        assertThat(baseline).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `splitInBlocks puts every fifth block of rows in validation`() {
        val rows = NeuralModelTrainer.BLOCK_SIZE * 10
        val inputs = (0 until rows).map { floatArrayOf(it.toFloat()) }
        val targets = (0 until rows).map { doubleArrayOf(it.toDouble()) }

        val split = NeuralModelTrainer.splitInBlocks(inputs, targets)

        // 10 blocks: blocks 4 and 9 are validation, the other 8 blocks are train.
        assertThat(split.valInputs).hasSize(NeuralModelTrainer.BLOCK_SIZE * 2)
        assertThat(split.trainInputs).hasSize(NeuralModelTrainer.BLOCK_SIZE * 8)
        assertThat(split.valInputs.first()[0]).isEqualTo((NeuralModelTrainer.BLOCK_SIZE * 4).toFloat())
        assertThat(split.valInputs.map { it[0] }).containsNoneIn(split.trainInputs.map { it[0] })
    }

    @Test
    fun `splitInBlocks keeps non zero labels from an early period in validation`() {
        // Non zero labels only in the first half. A newest-20% split would see only zeros in validation.
        val rows = NeuralModelTrainer.BLOCK_SIZE * 20
        val inputs = (0 until rows).map { floatArrayOf(it.toFloat()) }
        val targets = (0 until rows).map { doubleArrayOf(if (it < rows / 2) 0.3 else 0.0) }

        val split = NeuralModelTrainer.splitInBlocks(inputs, targets)

        assertThat(split.valTargets.any { it[0] > 0.0 }).isTrue()
        assertThat(NeuralModelTrainer.split80_20(inputs, targets).valTargets.any { it[0] > 0.0 }).isFalse()
    }

    @Test
    fun `splitInBlocks falls back when there are too few rows for a validation block`() {
        val inputs = (0 until 5).map { floatArrayOf(it.toFloat()) }
        val targets = (0 until 5).map { doubleArrayOf(it.toDouble()) }

        val split = NeuralModelTrainer.splitInBlocks(inputs, targets)

        assertThat(split.trainInputs).isNotEmpty()
        assertThat(split.valInputs).isNotEmpty()
    }
}
