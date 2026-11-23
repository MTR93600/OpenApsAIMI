package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.ml.NeuralModelTrainer
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [BasalHeadHealthGate]: one check for the basal and T3C heads, at publish and at load.
 *
 * The constant model, the range check and the two thresholds are covered on the loader side in
 * `BasalNeuralLearnerHealthProbeTest`. This file pins the probe point, the stamp, and the promise that
 * matters most: what the trainer publishes, its own loader accepts.
 */
class BasalHeadHealthGateTest {

    private val inputSize = BasalMlSchema.INPUT_SIZE

    @Test
    fun probePointIsTheModelInputMean() {
        val mean = DoubleArray(inputSize) { 10.0 + it }
        val net = AimiNeuralNetwork(inputSize, 4, 1)
        net.setInputNormalization(mean, DoubleArray(inputSize) { 1.0 })

        val verdict = BasalHeadHealthGate.check(net, BasalHeadHealthGate.BASAL_OUTPUT_RANGE, BasalHeadHealthGate.CONSTANT_SPREAD)

        assertEquals(mean.toList(), verdict.probePoint)
        assertEquals(BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL.size, verdict.outputs.size)
    }

    @Test
    fun wrongInputSizeIsRefused() {
        val net = AimiNeuralNetwork(3, 2, 1)

        val verdict = BasalHeadHealthGate.check(net, BasalHeadHealthGate.BASAL_OUTPUT_RANGE, BasalHeadHealthGate.CONSTANT_SPREAD)

        assertEquals(BasalHeadHealthGate.Reason.SIZE, verdict.reason)
        assertFalse(verdict.ok)
    }

    @Test
    fun stampIsReadBackAndChoosesTheConstantOnlyThreshold() {
        val text = """{"schemaVersion":2,"healthGate":{"version":1,"baselineErrorRatio":0.61}}"""
        val fields = BasalHeadHealthGate.stampFields(0.61)
        assertEquals(setOf(BasalHeadHealthGate.STAMP_KEY), fields.keys)

        val stamp = BasalHeadHealthGate.readStamp(text)

        assertNotNull(stamp)
        assertEquals(BasalHeadHealthGate.GATE_VERSION, stamp.version)
        assertEquals(0.61, stamp.baselineErrorRatio)
        assertEquals(BasalHeadHealthGate.CONSTANT_SPREAD, BasalHeadHealthGate.minSpreadFor(stamp))
    }

    @Test
    fun fileWithoutStampKeepsTheLegacyThreshold() {
        val stamp = BasalHeadHealthGate.readStamp("""{"schemaVersion":2}""")

        assertNull(stamp)
        assertEquals(BasalHeadHealthGate.LEGACY_MIN_SPREAD, BasalHeadHealthGate.minSpreadFor(stamp))
        assertNull(BasalHeadHealthGate.readStamp("not json"))
    }

    @Test
    fun nonFiniteErrorRatioIsLeftOutOfTheStamp() {
        val stamp = BasalHeadHealthGate.stampFields(Double.NaN)[BasalHeadHealthGate.STAMP_KEY].toString()

        assertFalse(stamp.contains("NaN"), stamp)
        assertNull(BasalHeadHealthGate.readStamp("""{"healthGate":$stamp}""")?.baselineErrorRatio)
    }

    /**
     * Every basal model was trained with `duraAvg` (input 4) always 0. The old loader probed at 45, where the
     * weights were never trained, so the two sides could disagree. Now both probe at the input mean.
     */
    @Test
    fun modelPublishedOnDataWithDuraAvgAlwaysZeroPassesTheLoadGate() {
        val storage = InMemoryAimiStorage()
        val target = storage.file("basal_adaptive_weights.json")
        val rows = (0 until 200).map { i ->
            val bg = 80f + 220f * i / 199
            FloatArray(inputSize) { f ->
                when (f) {
                    0    -> bg
                    1    -> 1.0f
                    2    -> 0.1f
                    3    -> 30f
                    4    -> 0f
                    5    -> 1.5f
                    else -> 0.5f
                }
            }
        }
        val targets = rows.map { doubleArrayOf((0.7 + (it[0] - 80f) / 220f * 0.8).coerceIn(0.5, 2.0)) }
        val notes = mutableListOf<String>()
        val range = BasalHeadHealthGate.BASAL_OUTPUT_RANGE

        val published = NeuralModelTrainer.trainAndPublish(
            storage = storage,
            weightsPath = target,
            split = NeuralModelTrainer.split80_20(rows, targets),
            config = TrainingConfig(learningRate = 0.01, epochs = 200, patience = 20),
            inputSize = inputSize,
            outputRange = range,
            minOutputSpread = 0.0,
            maxBaselineErrorRatio = 0.95,
            finalGate = { candidate, ratio ->
                val verdict = BasalHeadHealthGate.check(candidate, range, BasalHeadHealthGate.CONSTANT_SPREAD)
                if (verdict.ok) BasalHeadHealthGate.stampFields(ratio) else null
            },
            log = { notes += it },
        )

        assertNotNull(published, notes.toString())
        val stamp = BasalHeadHealthGate.readStamp(storage.readText(target) ?: "")
        assertNotNull(stamp)
        assertTrue((stamp.baselineErrorRatio ?: 1.0) <= 0.95)

        // The real loader reads the file it just published.
        val preferences = MapPreferences()
        preferences.booleans[BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled] = true
        val learner = BasalNeuralLearner(preferences, storage, NoOpAapsLogger)

        val status = learner.basalModelStatus()
        assertEquals(BasalNeuralLearner.ModelOrigin.CURRENT, status.origin)
        assertEquals(BasalHeadHealthGate.Reason.OK, status.targetGate)
        val decision = learner.getUniversalBasalDecision(bg = 140.0, basal = 1.0, accel = 0.1, duraMin = 30.0, duraAvg = 0.0, iob = 1.5)
        assertEquals(BasalNeuralLearner.BasalMultiplierSource.NEURAL, decision.source)
    }
}
