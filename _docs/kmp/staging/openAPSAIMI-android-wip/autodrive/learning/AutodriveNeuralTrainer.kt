package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_ENGAGED
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_FUTURE_BG
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_HYPER
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_HYPO
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_PHYSIO_MASK
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_TIMESTAMP
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.VERSION_CGM_LABELLED
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.exp
import kotlin.math.ln

/**
 * 🧠 Autodrive Neural Trainer (on-device learning)
 *
 * Fits the logistic model behind [MechanismAttentionGate]: given the physiological stress mask and
 * whether Autodrive drove the tick, how likely is a hypoglycaemia in the next hour.
 *
 * ## Why the previous objective could not produce a model
 *
 * It was full-batch gradient descent, 100 epochs at a learning rate of 0.01, from a zero
 * initialisation, on a positive class measured at **0.53 %** of the production corpus (91 of 17 011
 * labelled rows). Almost the whole gradient budget goes into the intercept, which has to travel to
 * `ln(0.0053 / 0.9947) ≈ -5.2`; after 100 epochs it has moved about -0.45. The output was neither
 * fitted nor calibrated — it was an intercept caught halfway.
 *
 * Three things changed, and no more than three:
 *
 * 1. **Class-balanced loss.** Each class contributes the same total weight, so the fit is about the
 *    features rather than about the base rate.
 * 2. **Prior correction on save.** Balancing calibrates the output to a 50 % prior; left there, the
 *    gate's `score > 0.5` test would fire on roughly half of all ticks — a systematic sensitivity
 *    inflation dressed up as a detection. The intercept is shifted back onto the true base rate
 *    before the weights are written, so a score above 0.5 keeps meaning "more likely than not".
 * 3. **A chronological holdout gate.** The last [HOLDOUT_FRACTION] of the corpus by time is never
 *    trained on. The new model replaces the incumbent only if it beats **both** the incumbent and a
 *    base-rate-only predictor on that holdout, by [MIN_IMPROVEMENT]. Otherwise the incumbent stays.
 *
 * Features are already bounded in [0, 1] by construction — the mask entries are confidences and
 * `Engaged` is an indicator — so there is nothing to normalise. Measured on the corpus: mask ranges
 * were [0, 1.000], [0, 0.963], [0, 0.990].
 *
 * ## Rows this refuses to learn from
 *
 * Rows written before schema [VERSION_CGM_LABELLED] carry an outcome label produced by the old
 * CSV-based pass, which gave up at the first coverage gap and wrote `0`. They also have no `Engaged`
 * field. Reading a missing field as `1.0`, as this class used to, made `Engaged` a perfect proxy for
 * "labelled by the broken method" — the model would have fitted a weight to the labelling bug. Those
 * rows are skipped here and re-derived by [AutodriveDataBackfiller].
 */
