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
