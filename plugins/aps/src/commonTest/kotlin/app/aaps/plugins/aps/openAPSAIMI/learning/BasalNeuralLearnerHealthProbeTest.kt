package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiNeuralModelStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runtime fail-safe of both learned heads: Universal Adaptive Basal and T3C Brittle Mode.
 *
 * A shipped `basal_adaptive_weights.json` was measured returning 0.19918 for every input, and the old
 * clamp floor of 0.70 turned that constant into a value that looked like a real decision on 100 % of
 * ticks. These tests pin the probe that rejects such a model and the 0.80 floor.
 *
 * The T3C head shares the network class, the file format and the trainer, so it can die the same way.
 * It had no probe and no runtime clamp at all; the tests at the end of this file pin both.
 *
 * The probe is [BasalHeadHealthGate], the same check the trainer runs before it publishes. The fixtures
 * have an input mean of 0, so the probe point is the zero vector with BG swept over the anchors. The last
 * tests pin what serves after a refused file (target, then `.bak`, then the model in memory) and the two
 * spread thresholds (stamped files 0.001, older files 0.05).
 *
 * Port of `BasalNeuralLearnerHealthProbeTest` from `dev_OAPSAIMI`. What changed:
 * - dev mocked `BasalMlModelStore` and `AimiNeuralNetwork` with mockk. Here there is no mock: the weight
 *   files are real JSON in an [InMemoryAimiStorage], read by the real loader. A "constant" model has all
 *   weights zero, and a "bg sensitive" model has one hidden unit `bg - 70`, so it answers exactly `low`
 *   at 70 mg/dL and `low + (high - low) * (bg - 70) / 180` above, the same line dev's mock drew over the
 *   probe anchors. Layer normalisation is off by default, which keeps that line exact.
 * - the learner has no `Context` parameter here. Preferences are the hand written [MapPreferences].
 * - Truth is replaced by `kotlin.test`, names are camelCase, so the test runs on every target.
 */
class BasalNeuralLearnerHealthProbeTest {

    private val storage = InMemoryAimiStorage()
    private val preferences = MapPreferences()
    private val basalFile = storage.file("basal_adaptive_weights.json")
    private val t3cFile = storage.file("t3c_brain_weights.json")

    init {
        preferences.booleans[BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled] = true
    }

    /** Weight file of a network whose only answer is [value], whatever the input. */
    private fun constantNet(value: Double): String = networkJson(bgWeight = 0.0, hiddenBias = 0.01, outWeight = 0.0, outBias = value)

    /** Weight file of a network that really answers to BG (feature index 0): [low] at 70 mg/dL, [high] at 250 mg/dL. */
    private fun bgSensitiveNet(low: Double, high: Double): String =
        networkJson(bgWeight = 1.0, hiddenBias = -70.0, outWeight = (high - low) / 180.0, outBias = low)

    /** [bgSensitiveNet] with the gate stamp a publish writes, so it is judged at the constant-only threshold. */
    private fun stamped(json: String): String =
        JsonObject(Json.parseToJsonElement(json).jsonObject + BasalHeadHealthGate.stampFields(0.6)).toString()

    private fun networkJson(bgWeight: Double, hiddenBias: Double, outWeight: Double, outBias: Double): String {
        val inputSize = BasalMlSchema.INPUT_SIZE
        fun one(v: Double) = JsonArray(listOf(JsonPrimitive(v)))
        fun vector(size: Int, v: Double) = JsonArray(List(size) { JsonPrimitive(v) })
        val text = buildJsonObject {
            put("schemaVersion", AimiNeuralNetwork.SCHEMA_VERSION)
            put("inputSize", inputSize)
            put("hiddenSize", 1)
            put("outputSize", 1)
            put("weightsInputHidden", JsonArray(List(inputSize) { i -> one(if (i == 0) bgWeight else 0.0) }))
            put("biasHidden", one(hiddenBias))
            put("weightsHiddenOutput", JsonArray(listOf(one(outWeight))))
            put("biasOutput", one(outBias))
            put("inputMean", vector(inputSize, 0.0))
            put("inputStd", vector(inputSize, 1.0))
        }.toString()
        // Guard the fixture: it must load, as the learner will load it.
        assertTrue(AimiNeuralNetwork.fromJsonString(text) != null, "fixture does not load")
        return text
    }

    /** Builds a learner. By default both heads get the same model, as the dev setup did. */
    private fun learnerWith(basalNet: String?, t3cNet: String? = basalNet): BasalNeuralLearner {
        if (basalNet != null) storage.writeText(basalFile, basalNet) else storage.delete(basalFile)
        if (t3cNet != null) storage.writeText(t3cFile, t3cNet) else storage.delete(t3cFile)
        return BasalNeuralLearner(preferences, storage, NoOpAapsLogger)
    }