@SingleIn(AppScope::class)
class AutodriveNeuralTrainer @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper
) {
    companion object {

        @Volatile
        var instance: AutodriveNeuralTrainer? = null
            internal set

        const val WEIGHTS_FILE_NAME = "autodrive_attention_weights.json"

        /** Learning rate. Usable because the loss is class-balanced and the features are bounded. */
        const val LEARNING_RATE = 0.1
        const val EPOCHS = 300

        /** Trailing share of the corpus, by time, kept out of training and used to gate the write. */
        const val HOLDOUT_FRACTION = 0.2

        /** No fit is attempted below this. A handful of positives cannot support four parameters. */
        const val MIN_POSITIVES_TRAIN = 20
        const val MIN_POSITIVES_HOLDOUT = 5
        const val MIN_ROWS = 500

        /** Balanced log-loss must fall by at least this much to displace the incumbent. */
        const val MIN_IMPROVEMENT = 0.005

        /**
         * Hard bound on what can be written. A degenerate fit cannot then produce an extreme
         * multiplier downstream, whatever the data looked like.
         */
        const val MAX_ABS_WEIGHT = 5.0
    }

    init {
        // Scheduling lives in AimiMlTrainingScheduler, which the plugin starts and stops. Enqueuing
        // from a constructor fires at DI graph construction, in no defined order, and silently does
        // nothing when the class is never instantiated — which is exactly what happened here.
        instance = this
    }

    private val csvFileName = AutodriveDataLake.FILE_NAME

    /** Result of one training run, for logging and for the liveness export. */
    data class TrainingReport(
        val accepted: Boolean,
        val reason: String,
        val rows: Int = 0,
        val positives: Int = 0,
        val holdoutRows: Int = 0,
        val holdoutPositives: Int = 0,
        val candidateLoss: Double = Double.NaN,
        val incumbentLoss: Double = Double.NaN,
        val baseRateLoss: Double = Double.NaN,
    )

    /** Last run, whether it produced a model or not. Null until the trainer has run once. */
    @Volatile
    var lastReport: TrainingReport? = null
        private set

    /**
     * Trains the attention weights and writes them only if they beat what is already installed.
     *
     * @return true when new weights were written.
     */
    fun trainAttentionWeights(): Boolean {
        val dataset = readDataset() ?: return report(TrainingReport(false, "dataset unreadable"))

        if (dataset.size < MIN_ROWS) {
            return report(TrainingReport(false, "only ${dataset.size} usable rows", rows = dataset.size))
        }

        // Chronological split: a random split would let a tick predict its own neighbour five minutes
        // later, and report a skill the model does not have.
        val ordered = dataset.sortedBy { it.timestampMs }
        val splitAt = ((1.0 - HOLDOUT_FRACTION) * ordered.size).toInt().coerceIn(1, ordered.size - 1)
        val train = ordered.subList(0, splitAt)
        val holdout = ordered.subList(splitAt, ordered.size)

        val trainPositives = train.count { it.targetHypo > 0.5 }
        val holdoutPositives = holdout.count { it.targetHypo > 0.5 }
        val base = TrainingReport(
            accepted = false, reason = "",
            rows = ordered.size, positives = trainPositives + holdoutPositives,
            holdoutRows = holdout.size, holdoutPositives = holdoutPositives,
        )
        if (trainPositives < MIN_POSITIVES_TRAIN) {
            return report(base.copy(reason = "only $trainPositives positives in the training split"))
        }
        if (holdoutPositives < MIN_POSITIVES_HOLDOUT) {
            return report(base.copy(reason = "only $holdoutPositives positives in the holdout"))
        }

        val model = fitBalanced(train, trainPositives)
        val candidateLoss = balancedLogLoss(model, holdout, holdoutPositives)
        val baseRateLoss = balancedLogLoss(
            // A constant predictor at the balanced prior: the score any model must beat.
            Model(DoubleArray(FEATURE_COUNT) { 0.0 }, 0.0), holdout, holdoutPositives,
        )
        val incumbentLoss = loadIncumbent()?.let { balancedLogLoss(it, holdout, holdoutPositives) } ?: Double.NaN

        val measured = base.copy(
            candidateLoss = candidateLoss, incumbentLoss = incumbentLoss, baseRateLoss = baseRateLoss,
        )

        if (!candidateLoss.isFinite()) {
            return report(measured.copy(reason = "holdout loss is not finite"))
        }
        if (candidateLoss > baseRateLoss - MIN_IMPROVEMENT) {
            return report(measured.copy(reason = "no better than the base rate on the holdout"))
        }
        if (incumbentLoss.isFinite() && candidateLoss > incumbentLoss - MIN_IMPROVEMENT) {
            return report(measured.copy(reason = "no better than the installed weights"))
        }

        val positiveRate = (trainPositives + holdoutPositives).toDouble() / ordered.size
        val saved = saveLearnedWeights(model, positiveRate, measured)
        return report(measured.copy(accepted = saved, reason = if (saved) "installed" else "save failed"))
    }

    private fun report(r: TrainingReport): Boolean {
        lastReport = r
        aapsLogger.info(
            LTag.APS,
            "NeuralTrainer: ${if (r.accepted) "installed" else "kept incumbent"} — ${r.reason} " +
                "(rows=${r.rows}, positives=${r.positives}, holdout=${r.holdoutRows}/${r.holdoutPositives}, " +
                "loss cand=${"%.4f".format(r.candidateLoss)} base=${"%.4f".format(r.baseRateLoss)} " +
                "incumbent=${"%.4f".format(r.incumbentLoss)})",
        )
        return r.accepted
    }

    /** Reads the corpus under [AutodriveDatasetLock], or null when the file cannot be read. */
    private fun readDataset(): List<TrainingExample>? = AutodriveDatasetLock.withDataset {
        val file = storageHelper.getAimiFile(csvFileName)
        if (!file.exists()) return@withDataset null

        val dataset = mutableListOf<TrainingExample>()
        try {
            file.useLines { lines ->
                lines.drop(1).forEach { line ->
                    parseRow(line)?.let { dataset.add(it) }
                }
            }
            dataset
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "NeuralTrainer Error reading dataset: ${e.message}")
            null
        }
    }

    private fun parseRow(line: String): TrainingExample? {
        val cols = line.split(",")
        if (cols.size <= IDX_HYPER) return null
        // Only rows whose outcome came from CGM history, and which therefore also carry `Engaged`.
        if (AutodriveDatasetSchema.versionOf(cols) < VERSION_CGM_LABELLED) return null
        if (cols[IDX_FUTURE_BG].isBlank()) return null

        val timestampMs = cols[IDX_TIMESTAMP].toLongOrNull() ?: return null
        val hypo = cols[IDX_HYPO].trim().toDoubleOrNull() ?: return null
        val engaged = cols.getOrNull(IDX_ENGAGED)?.trim()?.toDoubleOrNull() ?: return null

        val physioStr = cols[IDX_PHYSIO_MASK]
        val physio = if (physioStr != "0" && physioStr.isNotBlank()) {
            physioStr.split("|").map { it.toDoubleOrNull() ?: 0.0 }
        } else {
            listOf(0.0, 0.0, 0.0) // [HR, Inflammation, WCycle]
        }

        // Fixed length whatever the mask string held: a shorter vector further down the file would
        // otherwise break the gradient loop.
        val features = doubleArrayOf(
            physio.getOrElse(0) { 0.0 },
            physio.getOrElse(1) { 0.0 },
            physio.getOrElse(2) { 0.0 },
            engaged,
        )
        return TrainingExample(timestampMs, features, if (hypo > 0.5) 1.0 else 0.0)
    }

    /**
     * Class-balanced logistic regression.
     *
     * Positives and negatives contribute the same total weight, so the intercept settles near zero
     * and the epochs are spent on the features rather than on the base rate.
     */
    private fun fitBalanced(train: List<TrainingExample>, positives: Int): Model {
        val negatives = train.size - positives
        val wPos = if (positives > 0) 0.5 / positives else 0.0
        val wNeg = if (negatives > 0) 0.5 / negatives else 0.0

        val weights = DoubleArray(FEATURE_COUNT) { 0.0 }
        var bias = 0.0

        for (epoch in 0 until EPOCHS) {
            val dw = DoubleArray(FEATURE_COUNT) { 0.0 }
            var db = 0.0
            for (example in train) {
                val w = if (example.targetHypo > 0.5) wPos else wNeg
                val error = (sigmoid(score(weights, bias, example.features)) - example.targetHypo) * w
                for (i in 0 until FEATURE_COUNT) dw[i] += error * example.features[i]
                db += error
            }
            for (i in 0 until FEATURE_COUNT) weights[i] -= LEARNING_RATE * dw[i]
            bias -= LEARNING_RATE * db
        }

        for (i in 0 until FEATURE_COUNT) weights[i] = weights[i].coerceIn(-MAX_ABS_WEIGHT, MAX_ABS_WEIGHT)
        return Model(weights, bias.coerceIn(-MAX_ABS_WEIGHT, MAX_ABS_WEIGHT))
    }

    /** Class-balanced log-loss, so a model that only predicts the majority class scores no better. */
    private fun balancedLogLoss(model: Model, data: List<TrainingExample>, positives: Int): Double {
        val negatives = data.size - positives
        if (positives == 0 || negatives == 0) return Double.NaN
        val wPos = 0.5 / positives
        val wNeg = 0.5 / negatives
        var loss = 0.0
        for (example in data) {
            val p = sigmoid(score(model.weights, model.bias, example.features)).coerceIn(1e-9, 1 - 1e-9)
            val w = if (example.targetHypo > 0.5) wPos else wNeg
            loss -= w * if (example.targetHypo > 0.5) ln(p) else ln(1 - p)
        }
        return loss
    }

    private fun score(weights: DoubleArray, bias: Double, features: DoubleArray): Double {
        var z = bias
        for (i in 0 until FEATURE_COUNT) {
            if (i < features.size) z += weights[i] * features[i]
        }
        return z
    }

    private fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z.coerceIn(-20.0, 20.0)))

    /** The installed model, in its balanced (uncalibrated) form so the comparison is like for like. */
    private fun loadIncumbent(): Model? = try {
        val file = storageHelper.getAimiFile(WEIGHTS_FILE_NAME)
        if (!file.exists() || file.length() == 0L) {
            null
        } else {
            val json = JSONObject(file.readText())
            // `bias_balanced` is absent from files written before the holdout gate existed; their
            // `bias` was the balanced one, because no prior correction was applied.
            val bias = if (json.has("bias_balanced")) json.optDouble("bias_balanced", 0.0) else json.optDouble("bias", 0.0)
            Model(
                doubleArrayOf(
                    json.optDouble("weight_hr", 0.0),
                    json.optDouble("weight_inflammation", 0.0),
                    json.optDouble("weight_hormonal", 0.0),
                    json.optDouble("weight_engaged", 0.0),
                ),
                bias,
            )
        }
    } catch (e: Exception) {
        aapsLogger.warn(LTag.APS, "NeuralTrainer: installed weights unreadable, treated as absent — ${e.message}")
        null
    }

    /**
     * Writes the weights with the intercept moved back onto the true base rate.
     *
     * Training balanced the classes, which calibrates the output to a 50 % prior. The gate compares
     * the score with 0.5, so leaving it balanced would make the defensive arm fire on about half of
     * all ticks — a permanent sensitivity inflation, not a detection. Subtracting the log-odds of the
     * balancing restores a genuine probability.
     */
    private fun saveLearnedWeights(model: Model, positiveRate: Double, report: TrainingReport): Boolean {
        return try {
            val safeRate = positiveRate.coerceIn(1e-6, 1.0 - 1e-6)
            val priorCorrection = ln(safeRate / (1.0 - safeRate))
            val calibratedBias = (model.bias + priorCorrection).coerceIn(-MAX_ABS_WEIGHT * 4, MAX_ABS_WEIGHT * 4)

            val json = JSONObject()
            json.put("bias", calibratedBias)
            json.put("bias_balanced", model.bias)
            json.put("weight_hr", model.weights[0])
            json.put("weight_inflammation", model.weights[1])
            json.put("weight_hormonal", model.weights[2])
            json.put("weight_engaged", model.weights[3])
            json.put("timestamp", System.currentTimeMillis())
            // Liveness: "the model says safe" and "there is no model" must not look the same.
            json.put("rows", report.rows)
            json.put("positives", report.positives)
            json.put("positive_rate", positiveRate)
            json.put("holdout_rows", report.holdoutRows)
            json.put("holdout_positives", report.holdoutPositives)
            json.put("holdout_balanced_log_loss", report.candidateLoss)
            json.put("holdout_base_rate_log_loss", report.baseRateLoss)
            json.put("dataset_schema_version", AutodriveDatasetSchema.CURRENT_VERSION)

            storageHelper.saveFileSafe(storageHelper.getAimiFile(WEIGHTS_FILE_NAME), json.toString(4))
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "NeuralTrainer Error saving weights: ${e.message}")
            false
        }
    }

    private data class Model(val weights: DoubleArray, val bias: Double) {

        override fun equals(other: Any?): Boolean =
            other is Model && bias == other.bias && weights.contentEquals(other.weights)

        override fun hashCode(): Int = 31 * weights.contentHashCode() + bias.hashCode()
    }

    private data class TrainingExample(
        val timestampMs: Long,
        val features: DoubleArray,
        /** 1.0 = a hypoglycaemia followed within the hour. */
        val targetHypo: Double,
    ) {

        override fun equals(other: Any?): Boolean =
            other is TrainingExample && timestampMs == other.timestampMs &&
                targetHypo == other.targetHypo && features.contentEquals(other.features)

        override fun hashCode(): Int =
            31 * (31 * timestampMs.hashCode() + features.contentHashCode()) + targetHypo.hashCode()
    }
}

/** Mask entries [HR, inflammation, hormonal] plus the engagement indicator. */
private const val FEATURE_COUNT = 4
