package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Model input slot 4 (`duraAvg`) is held at [BasalMlSchema.MODEL_DURA_AVG], at run time and in the
 * training rows.
 *
 * Every model in the field was trained with 0 in this slot, because the loop never filled
 * `duraISFaverage`. Once the real value (a BG average in mg/dL) is wired, it must not reach the
 * untrained weights of that slot. The test network below has a weight on slot 4 on purpose, so a
 * value that leaks through changes the answer.
 */
class BasalModelDuraAvgHeldTest {

    private val storage = InMemoryAimiStorage()
    private val preferences = MapPreferences()

    init {
        preferences.booleans[BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled] = true
    }

    /**
     * One hidden unit `bg - 70 + duraAvg`. With slot 4 at 0 it answers 0.9 at 70 mg/dL and 1.25 at
     * 250 mg/dL, so it passes the load probe of both heads.
     */
    private fun netReadingDuraAvg(): String {
        val inputSize = BasalMlSchema.INPUT_SIZE
        fun one(v: Double) = JsonArray(listOf(JsonPrimitive(v)))
        fun vector(size: Int, v: Double) = JsonArray(List(size) { JsonPrimitive(v) })
        val text = buildJsonObject {
            put("schemaVersion", AimiNeuralNetwork.SCHEMA_VERSION)
            put("inputSize", inputSize)
            put("hiddenSize", 1)
            put("outputSize", 1)
            put("weightsInputHidden", JsonArray(List(inputSize) { i -> one(if (i == 0 || i == DURA_AVG_SLOT) 1.0 else 0.0) }))
            put("biasHidden", one(-70.0))
            put("weightsHiddenOutput", JsonArray(listOf(one(0.35 / 180.0))))
            put("biasOutput", one(0.9))
            put("inputMean", vector(inputSize, 0.0))
            put("inputStd", vector(inputSize, 1.0))
        }.toString()
        assertNotNull(AimiNeuralNetwork.fromJsonString(text), "fixture does not load")
        return text
    }

    private fun learner(): BasalNeuralLearner {
        storage.writeText(storage.file("basal_adaptive_weights.json"), netReadingDuraAvg())
        storage.writeText(storage.file("t3c_brain_weights.json"), netReadingDuraAvg())
        return BasalNeuralLearner(preferences, storage, NoOpAapsLogger)
    }

    @Test
    fun basalHeadGivesTheSameAnswerForAnyDuraAvg() {
        val learner = learner()

        val zero = learner.getUniversalBasalDecision(bg = 140.0, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 0.0, iob = 0.5)
        val real = learner.getUniversalBasalDecision(bg = 140.0, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 180.0, iob = 0.5)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.NEURAL, zero.source)
        assertEquals(zero.rawValue, real.rawValue)
        assertEquals(zero.multiplier, real.multiplier)
    }

    @Test
    fun t3cHeadGivesTheSameAnswerForAnyDuraAvg() {
        val learner = learner()

        val zero = learner.getT3cAdaptiveDecision(bg = 140.0, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 0.0, iob = 0.5)
        val real = learner.getT3cAdaptiveDecision(bg = 140.0, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 180.0, iob = 0.5)

        assertEquals(BasalNeuralLearner.T3cFactorSource.NEURAL, zero.source)
        assertEquals(zero.rawMultiplier, real.rawMultiplier)
        assertEquals(zero.factor, real.factor)
    }

    @Test
    fun parsedRowsHoldSlot4AtZeroWhateverTheCsvSays() {
        val csv = storage.file("basal_adaptive_records.csv")
        val startTs = 1_700_000_000_000L
        val stepMs = 5L * 60_000
        val lines = buildList {
            add("timestamp,bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,t3cAgg,basalScale")
            // Flat BG at target: every row with a future 30 min later gets a neutral label.
            repeat(12) { i -> add("${startTs + i * stepMs},100.0,100.0,1.0,100.0,0.0,30,120,0.5,1.0,1.0") }
        }
        storage.writeText(csv, lines.joinToString("\n") + "\n")

        val dataset = assertNotNull(BasalMlDatasetParser.parse(storage, csv))

        assertTrue(dataset.inputs.isNotEmpty())
        dataset.inputs.forEach { row ->
            assertEquals(BasalMlSchema.MODEL_DURA_AVG.toFloat(), row[DURA_AVG_SLOT])
            // The column next to it is still read from the CSV.
            assertEquals(30f, row[DURA_MIN_SLOT])
        }
    }

    @Test
    fun neutralProbeHoldsSlot4AtZero() {
        assertEquals(BasalMlSchema.MODEL_DURA_AVG.toFloat(), BasalMlDatasetParser.neutralProbeInput()[DURA_AVG_SLOT])
    }

    private companion object {
        const val DURA_MIN_SLOT = 3
        const val DURA_AVG_SLOT = 4
    }
}
