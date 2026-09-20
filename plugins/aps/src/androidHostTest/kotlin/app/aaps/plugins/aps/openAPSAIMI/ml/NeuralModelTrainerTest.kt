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
}
