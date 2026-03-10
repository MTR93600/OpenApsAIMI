package app.aaps.plugins.aps.openAPSAIMI

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class aimiNeuralNetworkTest {

    @Test
    fun `test forward pass shape`() {
        val nn = AimiNeuralNetwork(inputSize = 3, hiddenSize = 5, outputSize = 1)
        val input = floatArrayOf(1.0f, 2.0f, 3.0f)
        val output = nn.predict(input)
        assertEquals(1, output.size)
    }

    @Test
    fun `test training reduces loss`() {
        // Simple regression: y = x1 + x2
        val nn = AimiNeuralNetwork(
            inputSize = 2,
            hiddenSize = 4,
            outputSize = 1,
            config = TrainingConfig(
                epochs = 100,
                learningRate = 0.01,
                batchSize = 1
            )
        )

        val inputs = listOf(
            floatArrayOf(0.0f, 0.0f),
            floatArrayOf(1.0f, 0.0f),
            floatArrayOf(0.0f, 1.0f),
            floatArrayOf(1.0f, 1.0f)
        )
        val targets = listOf(
            doubleArrayOf(0.0),
            doubleArrayOf(1.0),
            doubleArrayOf(1.0),
            doubleArrayOf(2.0)
        )

        val initialLoss = nn.validate(inputs, targets)
        nn.trainWithValidation(inputs, targets, inputs, targets)
        val finalLoss = nn.validate(inputs, targets)

        assertTrue("Loss should decrease", finalLoss < initialLoss)
    }
}
