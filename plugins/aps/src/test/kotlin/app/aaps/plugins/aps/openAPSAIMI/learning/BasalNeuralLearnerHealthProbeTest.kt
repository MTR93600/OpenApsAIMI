package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runtime fail-safe of both learned heads: Universal Adaptive Basal and T3C Brittle Mode.
 *
 * A shipped `basal_adaptive_weights.json` was measured returning 0.19918 for every input, and the old
 * clamp floor of 0.70 turned that constant into a value that looked like a real decision on 100 % of
 * ticks. These tests pin the probe that rejects such a model and the new 0.80 floor.
 *
 * The T3C head shares the network class, the file format and the trainer, so it can die the same way.
 * It had no probe and no runtime clamp at all; the tests at the end of this file pin both.
 */
class BasalNeuralLearnerHealthProbeTest {

    private val mockFile = mockk<File>(relaxed = true)
    private val basalFile = mockk<File>(relaxed = true)
    private val t3cFile = mockk<File>(relaxed = true)
    private lateinit var context: Context
    private lateinit var preferences: Preferences
    private lateinit var storage: AimiStorageHelper
    private lateinit var log: AAPSLogger

    @BeforeEach
    fun setup() {
        context = mockk(relaxed = true)
        preferences = mockk(relaxed = true)
        storage = mockk(relaxed = true)
        log = mockk(relaxed = true)
        every { mockFile.exists() } returns true
        every { basalFile.exists() } returns true
        every { t3cFile.exists() } returns true
        // Default first, then the two weight files, so each head can be given its own model.
        every { storage.getAimiFile(any()) } returns mockFile
        every { storage.getAimiFile("basal_adaptive_weights.json") } returns basalFile
        every { storage.getAimiFile("t3c_brain_weights.json") } returns t3cFile
        every { preferences.get(any<DoublePreferenceKey>()) } answers { firstArg<DoubleKey>().defaultValue }
        every { preferences.get(any<BooleanPreferenceKey>()) } answers { firstArg<BooleanKey>().defaultValue }
        every { preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled) } returns true
        mockkObject(BasalMlModelStore)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(BasalMlModelStore)
    }

    /** Network whose only answer is [value], whatever the input. */
    private fun constantNet(value: Double): AimiNeuralNetwork {
        val net = mockk<AimiNeuralNetwork>()
        every { net.predict(any()) } returns doubleArrayOf(value)
        return net
    }

    /** Network that really answers to BG (feature index 0), mapped into [low] .. [high]. */
    private fun bgSensitiveNet(low: Double, high: Double): AimiNeuralNetwork {
        val net = mockk<AimiNeuralNetwork>()
        every { net.predict(any()) } answers {
            val bg = firstArg<FloatArray>()[0].toDouble()
            val position = ((bg - 70.0) / 180.0).coerceIn(0.0, 1.0)
            doubleArrayOf(low + (high - low) * position)
        }
        return net
    }

    /** Builds a learner. By default both heads get the same model, as the old test setup did. */
    private fun learnerWith(
        basalNet: AimiNeuralNetwork?,
        t3cNet: AimiNeuralNetwork? = basalNet,
    ): BasalNeuralLearner {
        every { BasalMlModelStore.loadValid(basalFile, any()) } returns basalNet
        every { BasalMlModelStore.loadValid(t3cFile, any()) } returns t3cNet
        return BasalNeuralLearner(context, preferences, storage, log)
    }

    private fun decisionAt(learner: BasalNeuralLearner, bg: Double) =
        learner.getUniversalBasalDecision(bg = bg, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 45.0, iob = 0.5)

    private fun t3cAt(learner: BasalNeuralLearner, bg: Double) =
        learner.getT3cAdaptiveDecision(bg = bg, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 45.0, iob = 0.5)

    @Test
    fun `constant model is rejected and the heuristic is used instead`() {
        // The exact constant measured on a real shipped weights file.
        val learner = learnerWith(constantNet(0.19918))

        val decision = decisionAt(learner, bg = 250.0)

        assertThat(decision.source).isEqualTo(BasalNeuralLearner.BasalMultiplierSource.HEURISTIC)
        // Heuristic starts neutral, so no basal is cut at all — the old behaviour cut 30 % forever.
        assertThat(decision.rawValue).isWithin(1e-9).of(1.0)
        assertThat(decision.multiplier).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `model answering outside the plausible range is rejected`() {
        val learner = learnerWith(bgSensitiveNet(low = 2.5, high = 4.0))

        assertThat(decisionAt(learner, bg = 140.0).source)
            .isEqualTo(BasalNeuralLearner.BasalMultiplierSource.HEURISTIC)
    }

    @Test
    fun `model that answers to bg is kept`() {
        val learner = learnerWith(bgSensitiveNet(low = 0.9, high = 1.25))

        val low = decisionAt(learner, bg = 70.0)
        val high = decisionAt(learner, bg = 250.0)

        assertThat(low.source).isEqualTo(BasalNeuralLearner.BasalMultiplierSource.NEURAL)
        assertThat(low.multiplier).isLessThan(high.multiplier)
    }

    @Test
    fun `runtime floor is 0_80, not 0_70`() {
        // Healthy model (it moves with BG) but asking for a very strong cut at low BG.
        val learner = learnerWith(bgSensitiveNet(low = 0.55, high = 1.10))

        val decision = decisionAt(learner, bg = 70.0)

        assertThat(decision.source).isEqualTo(BasalNeuralLearner.BasalMultiplierSource.NEURAL)
        assertThat(decision.rawValue).isWithin(1e-9).of(0.55)
        assertThat(decision.multiplier).isWithin(1e-9).of(BasalNeuralLearner.RUNTIME_BASAL_FLOOR)
        assertThat(decision.multiplier).isWithin(1e-9).of(0.80)
        assertThat(decision.clamped).isTrue()
    }

    @Test
    fun `raw value separates a clamped model from a model that really learned the floor`() {
        val clamped = decisionAt(learnerWith(bgSensitiveNet(low = 0.55, high = 1.10)), bg = 70.0)
        val learned = decisionAt(learnerWith(bgSensitiveNet(low = 0.80, high = 1.10)), bg = 70.0)

        // Same applied multiplier...
        assertThat(clamped.multiplier).isWithin(1e-9).of(learned.multiplier)
        // ...told apart only by n_raw, which is the field the JSONL now exports.
        assertThat(clamped.rawValue).isNotEqualTo(learned.rawValue)
        assertThat(clamped.clamped).isTrue()
        assertThat(learned.clamped).isFalse()
    }

    @Test
    fun `default ceiling leaves room above neutral so basal can be raised`() {
        val learner = learnerWith(bgSensitiveNet(low = 1.0, high = 1.9))

        val decision = decisionAt(learner, bg = 250.0)

        // Old default of 1.0 made every boost impossible by construction.
        assertThat(decision.ceiling).isWithin(1e-9).of(1.3)
        assertThat(decision.multiplier).isGreaterThan(1.0)
    }

    @Test
    fun `disabled feature reports its own source and never scales`() {
        every { preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled) } returns false
        val learner = learnerWith(bgSensitiveNet(low = 0.9, high = 1.25))

        val decision = decisionAt(learner, bg = 250.0)

        assertThat(decision.source).isEqualTo(BasalNeuralLearner.BasalMultiplierSource.DISABLED)
        assertThat(decision.multiplier).isWithin(1e-9).of(1.0)
    }

    // ── T3C head ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `bg-blind T3C model is rejected and the heuristic is used instead`() {
        // 1.5 sits inside the T3C publish range (0.3 .. 3.0), so only the spread check can catch it.
        val learner = learnerWith(basalNet = null, t3cNet = constantNet(1.5))

        val decision = t3cAt(learner, bg = 250.0)

        assertThat(decision.source).isEqualTo(BasalNeuralLearner.T3cFactorSource.HEURISTIC)
        // Heuristic starts neutral: the dead model scales nothing at all.
        assertThat(decision.rawMultiplier).isWithin(1e-9).of(1.0)
        assertThat(decision.multiplier).isWithin(1e-9).of(1.0)
        assertThat(decision.clamped).isFalse()
    }

    @Test
    fun `a dead T3C model does not disable a healthy basal model`() {
        val learner = learnerWith(
            basalNet = bgSensitiveNet(low = 0.9, high = 1.25),
            t3cNet = constantNet(1.5),
        )

        assertThat(t3cAt(learner, bg = 250.0).source).isEqualTo(BasalNeuralLearner.T3cFactorSource.HEURISTIC)
        assertThat(decisionAt(learner, bg = 250.0).source).isEqualTo(BasalNeuralLearner.BasalMultiplierSource.NEURAL)
    }

    @Test
    fun `T3C model that answers to bg is kept`() {
        val learner = learnerWith(basalNet = null, t3cNet = bgSensitiveNet(low = 0.8, high = 1.8))

        val low = t3cAt(learner, bg = 70.0)
        val high = t3cAt(learner, bg = 250.0)

        assertThat(low.source).isEqualTo(BasalNeuralLearner.T3cFactorSource.NEURAL)
        assertThat(low.multiplier).isWithin(1e-9).of(0.8)
        assertThat(high.multiplier).isWithin(1e-9).of(1.8)
        assertThat(low.clamped).isFalse()
        assertThat(high.clamped).isFalse()
    }

    @Test
    fun `live T3C factor is clamped to 0_5 and 2_0`() {
        // Healthy model (it moves with bg) but it asks for the extremes of its publish range.
        val learner = learnerWith(basalNet = null, t3cNet = bgSensitiveNet(low = 0.35, high = 2.9))

        val low = t3cAt(learner, bg = 70.0)
        val high = t3cAt(learner, bg = 250.0)

        assertThat(low.source).isEqualTo(BasalNeuralLearner.T3cFactorSource.NEURAL)
        assertThat(low.rawMultiplier).isWithin(1e-9).of(0.35)
        assertThat(low.multiplier).isWithin(1e-9).of(BasalNeuralLearner.RUNTIME_T3C_FACTOR_MIN)
        assertThat(low.multiplier).isWithin(1e-9).of(0.5)
        assertThat(low.clamped).isTrue()

        assertThat(high.rawMultiplier).isWithin(1e-9).of(2.9)
        assertThat(high.multiplier).isWithin(1e-9).of(BasalNeuralLearner.RUNTIME_T3C_FACTOR_MAX)
        assertThat(high.multiplier).isWithin(1e-9).of(2.0)
        assertThat(high.clamped).isTrue()
    }

    @Test
    fun `T3C factor is the user aggressiveness times the clamped multiplier`() {
        every { preferences.get(DoubleKey.OApsAIMIT3cAggressiveness) } returns 1.5
        val learner = learnerWith(basalNet = null, t3cNet = bgSensitiveNet(low = 0.35, high = 2.9))

        val decision = t3cAt(learner, bg = 250.0)
        val plainFactor = learner.getT3cAdaptiveFactor(
            bg = 250.0, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 45.0, iob = 0.5,
        )

        // Without the clamp this would be 1.5 * 2.9 = 4.35.
        assertThat(decision.baseAggressiveness).isWithin(1e-9).of(1.5)
        assertThat(decision.factor).isWithin(1e-9).of(3.0)
        assertThat(plainFactor).isWithin(1e-9).of(decision.factor)
    }

    @Test
    fun `raw multiplier separates a clamped T3C model from one that really learned the floor`() {
        val clamped = t3cAt(learnerWith(basalNet = null, t3cNet = bgSensitiveNet(0.35, 1.10)), bg = 70.0)
        val learned = t3cAt(learnerWith(basalNet = null, t3cNet = bgSensitiveNet(0.50, 1.10)), bg = 70.0)

        assertThat(clamped.multiplier).isWithin(1e-9).of(learned.multiplier)
        assertThat(clamped.rawMultiplier).isNotEqualTo(learned.rawMultiplier)
        assertThat(clamped.clamped).isTrue()
        assertThat(learned.clamped).isFalse()
    }

    @Test
    fun `reloadModels probes the T3C head too`() {
        val learner = learnerWith(basalNet = null, t3cNet = bgSensitiveNet(low = 0.8, high = 1.8))
        assertThat(t3cAt(learner, bg = 250.0).source).isEqualTo(BasalNeuralLearner.T3cFactorSource.NEURAL)

        // Training publishes a dead head, then the loop reloads it.
        every { BasalMlModelStore.loadValid(t3cFile, any()) } returns constantNet(1.5)
        learner.reloadModels()

        assertThat(t3cAt(learner, bg = 250.0).source).isEqualTo(BasalNeuralLearner.T3cFactorSource.HEURISTIC)
    }

    @Test
    fun `both probes sweep the same clinical bg anchors`() {
        // The publish gate must sweep this very list, or training can publish what the loader refuses.
        assertThat(BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL)
            .containsExactly(70.0, 140.0, 250.0)
            .inOrder()
    }
}
