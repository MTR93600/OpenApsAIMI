package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The one health check of the two basal heads (Universal Adaptive Basal and T3C Brittle Mode).
 *
 * The SAME function runs at publish time (`BasalMlTrainingCoordinator`, on the trained candidate) and at load
 * time (`BasalNeuralLearner`, on the file). Before, the two sides probed at different points: the trainer at
 * the training median, the loader at a fixed vector with `duraAvg = 45`. No basal or T3C model was ever
 * trained with a non-zero `duraAvg`, so the loader read the model where its weights were never trained, and
 * the two sides could disagree about one model.
 *
 * **Probe point.** The model's own input mean (`AimiNeuralNetwork.inputMeanCopy`), which is stored in the
 * weight file. Only feature [BG_FEATURE_INDEX] moves: it takes each value of
 * [BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL] in turn. So the question is always "does the answer
 * move with BG, at a place this model has really seen?".
 *
 * **What it rejects**, in this order (see [Reason]): a wrong input size, a non-finite answer, an answer
 * outside the head's publish range, and a model whose answers move less than the minimum spread.
 *
 * **Minimum spread.** The spread check is only here to catch a constant model (the dead file of July 2026
 * moved 3.4e-08). The quality of a model is judged at publish time by the held-out error against the median
 * label (`BasalMlTrainingCoordinator.MAX_BASELINE_MAE_RATIO`), which is the only check measured to separate a
 * model that learned from one that fitted noise. A file that carries a gate stamp ([STAMP_KEY], written at
 * publish time) passed that error check, so it is judged at [CONSTANT_SPREAD]. A file without the stamp was
 * published before this gate existed, maybe before the error check existed (about 2026-10-09), so it keeps
 * the old rule [LEGACY_MIN_SPREAD].
 */
object BasalHeadHealthGate {

    /** Why a model was refused. [OK] means it passed. */
    enum class Reason { OK, CONSTANT, RANGE, NONFINITE, SIZE }

    /**
     * Result of one [check].
     *
     * [outputs] are the answers at each BG anchor (empty when the check stopped before the sweep), [spread] is
     * their max minus min (`NaN` when not measured), and [probePoint] is the vector the sweep started from.
     */
    class Verdict(
        val reason: Reason,
        val outputs: List<Double>,
        val spread: Double,
        val minSpread: Double,
        val probePoint: List<Double>,
    ) {

        val ok: Boolean get() = reason == Reason.OK
    }

    /** What the publish step wrote into the weight file. [baselineErrorRatio] is `null` when not measured. */
    data class Stamp(val version: Int, val baselineErrorRatio: Double?)

    /** Index of the BG column in the model input (`BasalNeuralLearner.modelInput`). */
    const val BG_FEATURE_INDEX = 0

    /** Version of this gate, written into the stamp. Raise it when the rule changes. */
    const val GATE_VERSION = 1

    /**
     * Smallest spread over the BG anchors for a stamped file. Only a constant model moves less.
     *
     * The dead model of July 2026 moved 3.4e-08, so this is more than 10 000 times above it. Models that really
     * learned moved 0.042 .. 0.158 (T3C head) and 0.073 .. 0.143 (basal head) on patient-shaped data.
     */
    const val CONSTANT_SPREAD = 1e-3

    /** Smallest spread for a file without a stamp: the rule those files were always judged by. */
    const val LEGACY_MIN_SPREAD = 0.05

    /** Name of the stamp object in the weight JSON. Unknown keys are ignored by `AimiNeuralNetwork.fromJsonString`. */
    const val STAMP_KEY = "healthGate"

    private const val STAMP_VERSION = "version"
    private const val STAMP_ERROR_RATIO = "baselineErrorRatio"

    /**
     * Range each head may answer in = the range it may publish in. Values inside the range but outside the
     * runtime clamp are accepted here and clamped later, so the decision log can still show the raw value.
     */
    val BASAL_OUTPUT_RANGE: ClosedFloatingPointRange<Double> = 0.5..2.0
    val T3C_OUTPUT_RANGE: ClosedFloatingPointRange<Double> = 0.3..3.0

    /** The minimum spread a file with this [stamp] is judged at. */
    fun minSpreadFor(stamp: Stamp?): Double =
        if (stamp != null && stamp.version >= GATE_VERSION) CONSTANT_SPREAD else LEGACY_MIN_SPREAD

    /** Checks [net]. Never throws: a model that cannot be run is [Reason.NONFINITE]. */
    fun check(net: AimiNeuralNetwork, outputRange: ClosedFloatingPointRange<Double>, minSpread: Double): Verdict {
        val mean = net.inputMeanCopy()
        val probePoint = mean.toList()
        if (mean.size != BasalMlSchema.INPUT_SIZE) {
            return Verdict(Reason.SIZE, emptyList(), Double.NaN, minSpread, probePoint)
        }

        val outputs = try {
            BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL.map { bg ->
                val input = FloatArray(mean.size) { mean[it].toFloat() }
                input[BG_FEATURE_INDEX] = bg.toFloat()
                net.predict(input).firstOrNull() ?: Double.NaN
            }
        } catch (_: Exception) {
            return Verdict(Reason.NONFINITE, emptyList(), Double.NaN, minSpread, probePoint)
        }
        if (outputs.any { !it.isFinite() }) {
            return Verdict(Reason.NONFINITE, outputs, Double.NaN, minSpread, probePoint)
        }

        val spread = outputs.max() - outputs.min()
        val reason = when {
            outputs.any { it !in outputRange } -> Reason.RANGE
            spread < minSpread                 -> Reason.CONSTANT
            else                               -> Reason.OK
        }
        return Verdict(reason, outputs, spread, minSpread, probePoint)
    }

    /**
     * The fields the publish step adds to the weight JSON. A non-finite [baselineErrorRatio] is left out,
     * because `NaN` is not valid JSON and would make the whole file unreadable.
     */
    fun stampFields(baselineErrorRatio: Double): Map<String, JsonElement> =
        mapOf(
            STAMP_KEY to buildJsonObject {
                put(STAMP_VERSION, GATE_VERSION)
                if (baselineErrorRatio.isFinite()) put(STAMP_ERROR_RATIO, baselineErrorRatio)
            }
        )

    /** The stamp in a weight file, or `null` when the file has none (published before this gate) or is not JSON. */
    fun readStamp(text: String): Stamp? =
        try {
            val stamp = (Json.parseToJsonElement(text) as? JsonObject)?.get(STAMP_KEY) as? JsonObject
            val version = (stamp?.get(STAMP_VERSION) as? JsonPrimitive)?.intOrNull
            if (stamp == null || version == null) {
                null
            } else {
                Stamp(version, (stamp[STAMP_ERROR_RATIO] as? JsonPrimitive)?.doubleOrNull)
            }
        } catch (_: Exception) {
            null
        }
}
