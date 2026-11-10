package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.exp
import kotlin.random.Random

/**
 * The trainer used to be a scheduled 24 h job that always overwrote the installed weights, whatever
 * it had fitted. On a positive class measured at 0.53 % of the production corpus, 100 epochs of
 * full-batch descent at a learning rate of 0.01 from a zero start moves the intercept about -0.45
 * out of the -5.2 it needs: the output was an intercept caught halfway, and it replaced the previous
 * one every night.
 *
 * A model now has to earn its place on data it never saw.
 */
class AutodriveNeuralTrainerHoldoutGateTest {

    @TempDir
    lateinit var dir: File

    private val aapsLogger = mockk<AAPSLogger>(relaxed = true)

    private fun trainer(): AutodriveNeuralTrainer {
        val storage = mockk<AimiStorageHelper>()
        every { storage.getAimiFile(any<String>()) } answers { File(dir, firstArg<String>()) }
        every { storage.saveFileSafe(any(), any()) } answers {
            firstArg<File>().writeText(secondArg()); true
        }
        return AutodriveNeuralTrainer(mockk<Context>(relaxed = true), aapsLogger, storage)
    }

    private fun weightsFile() = File(dir, AutodriveNeuralTrainer.WEIGHTS_FILE_NAME)

    /**
     * Writes a v2 corpus of [count] rows.
     *
     * [hypoProbability] receives the HR stress value and returns the chance of a hypoglycaemia, so a
     * caller can make the label depend on the features or not.
     */
    private fun writeCorpus(count: Int, seed: Int, hypoProbability: (Double) -> Double) {
        val random = Random(seed)
        val start = System.currentTimeMillis() - count * 300_000L
        val body = (0 until count).joinToString("\n") { i ->
            val hr = random.nextDouble()
            val hypo = if (random.nextDouble() < hypoProbability(hr)) "1" else "0"
            val ts = start + i * 300_000L
            "$ts,2026-08-01 10:00:00,150.0,0.0,1.5,0.0,0.1,0.5,70.0," +
                "$hr|0.0|0.0,0.0,0.8,0.0,0.8,0,150.0,$hypo,0,1,${AutodriveDatasetSchema.CURRENT_VERSION}"
        }
        File(dir, AutodriveDataLake.FILE_NAME).writeText(AutodriveDatasetSchema.HEADER + "\n" + body + "\n")
    }

    @Test
    fun `a model with real signal is installed`() {
        // Hypo risk rises steeply with the HR stress feature: there is something to learn.
        writeCorpus(6000, seed = 7) { hr -> 1.0 / (1.0 + exp(-(6.0 * hr - 4.0))) * 0.6 }

        val trainer = trainer()
        assertThat(trainer.trainAttentionWeights()).isTrue()

        val json = JSONObject(weightsFile().readText())
        assertThat(json.getDouble("weight_hr")).isGreaterThan(0.5)
        // Bounded, whatever the fit produced.
        assertThat(json.getDouble("weight_hr")).isAtMost(AutodriveNeuralTrainer.MAX_ABS_WEIGHT)
        // Calibrated back onto the real base rate, not onto the 50 % prior training balanced to.
        assertThat(json.getDouble("bias")).isLessThan(json.getDouble("bias_balanced"))
        assertThat(json.getInt("holdout_rows")).isGreaterThan(0)
        assertThat(json.getInt("holdout_positives")).isGreaterThan(0)
    }

    @Test
    fun `a model with no signal is refused and nothing is written`() {
        // The label is independent of every feature. Nothing to learn, so nothing to install.
        writeCorpus(6000, seed = 11) { 0.05 }

        val trainer = trainer()
        assertThat(trainer.trainAttentionWeights()).isFalse()
        assertThat(weightsFile().exists()).isFalse()
        assertThat(trainer.lastReport?.reason).contains("base rate")
    }

    @Test
    fun `an installed model is not displaced by one that does not beat it`() {
        writeCorpus(6000, seed = 7) { hr -> 1.0 / (1.0 + exp(-(6.0 * hr - 4.0))) * 0.6 }
        assertThat(trainer().trainAttentionWeights()).isTrue()
        val incumbent = weightsFile().readText()

        // Same corpus, so the second fit reproduces the first: an exact tie is not an improvement.
        assertThat(trainer().trainAttentionWeights()).isFalse()
        assertThat(weightsFile().readText()).isEqualTo(incumbent)
    }

    @Test
    fun `too few positives means no fit at all`() {
        writeCorpus(6000, seed = 3) { 0.0005 }

        val trainer = trainer()
        assertThat(trainer.trainAttentionWeights()).isFalse()
        assertThat(weightsFile().exists()).isFalse()
        assertThat(trainer.lastReport?.reason).contains("positives")
    }
}