    private fun decisionAt(learner: BasalNeuralLearner, bg: Double) =
        learner.getUniversalBasalDecision(bg = bg, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 45.0, iob = 0.5)

    private fun t3cAt(learner: BasalNeuralLearner, bg: Double) =
        learner.getT3cAdaptiveDecision(bg = bg, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 45.0, iob = 0.5)

    private fun assertNear(expected: Double, actual: Double) {
        assertTrue(abs(expected - actual) <= 1e-9, "expected $expected but was $actual")
    }

    @Test
    fun constantModelIsRejectedAndTheHeuristicIsUsedInstead() {
        // The exact constant measured on a real shipped weights file.
        val learner = learnerWith(constantNet(0.19918))

        val decision = decisionAt(learner, bg = 250.0)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.HEURISTIC, decision.source)
        // Heuristic starts neutral, so no basal is cut at all - the old behaviour cut 30 % forever.
        assertNear(1.0, decision.rawValue)
        assertNear(1.0, decision.multiplier)
    }

    @Test
    fun modelAnsweringOutsideThePlausibleRangeIsRejected() {
        val learner = learnerWith(bgSensitiveNet(low = 2.5, high = 4.0))

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.HEURISTIC, decisionAt(learner, bg = 140.0).source)
    }

    @Test
    fun modelThatAnswersToBgIsKept() {
        val learner = learnerWith(bgSensitiveNet(low = 0.9, high = 1.25))

        val low = decisionAt(learner, bg = 70.0)
        val high = decisionAt(learner, bg = 250.0)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.NEURAL, low.source)
        assertTrue(low.multiplier < high.multiplier)
    }

    @Test
    fun runtimeFloorIs080Not070() {
        // Healthy model (it moves with BG) but asking for a very strong cut at low BG.
        val learner = learnerWith(bgSensitiveNet(low = 0.55, high = 1.10))

        val decision = decisionAt(learner, bg = 70.0)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.NEURAL, decision.source)
        assertNear(0.55, decision.rawValue)
        assertNear(BasalNeuralLearner.RUNTIME_BASAL_FLOOR, decision.multiplier)
        assertNear(0.80, decision.multiplier)
        assertTrue(decision.clamped)
    }

    @Test
    fun rawValueSeparatesAClampedModelFromAModelThatReallyLearnedTheFloor() {
        val clamped = decisionAt(learnerWith(bgSensitiveNet(low = 0.55, high = 1.10)), bg = 70.0)
        val learned = decisionAt(learnerWith(bgSensitiveNet(low = 0.80, high = 1.10)), bg = 70.0)

        // Same applied multiplier...
        assertNear(learned.multiplier, clamped.multiplier)
        // ...told apart only by n_raw, which is the field the JSONL exports.
        assertNotEquals(learned.rawValue, clamped.rawValue)
        assertTrue(clamped.clamped)
        assertFalse(learned.clamped)
    }

    @Test
    fun defaultCeilingLeavesRoomAboveNeutralSoBasalCanBeRaised() {
        val learner = learnerWith(bgSensitiveNet(low = 1.0, high = 1.9))

        val decision = decisionAt(learner, bg = 250.0)

        // The old default of 1.0 made every boost impossible by construction.
        assertNear(1.3, decision.ceiling)
        assertTrue(decision.multiplier > 1.0)
    }

    @Test
    fun disabledFeatureReportsItsOwnSourceAndNeverScales() {
        preferences.booleans[BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled] = false
        val learner = learnerWith(bgSensitiveNet(low = 0.9, high = 1.25))

        val decision = decisionAt(learner, bg = 250.0)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.DISABLED, decision.source)
        assertNear(1.0, decision.multiplier)
    }

    // -- T3C head ---------------------------------------------------------------------------------

    @Test
    fun bgBlindT3cModelIsRejectedAndTheHeuristicIsUsedInstead() {
        // 1.5 sits inside the T3C publish range (0.3 .. 3.0), so only the spread check can catch it.
        val learner = learnerWith(basalNet = null, t3cNet = constantNet(1.5))

        val decision = t3cAt(learner, bg = 250.0)

        assertEquals(BasalNeuralLearner.T3cFactorSource.HEURISTIC, decision.source)
        // Heuristic starts neutral: the dead model scales nothing at all.
        assertNear(1.0, decision.rawMultiplier)
        assertNear(1.0, decision.multiplier)
        assertFalse(decision.clamped)
    }

    @Test
    fun deadT3cModelDoesNotDisableAHealthyBasalModel() {
        val learner = learnerWith(
            basalNet = bgSensitiveNet(low = 0.9, high = 1.25),
            t3cNet = constantNet(1.5),
        )

        assertEquals(BasalNeuralLearner.T3cFactorSource.HEURISTIC, t3cAt(learner, bg = 250.0).source)
        assertEquals(BasalNeuralLearner.BasalMultiplierSource.NEURAL, decisionAt(learner, bg = 250.0).source)
    }

    @Test
    fun t3cModelThatAnswersToBgIsKept() {
        val learner = learnerWith(basalNet = null, t3cNet = bgSensitiveNet(low = 0.8, high = 1.8))

        val low = t3cAt(learner, bg = 70.0)
        val high = t3cAt(learner, bg = 250.0)

        assertEquals(BasalNeuralLearner.T3cFactorSource.NEURAL, low.source)
        assertNear(0.8, low.multiplier)
        assertNear(1.8, high.multiplier)
        assertFalse(low.clamped)
        assertFalse(high.clamped)
    }

    @Test
    fun liveT3cFactorIsClampedTo05And20() {
        // Healthy model (it moves with bg) but it asks for the extremes of its publish range.
        val learner = learnerWith(basalNet = null, t3cNet = bgSensitiveNet(low = 0.35, high = 2.9))

        val low = t3cAt(learner, bg = 70.0)
        val high = t3cAt(learner, bg = 250.0)

        assertEquals(BasalNeuralLearner.T3cFactorSource.NEURAL, low.source)
        assertNear(0.35, low.rawMultiplier)
        assertNear(BasalNeuralLearner.RUNTIME_T3C_FACTOR_MIN, low.multiplier)
        assertNear(0.5, low.multiplier)
        assertTrue(low.clamped)

        assertNear(2.9, high.rawMultiplier)
        assertNear(BasalNeuralLearner.RUNTIME_T3C_FACTOR_MAX, high.multiplier)
        assertNear(2.0, high.multiplier)
        assertTrue(high.clamped)
    }

    @Test
    fun t3cFactorIsTheUserAggressivenessTimesTheClampedMultiplier() {
        preferences.doubles[DoubleKey.OApsAIMIT3cAggressiveness] = 1.5
        val learner = learnerWith(basalNet = null, t3cNet = bgSensitiveNet(low = 0.35, high = 2.9))

        val decision = t3cAt(learner, bg = 250.0)
        val plainFactor = learner.getT3cAdaptiveFactor(
            bg = 250.0, basal = 1.0, accel = 0.0, duraMin = 30.0, duraAvg = 45.0, iob = 0.5,
        )

        // Without the clamp this would be 1.5 * 2.9 = 4.35.
        assertNear(1.5, decision.baseAggressiveness)
        assertNear(3.0, decision.factor)
        assertNear(decision.factor, plainFactor)
    }

    @Test
    fun rawMultiplierSeparatesAClampedT3cModelFromOneThatReallyLearnedTheFloor() {
        val clamped = t3cAt(learnerWith(basalNet = null, t3cNet = bgSensitiveNet(0.35, 1.10)), bg = 70.0)
        val learned = t3cAt(learnerWith(basalNet = null, t3cNet = bgSensitiveNet(0.50, 1.10)), bg = 70.0)

        assertNear(learned.multiplier, clamped.multiplier)
        assertNotEquals(learned.rawMultiplier, clamped.rawMultiplier)
        assertTrue(clamped.clamped)
        assertFalse(learned.clamped)
    }

    @Test
    fun reloadModelsProbesTheT3cHeadToo() {
        val learner = learnerWith(basalNet = null, t3cNet = bgSensitiveNet(low = 0.8, high = 1.8))
        assertEquals(BasalNeuralLearner.T3cFactorSource.NEURAL, t3cAt(learner, bg = 250.0).source)

        // Training publishes a dead head, then the loop reloads it.
        storage.writeText(t3cFile, constantNet(1.5))
        learner.reloadModels()

        // The dead file is refused and moved aside. No backup passes, so the model already in memory stays.
        assertFalse(storage.exists(t3cFile))
        assertTrue(storage.exists(storage.sibling(t3cFile, BasalMlModelStore.REJECTED_SUFFIX)))
        val decision = t3cAt(learner, bg = 250.0)
        assertEquals(BasalNeuralLearner.T3cFactorSource.NEURAL, decision.source)
        assertNear(1.8, decision.multiplier)
    }

    // -- D4: what serves after a refused file -----------------------------------------------------

    @Test
    fun refusedTargetFallsBackToTheBackupThatPassesTheSameGate() {
        storage.writeText(storage.sibling(basalFile, ".bak"), bgSensitiveNet(low = 0.9, high = 1.25))
        val learner = learnerWith(basalNet = constantNet(1.0), t3cNet = null)

        val decision = decisionAt(learner, bg = 70.0)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.NEURAL, decision.source)
        assertNear(0.9, decision.rawValue)
        val status = learner.basalModelStatus()
        assertEquals(BasalNeuralLearner.ModelOrigin.BACKUP, status.origin)
        assertEquals(BasalHeadHealthGate.Reason.CONSTANT, status.targetGate)
    }

    @Test
    fun refusedTargetAndRefusedBackupKeepTheModelInMemory() {
        val learner = learnerWith(basalNet = bgSensitiveNet(low = 0.9, high = 1.25), t3cNet = null)
        assertEquals(BasalNeuralLearner.ModelOrigin.CURRENT, learner.basalModelStatus().origin)

        storage.writeText(basalFile, constantNet(1.0))
        storage.writeText(storage.sibling(basalFile, ".bak"), bgSensitiveNet(low = 2.5, high = 4.0))
        learner.reloadModels()

        val decision = decisionAt(learner, bg = 70.0)
        assertEquals(BasalNeuralLearner.BasalMultiplierSource.NEURAL, decision.source)
        assertNear(0.9, decision.rawValue)
        assertEquals(BasalNeuralLearner.ModelOrigin.KEPT, learner.basalModelStatus().origin)
    }

    @Test
    fun withNoModelInMemoryARefusedFileLeavesTheHeuristic() {
        val learner = learnerWith(basalNet = constantNet(1.0), t3cNet = null)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.HEURISTIC, decisionAt(learner, bg = 140.0).source)
        val status = learner.basalModelStatus()
        assertEquals(BasalNeuralLearner.ModelOrigin.NONE, status.origin)
        assertEquals(BasalHeadHealthGate.Reason.CONSTANT, status.targetGate)
    }

    @Test
    fun refusedTargetNeverRotatesIntoTheBackup() {
        val goodBackup = bgSensitiveNet(low = 0.9, high = 1.25)
        storage.writeText(storage.sibling(basalFile, ".bak"), goodBackup)
        val dead = constantNet(1.0)
        learnerWith(basalNet = dead, t3cNet = null)

        // The refused target was moved aside, so the next publish has no target to rotate.
        assertFalse(storage.exists(basalFile))
        assertEquals(dead, storage.readText(storage.sibling(basalFile, BasalMlModelStore.REJECTED_SUFFIX)))
        val next = AimiNeuralNetwork.fromJsonString(bgSensitiveNet(low = 1.0, high = 1.2))
        assertNotNull(next)
        assertTrue(AimiNeuralModelStore.save(storage, basalFile, next))

        assertEquals(goodBackup, storage.readText(storage.sibling(basalFile, ".bak")))
    }

    @Test
    fun missingTargetReportsNoGateVerdict() {
        val learner = learnerWith(basalNet = null, t3cNet = null)

        val status = learner.basalModelStatus()
        assertEquals(BasalNeuralLearner.ModelOrigin.NONE, status.origin)
        assertNull(status.targetGate)
        assertNull(status.targetSpread)
    }

    // -- D3: one threshold for stamped files, the old one for older files -------------------------

    @Test
    fun unstampedFileIsJudgedAtTheLegacySpread() {
        // Moves 0.01 over the anchors: more than a constant, less than the old 0.05 rule.
        val learner = learnerWith(basalNet = bgSensitiveNet(low = 1.0, high = 1.01), t3cNet = null)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.HEURISTIC, decisionAt(learner, bg = 140.0).source)
        assertEquals(BasalHeadHealthGate.Reason.CONSTANT, learner.basalModelStatus().targetGate)
    }

    @Test
    fun stampedFileIsJudgedAtTheConstantOnlySpread() {
        val learner = learnerWith(basalNet = stamped(bgSensitiveNet(low = 1.0, high = 1.01)), t3cNet = null)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.NEURAL, decisionAt(learner, bg = 140.0).source)
        val status = learner.basalModelStatus()
        assertEquals(BasalNeuralLearner.ModelOrigin.CURRENT, status.origin)
        assertEquals(BasalHeadHealthGate.Reason.OK, status.targetGate)
        assertNear(0.01, status.targetSpread ?: Double.NaN)
    }

    @Test
    fun stampedConstantFileIsStillRefused() {
        val learner = learnerWith(basalNet = stamped(constantNet(1.0)), t3cNet = null)

        assertEquals(BasalNeuralLearner.BasalMultiplierSource.HEURISTIC, decisionAt(learner, bg = 140.0).source)
        assertEquals(BasalHeadHealthGate.Reason.CONSTANT, learner.basalModelStatus().targetGate)
    }

    @Test
    fun bothProbesSweepTheSameClinicalBgAnchors() {
        // The publish gate must sweep this very list, or training can publish what the loader refuses.
        assertEquals(listOf(70.0, 140.0, 250.0), BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL)
    }
}
