package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.ml.NeuralModelTrainer
import app.aaps.plugins.aps.openAPSAIMI.ml.SmbRefinementFeatureSchema
import app.aaps.plugins.aps.openAPSAIMI.ml.TrainingCircuitBreaker
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.abs

/**
 * Coordinated basal / T3C neural training with SMB-style safety:
 * rate limit, min new rows, circuit breaker, validation gate, atomic weights.
 */
@SingleIn(AppScope::class)
class BasalMlTrainingCoordinator @Inject constructor(
    private val storageHelper: AimiStorageHelper,
    private val basalNeuralLearner: BasalNeuralLearner,
    private val log: AAPSLogger,
) {

    companion object {
        private const val TAG = "BasalMlTraining"

        // 6 glucose-dynamics base features + 10 physiological-context features (mirror of the SMB schema:
        // 4 latent + 3 patient-mode + 3 causal). Keep in sync with BasalNeuralLearner.modelInput / the parser.
        const val BASE_FEATURE_COUNT = 6
        const val INPUT_SIZE = 16

        /**
         * Index of the bg column in the feature vector built by `BasalNeuralLearner.modelInput`.
         *
         * The spread probe sweeps this one column over [SPREAD_SWEEP_BG_MGDL] and holds every other
         * feature at its training median. Sweeping one axis at a time is on purpose: a joint
         * p10/p50/p90 vector mixes states that cannot happen together (p90 bg with p90 iob) and can
         * show a healthy looking spread on a model that is in fact blind to bg.
         */
        internal const val BG_FEATURE_INDEX = 0

        /**
         * The bg values the spread probe sweeps: the SAME fixed clinical anchors the runtime health
         * probe uses (`BasalNeuralLearner.answersToBg`).
         *
         * The two must not drift apart. The runtime probe nulls a model that does not answer across
         * these anchors, so a publish gate that measured a different window could publish a model its
         * own loader then refuses: training reports success, the runtime silently serves the heuristic,
         * and no log line links the two. `BasalMlTrainingCoordinatorTest` fails if they diverge.
         *
         * The gate used to sweep the p10 .. p90 of the training bg column instead. On real field data
         * (one device, 24 h, 260 ticks) that window was 79.2 .. 157.6 mg/dL, 78.4 mg/dL wide: 0.44x of
         * the clinical span, so it understated that model's bg response by about 2.3x while the
         * threshold below is written in full-range units. Worse, the window is narrowest for a
         * well-controlled patient, so the old gate was loosest exactly where a wrong model is hardest
         * to notice.
         */
        internal val SPREAD_SWEEP_BG_MGDL: List<Double> = BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL

        /**
         * Smallest output spread we accept over the bg sweep, in multiplier units.
         *
         * A model that answers the same value for every bg is useless, and it is not a rare case: the
         * model shipped on 12 July 2026 answered 0.19918 for every input, with a spread of 3.4e-08 over
         * bg 40..400. It stayed in place for 40 days. The threshold is in real multiplier units on
         * purpose, because a spread of 0.001 would pass any plain "not a constant" test while being the
         * same thing as a constant for the patient.
         *
         * The value is now DERIVED, not inherited. Two measurements fix it, both on patient-shaped data
         * with the fixed training seed, 10 seeds per side (`AdversarialBasalLearningTest`):
         * - over the anchors, models that really learned moved by 0.073 .. 0.143 (basal head) and
         *   0.042 .. 0.158 (T3C head). Raising this number above 0.05 starts rejecting them.
         * - models trained on pure label noise moved MORE, not less: 0.159 .. 0.208. So no spread
         *   threshold at all separates a live model from a noise model, and lowering this number buys
         *   nothing either. Rejecting noise is [MAX_BASELINE_MAE_RATIO]'s job, not this one's.
         *
         * It also cannot go below `BasalNeuralLearner.PROBE_MIN_SPREAD`, or the loader would refuse
         * what this gate just published. That runtime constant is 0.05 and is shared by both heads,
         * which is why ONE shared number is kept here as well instead of one per head: a per-head
         * value under 0.05 could never be served. The T3C head pays for that — 2 of its 10 genuinely
         * learned models sit under 0.05 — but they would be dropped at load time anyway.
         */
        internal const val MIN_OUTPUT_SPREAD = 0.05

        /**
         * The candidate's held-out mean absolute error must be at most this fraction of the best
         * constant predictor's (the median training label). 0.95 asks for at least 5% better.
         *
         * This is the gate that tells a model that learned from a model that only fitted noise, and it
         * is the only one measured to do so. Over 10 seeds of patient-shaped data the models that
         * learned scored 0.476 .. 0.705 of the baseline, and the pure-noise models 0.999 .. 1.052 —
         * they are slightly WORSE than answering the median. 0.95 sits between the two with margin on
         * both sides (0.245 on the signal side, 0.049 on the noise side).
         *
         * A model that only just beats the median is refused on purpose: it would join the dosing path
         * for almost no gain, and the heuristic fallback is the safe alternative.
         */
        internal const val MAX_BASELINE_MAE_RATIO = 0.95
        private const val TRAIN_INTERVAL_MS = 1L * 60 * 60 * 1000 // 1h — matches the 1h worker cadence; the MIN_NEW_ROWS gate still prevents retraining without new data
        private const val MIN_NEW_ROWS = 80L
        private const val BASAL_MIN_ROWS = 100
        private const val T3C_MIN_ROWS = 50
        private const val VAL_LOSS_TOLERANCE = 1.05
        private const val STATE_FILE = "basal_ml_training_state.json"
        private const val CSV_FILE = "basal_adaptive_records.csv"
        private const val BASAL_WEIGHTS = "basal_adaptive_weights.json"
        private const val T3C_WEIGHTS = "t3c_brain_weights.json"

        @Volatile
        var instance: BasalMlTrainingCoordinator? = null
            internal set
    }

    enum class TrainingOutcome {
        SUCCESS,
        SKIPPED,
        FAILED_RETRY,
    }

    private val trainMutex = Mutex()
    private val trainScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastTrainMs = AtomicLong(0L)
    private val rowsAtLastTrain = AtomicLong(0L)
    private val circuitBreaker = TrainingCircuitBreaker()

    init {
        instance = this
        loadPersistedState()
    }

    /**
     * Fire-and-forget training trigger (SMB-style): called from the loop hot path after each basal record.
     * Never blocks the caller; [runScheduledTraining] enforces rate limit, min new rows, and mutex.
     */
    fun maybeTrainAsync() {
        trainScope.launch {
            if (trainMutex.isLocked) return@launch
            try {
                runScheduledTraining()
            } catch (e: Exception) {
                log.error(LTag.APS, "$TAG: maybeTrainAsync failed", e)
            }
        }
    }

    suspend fun runScheduledTraining(): TrainingOutcome = trainMutex.withLock {
        val now = System.currentTimeMillis()
        // First-ever model creation bypasses the rate limit: if no basal weights exist yet, train now (bootstrap) so
        // the model is created ASAP from the already-accumulated CSV, instead of waiting for the next 6h window.
        val bootstrapNeeded = !storageHelper.getAimiFile(BASAL_WEIGHTS).exists()
        if (isCircuitOpen(now)) {
            log.debug(LTag.APS, "$TAG: circuit breaker open — skip")
            return TrainingOutcome.SKIPPED
        }
        if (!bootstrapNeeded && now - lastTrainMs.get() < TRAIN_INTERVAL_MS) {
            log.debug(LTag.APS, "$TAG: rate limit — skip")
            return TrainingOutcome.SKIPPED
        }

        val csvFile = storageHelper.getAimiFile(CSV_FILE)
        if (!csvFile.exists()) {
            log.debug(LTag.APS, "$TAG: CSV missing — skip")
            return TrainingOutcome.SKIPPED
        }

        val parsed = BasalMlDatasetParser.parse(csvFile) ?: run {
            log.debug(LTag.APS, "$TAG: CSV parse failed — skip")
            return TrainingOutcome.SKIPPED
        }

        log.debug(LTag.APS, "$TAG: label set ${parsed.rowCount} rows kept (${parsed.stats})")

        val totalRows = parsed.rowCount.toLong()
        val newRows = totalRows - rowsAtLastTrain.get()
        if (newRows < MIN_NEW_ROWS) {
            log.debug(LTag.APS, "$TAG: only $newRows new rows (need $MIN_NEW_ROWS) — skip")
            return TrainingOutcome.SKIPPED
        }

        // Training is DECOUPLED from usage: both heads are trained whenever enough recorded data exists, so the
        // weights files (basal_adaptive_weights.json / t3c_brain_weights.json) are always produced and kept fresh.
        // The feature prefs (OApsAIMIT3cAdaptiveBasalEnabled / OApsAIMIT3cBrittleMode) gate only whether
        // BasalNeuralLearner APPLIES the models at runtime — never whether they are trained. This guarantees a model
        // is ready the instant the feature is enabled, instead of starting from an empty model. Recording is likewise
        // pref-independent (BasalNeuralLearner.logRecord), so the dataset keeps growing for everyone.
        return try {
            var published = false
            var hardFailure = false

            if (parsed.rowCount >= BASAL_MIN_ROWS) {
                when (trainBasalHead(parsed)) {
                    HeadTrainResult.PUBLISHED -> published = true
                    HeadTrainResult.FAILED -> hardFailure = true
                    HeadTrainResult.SKIPPED -> Unit
                }
            }

            if (parsed.rowCount >= T3C_MIN_ROWS) {
                when (trainT3cHead(parsed)) {
                    HeadTrainResult.PUBLISHED -> published = true
                    HeadTrainResult.FAILED -> hardFailure = true
                    HeadTrainResult.SKIPPED -> Unit
                }
            }

            when {
                hardFailure -> {
                    recordFailure()
                    TrainingOutcome.FAILED_RETRY
                }
                published -> {
                    markAttemptCompleted(now, totalRows)
                    circuitBreaker.reset()
                    basalNeuralLearner.reloadModels()
                    log.info(LTag.APS, "$TAG: training complete — models reloaded ($totalRows CSV rows)")
                    TrainingOutcome.SUCCESS
                }
                else -> {
                    // An attempt that ran to completion without beating the incumbent still counts.
                    //
                    // The rate limit and the min-new-rows gate both keyed on the last *publish*. Once a
                    // model stopped being beaten, `newRows` kept growing past the threshold and nothing
                    // ever reset the clock — so a full 200+300-epoch training over the whole CSV ran on
                    // every tick, forever, on a phone.
                    markAttemptCompleted(now, totalRows)
                    log.debug(LTag.APS, "$TAG: candidate did not beat the incumbent — attempt recorded")
                    TrainingOutcome.SKIPPED
                }
            }
        } catch (e: Exception) {
            recordFailure()
            log.error(LTag.APS, "$TAG: training exception", e)
            TrainingOutcome.FAILED_RETRY
        }
    }

    private enum class HeadTrainResult { PUBLISHED, SKIPPED, FAILED }

    private fun trainBasalHead(parsed: BasalMlDataset): HeadTrainResult {
        val split = parsed.split80_20()
        val weightsFile = storageHelper.getAimiFile(BASAL_WEIGHTS)
        val config = TrainingConfig(learningRate = 0.0005, epochs = 200, patience = 20)
        val published = trainAndMaybePublish(
            weightsFile = weightsFile,
            trainInputs = split.trainInputs,
            trainTargets = split.trainBasalTargets,
            valInputs = split.valInputs,
            valTargets = split.valBasalTargets,
            config = config,
            outputRange = 0.5..2.0,
        )
        return when {
            published -> HeadTrainResult.PUBLISHED
            else -> HeadTrainResult.SKIPPED
        }
    }

    private fun trainT3cHead(parsed: BasalMlDataset): HeadTrainResult {
        val split = parsed.split80_20()
        val weightsFile = storageHelper.getAimiFile(T3C_WEIGHTS)
        val config = TrainingConfig(learningRate = 0.001, epochs = 300, patience = 20)
        val published = trainAndMaybePublish(
            weightsFile = weightsFile,
            trainInputs = split.trainInputs,
            trainTargets = split.trainT3cTargets,
            valInputs = split.valInputs,
            valTargets = split.valT3cTargets,
            config = config,
            outputRange = 0.3..3.0,
        )
        return when {
            published -> HeadTrainResult.PUBLISHED
            else -> HeadTrainResult.SKIPPED
        }
    }

    /**
     * Trains a candidate and publishes it only if it passes every gate.
     *
     * The range probe and the spread probe run on the bootstrap run too. They used to be switched off
     * when there was no model on disk yet, which let the very first candidate publish on nothing more
     * than "the numbers are finite". A dead model published that way then blocked every later candidate,
     * because each retrain produced the same out of range output and was dropped before the loss was
     * even compared. Publishing no model is a safe outcome: the caller falls back to the heuristic.
     *
     * The candidate also has to beat the best constant predictor on the held-out rows
     * ([MAX_BASELINE_MAE_RATIO]). The spread probe alone cannot do that job: measured, a model trained
     * on pure label noise moves MORE across the bg anchors than a model that found the real function.
     */
    private fun trainAndMaybePublish(
        weightsFile: File,
        trainInputs: List<FloatArray>,
        trainTargets: List<DoubleArray>,
        valInputs: List<FloatArray>,
        valTargets: List<DoubleArray>,
        config: TrainingConfig,
        outputRange: ClosedFloatingPointRange<Double>,
        minOutputSpread: Double = MIN_OUTPUT_SPREAD,
    ): Boolean {
        val hasIncumbent = weightsFile.exists()
        return NeuralModelTrainer.trainAndPublish(
            weightsFile = weightsFile,
            split = NeuralModelTrainer.Split(trainInputs, trainTargets, valInputs, valTargets),
            config = config,
            inputSize = INPUT_SIZE,
            probeInput = BasalMlDatasetParser.representativeProbeInput(trainInputs),
            outputRange = outputRange,
            spreadFeatureIndex = BG_FEATURE_INDEX,
            spreadSweepValues = SPREAD_SWEEP_BG_MGDL,
            minOutputSpread = minOutputSpread,
            maxBaselineMaeRatio = MAX_BASELINE_MAE_RATIO,
            requireIncumbentBeat = hasIncumbent,
            valLossTolerance = VAL_LOSS_TOLERANCE,
            log = { log.info(LTag.APS, "$TAG: $it") },
        ) != null
    }

    private fun isCircuitOpen(now: Long): Boolean = circuitBreaker.isOpen(now)

    private fun recordFailure() {
        if (circuitBreaker.recordFailure()) {
            log.warn(LTag.APS, "$TAG: circuit breaker OPEN for 6h after ${TrainingCircuitBreaker.DEFAULT_MAX_FAILURES} failures")
        }
    }

    private fun loadPersistedState() {
        val stateFile = storageHelper.getAimiFile(STATE_FILE)
        if (!stateFile.exists()) return
        try {
            val json = JSONObject(stateFile.readText())
            lastTrainMs.set(json.optLong("lastTrainMs", 0L))
            rowsAtLastTrain.set(json.optLong("rowsAtLastTrain", 0L))
        } catch (e: Exception) {
            log.warn(LTag.APS, "$TAG: could not load training state", e)
        }
    }

    /**
     * Records that a training attempt ran to completion, whether or not it published a model.
     *
     * Both gates — the interval in [TRAIN_INTERVAL_MS] and [MIN_NEW_ROWS] — key on this. Recording it
     * only on publish meant a model that stopped being beaten left the clock frozen while the row
     * count kept growing, so the coordinator retrained on every tick indefinitely.
     */
    private fun markAttemptCompleted(nowMs: Long, totalRows: Long) {
        lastTrainMs.set(nowMs)
        rowsAtLastTrain.set(totalRows)
        persistState()
    }

    private fun persistState() {
        try {
            val stateFile = storageHelper.getAimiFile(STATE_FILE)
            stateFile.parentFile?.mkdirs()
            val json = JSONObject()
                .put("lastTrainMs", lastTrainMs.get())
                .put("rowsAtLastTrain", rowsAtLastTrain.get())
            stateFile.writeText(json.toString())
        } catch (e: Exception) {
            log.warn(LTag.APS, "$TAG: could not persist training state", e)
        }
    }
}

/** Why rows were dropped while building the label set. Diagnostic only; never used for dosing. */
internal data class BasalMlParseStats(
    /** Rows dropped because insulin or carbs landed inside the label window. */
    val rejectedContaminated: Int = 0,
    /** Rows dropped because BG barely moved, so the response is not measurable. */
    val rejectedFlat: Int = 0,
    /** Rows dropped because the move and the needed correction point opposite ways. */
    val rejectedMixed: Int = 0,
    /** Rows kept without a censor check because the legacy CSV has no insulin / carb column. */
    val legacyUncensored: Int = 0,
) {
    override fun toString(): String =
        "contaminated=$rejectedContaminated flat=$rejectedFlat mixed=$rejectedMixed legacy=$legacyUncensored"
}

/** Parsed rows from `basal_adaptive_records.csv` with basal and T3C labels. */
internal data class BasalMlDataset(
    val inputs: List<FloatArray>,
    val basalTargets: List<DoubleArray>,
    val t3cTargets: List<DoubleArray>,
    val stats: BasalMlParseStats = BasalMlParseStats(),
) {
    val rowCount: Int get() = inputs.size

    data class Split(
        val trainInputs: List<FloatArray>,
        val valInputs: List<FloatArray>,
        val trainBasalTargets: List<DoubleArray>,
        val valBasalTargets: List<DoubleArray>,
        val trainT3cTargets: List<DoubleArray>,
        val valT3cTargets: List<DoubleArray>,
    )

    fun split80_20(): Split {
        val splitIdx = (inputs.size * 0.8).toInt().coerceAtLeast(1).coerceAtMost(inputs.size - 1)
        return Split(
            trainInputs = inputs.subList(0, splitIdx),
            valInputs = inputs.subList(splitIdx, inputs.size),
            trainBasalTargets = basalTargets.subList(0, splitIdx),
            valBasalTargets = basalTargets.subList(splitIdx, basalTargets.size),
            trainT3cTargets = t3cTargets.subList(0, splitIdx),
            valT3cTargets = t3cTargets.subList(splitIdx, t3cTargets.size),
        )
    }
}

/**
 * Column names shared by the CSV writer (`BasalNeuralLearner.logRecord`) and [BasalMlDatasetParser].
 *
 * These two columns were added so a label window that also received insulin or carbs can be dropped.
 * Rows written before they existed simply do not have them; the parser reads by name and treats a
 * missing column as "unknown", never as zero.
 */
/**
 * The one place the basal / T3C label window is written down.
 *
 * The trainer clamps every label into this window, so a model can only have LEARNED a value inside it;
 * anything it asks for outside is extrapolation. `BasalNeuralLearner` therefore uses the same numbers as
 * its runtime clamp, and the two used to be two copies of the same literals with only a KDoc pointer
 * between them. They live here now so they cannot drift apart quietly, and
 * `BasalMlTrainingCoordinatorTest` fails if the runtime clamp stops matching.
 *
 * The basal FLOOR is the deliberate exception: the runtime floor is 0.80, strictly BELOW [BASAL_MIN].
 * That gap is load-bearing. When the label floor and the runtime floor are the same number, a saturated
 * label and a dead model produce the exact same multiplier, and that is what hid a constant model for 40
 * days in the field (raw output 0.19918, reported as 0.70 by the clamp). Do not close it.
 */
internal object BasalLabelWindow {

    /** Basal scale label floor. Must stay strictly ABOVE the runtime floor, see above. */
    const val BASAL_MIN = 0.85

    /** Basal scale label ceiling. */
    const val BASAL_MAX = 1.35

    /** T3C aggressiveness label floor. Same value as the runtime clamp floor. */
    const val T3C_MIN = 0.5

    /** T3C aggressiveness label ceiling. Same value as the runtime clamp ceiling. */
    const val T3C_MAX = 2.0
}

internal object BasalCsvSchema {
    /** Insulin delivered outside basal at this tick (SMB + manual bolus), in units. */
    const val COL_BOLUS_U = "bolusU"

    /** Carbs on board at this tick, in grams. */
    const val COL_COB_G = "cobG"

    val causalColumns: List<String> = listOf(COL_BOLUS_U, COL_COB_G)
}

internal object BasalMlDatasetParser {

    /** Feature centroid for publish-time probe (replaces all-zero vector that never appears in training data). */
    fun representativeProbeInput(trainInputs: List<FloatArray>): FloatArray {
        if (trainInputs.isEmpty()) return neutralProbeInput()
        val size = trainInputs.first().size
        val acc = FloatArray(size)
        var used = 0
        for (row in trainInputs) {
            if (row.size != size) continue
            for (i in row.indices) acc[i] += row[i]
            used++
        }
        if (used == 0) return neutralProbeInput()
        val n = used.toFloat()
        for (i in acc.indices) acc[i] /= n
        return acc
    }

    /** Fallback probe when the train set is empty (should not happen in normal publish flow). */
    fun neutralProbeInput(): FloatArray {
        val base = floatArrayOf(100f, 1f, 0f, 30f, 45f, 0.5f)
        val physio = SmbRefinementFeatureSchema.latentFeatureValues(null) +
            SmbRefinementFeatureSchema.modeFeatureValues(null) +
            SmbRefinementFeatureSchema.causalFeatureValues(null)
        return base + physio
    }

    // 🩸 Alignement temporel du label. La cible d'apprentissage doit être le résultat RÉELLEMENT observé, donc
    // le BG mesuré ~30 min après le tick (colonne `bg` d'une ligne future), et NON la colonne `eventualBg` —
    // qui est une prédiction pkpd plancherée à 39 mg/dL (≈24 % des lignes) et produisait des labels fictifs
    // « crash à 39 » sur des BG réels normaux. On joint donc chaque ligne à son futur par timestamp.
    private const val LABEL_HORIZON_MS = 30L * 60_000        // cible : BG réalisé à +30 min
    private const val LABEL_HORIZON_MIN_MS = 20L * 60_000    // borne basse de la fenêtre d'acceptation
    private const val LABEL_HORIZON_MAX_MS = 45L * 60_000    // borne haute (au-delà = trop décorrélé)
    private const val MIN_VALID_BG = 40.0                    // en dessous = plancher/glitch capteur → ligne écartée
    private const val MAX_VALID_BG = 400.0
    private const val MAX_PLAUSIBLE_DELTA_MGDL = 150.0       // |bg − bgFutur| au-delà = glitch → écarté

    /**
     * Smallest measured BG move that still says something about the basal rate.
     *
     * The old rule used `actualDelta.coerceAtLeast(1.0)`, which turned every RISE into a fake 1 mg/dL
     * fall. The "ratio" then became a raw mg/dL error used as a multiplier, and the whole label set
     * collapsed onto three values (0.70 / 1.00 / 1.50). The guard is symmetric now: a move smaller
     * than this in either direction is noise, and the row is dropped instead of being repaired.
     */
    private const val MIN_ACTUAL_DELTA_MGDL = 3.0

    /** Below this distance to target the basal label is neutral (nothing to correct). */
    private const val BASAL_DEADBAND_MGDL = 3.0

    /** Same idea for the T3C head, which reacts later than the basal head. */
    private const val T3C_DEADBAND_MGDL = 5.0

    /** Response ratio bounds, applied BEFORE the damping so one odd row cannot dominate a batch. */
    private const val RAW_WEIGHT_MIN = 0.5
    private const val RAW_WEIGHT_MAX = 2.0

    /** Half of the measured correction is asked for, so the model moves in small steps. */
    private const val LABEL_DAMPING = 0.5

    /** Bolus (SMB or manual) inside the label window above this size makes the row unusable. */
    private const val BOLUS_CENSOR_U = 0.05

    /** Carbs on board above this size inside the label window make the row unusable. */
    private const val COB_CENSOR_G = 5.0

    /** IOB rise above the expected basal delivery that betrays an unrecorded bolus. */
    private const val IOB_JUMP_CENSOR_U = 0.25

    private const val MS_PER_HOUR = 3_600_000.0

    private class RawRow(
        val ts: Long,
        val bg: Double,
        val target: Double,
        val currentScale: Double,
        val currentAgg: Double,
        /** Basal rate set for this tick (U/h). Used to predict how much IOB basal alone can add. */
        val basalUph: Double,
        /** Total IOB at this tick (U). Used to spot a bolus landing inside a label window. */
        val iobU: Double,
        /** Insulin delivered outside basal at this tick (U); null when the CSV has no such column. */
        val bolusU: Double?,
        /** Carbs on board at this tick (g); null when the CSV has no such column. */
        val cobG: Double?,
        val features: FloatArray,
    )

    /** Result of the causal check on one label window. */
    private enum class WindowStatus {
        /** No insulin and no carbs entered the window: the BG move can be read as a basal response. */
        CLEAN,

        /** A bolus or carbs entered the window: the move is not a basal response. */
        CONTAMINATED,

        /** Legacy row: the CSV carries no bolus / carb column, so nothing can be proven either way. */
        LEGACY_UNKNOWN,
    }

    fun parse(csvFile: File): BasalMlDataset? {
        val allLines = csvFile.readLines()
        if (allLines.size < 2) return null

        val header = allLines.first().split(",")
        val iTs = header.indexOf("timestamp")
        val iBg = header.indexOf("bg")
        val iBasal = header.indexOf("basal")
        val iTarget = header.indexOf("target")
        val iAccel = header.indexOf("accel")
        val iDuraMin = header.indexOf("duraMin")
        val iDuraAvg = header.indexOf("duraAvg")
        val iIob = header.indexOf("iob")
        val iBasalScale = header.indexOf("basalScale")
        val iT3cAgg = header.indexOf("t3cAgg")
        // Causal columns (added later than the rest). Absent = legacy row, see [WindowStatus].
        val iBolus = header.indexOf(BasalCsvSchema.COL_BOLUS_U)
        val iCob = header.indexOf(BasalCsvSchema.COL_COB_G)

        val required = listOf(iTs, iBg, iBasal, iTarget, iAccel, iDuraMin, iDuraAvg, iIob, iBasalScale, iT3cAgg)
        if (required.any { it < 0 }) return null
        val requiredMaxIdx = required.max()

        // Physio-context columns (mirror of the SMB schema). Read by name; absent column OR short (legacy) row →
        // neutral value → schema versioning + neutral backfill so the pre-physio history keeps training.
        val physioNames = SmbRefinementFeatureSchema.latentFeatureNames +
            SmbRefinementFeatureSchema.modeFeatureNames +
            SmbRefinementFeatureSchema.causalFeatureNames
        val neutralPhysio = SmbRefinementFeatureSchema.latentFeatureValues(null) +
            SmbRefinementFeatureSchema.modeFeatureValues(null) +
            SmbRefinementFeatureSchema.causalFeatureValues(null)
        val physioIdx = physioNames.map { header.indexOf(it) }

        // 1) Parse + filtre de validité (BG plausible), puis tri chronologique pour la jointure du label.
        val raw = ArrayList<RawRow>(allLines.size)
        for (line in allLines.drop(1)) {
            val cols = line.split(",")
            // Require only the base columns (physio columns are optional → neutral backfill for legacy rows).
            if (cols.size <= requiredMaxIdx) continue
            val ts = cols[iTs].toLongOrNull() ?: continue
            val bg = cols[iBg].toDoubleOrNull() ?: continue
            if (!bg.isFinite() || bg < MIN_VALID_BG || bg > MAX_VALID_BG) continue
            val basal = cols[iBasal].toFloatOrNull() ?: continue
            val accel = cols[iAccel].toFloatOrNull() ?: continue
            val duraMin = cols[iDuraMin].toFloatOrNull() ?: continue
            val duraAvg = cols[iDuraAvg].toFloatOrNull() ?: continue
            val iob = cols[iIob].toFloatOrNull() ?: continue
            val target = cols[iTarget].toDoubleOrNull() ?: continue
            val currentScale = cols[iBasalScale].toDoubleOrNull() ?: continue
            val currentAgg = cols[iT3cAgg].toDoubleOrNull() ?: continue
            val physio = FloatArray(physioNames.size) { j ->
                val idx = physioIdx[j]
                if (idx >= 0) cols.getOrNull(idx)?.toFloatOrNull() ?: neutralPhysio[j] else neutralPhysio[j]
            }
            val bolusU = if (iBolus >= 0) cols.getOrNull(iBolus)?.toDoubleOrNull()?.takeIf { it.isFinite() } else null
            val cobG = if (iCob >= 0) cols.getOrNull(iCob)?.toDoubleOrNull()?.takeIf { it.isFinite() } else null
            raw.add(
                RawRow(
                    ts = ts,
                    bg = bg,
                    target = target,
                    currentScale = currentScale,
                    currentAgg = currentAgg,
                    basalUph = basal.toDouble(),
                    iobU = iob.toDouble(),
                    bolusU = bolusU,
                    cobG = cobG,
                    features = floatArrayOf(bg.toFloat(), basal, accel, duraMin, duraAvg, iob) + physio,
                )
            )
        }
        if (raw.size < 2) return null
        raw.sortBy { it.ts }

        val inputs = mutableListOf<FloatArray>()
        val basalTargets = mutableListOf<DoubleArray>()
        val t3cTargets = mutableListOf<DoubleArray>()

        // 2) Cible = BG réalisé (vérité terrain) au lieu de la prédiction. Les lignes sans futur observable
        //    (fin de log / trou de données) sont écartées — on ne fabrique pas de label.
        var rejectedContaminated = 0
        var rejectedFlat = 0
        var rejectedMixed = 0
        var legacyUncensored = 0

        for (i in raw.indices) {
            val r = raw[i]
            val realizedBg = realizedFutureBg(raw, i) ?: continue
            val actualDelta = r.bg - realizedBg          // > 0 when BG FELL
            if (abs(actualDelta) > MAX_PLAUSIBLE_DELTA_MGDL) continue
            val neededDelta = r.bg - r.target            // > 0 when ABOVE target

            // Causal check first: a window that also received a bolus or carbs says nothing about basal.
            when (windowStatus(raw, i)) {
                WindowStatus.CONTAMINATED -> {
                    rejectedContaminated++
                    continue
                }

                WindowStatus.LEGACY_UNKNOWN -> legacyUncensored++
                WindowStatus.CLEAN            -> Unit
            }

            val atTarget = abs(neededDelta) < BASAL_DEADBAND_MGDL
            if (!atTarget) {
                // Symmetric guard: the BG must really have moved, in either direction.
                if (abs(actualDelta) < MIN_ACTUAL_DELTA_MGDL) {
                    rejectedFlat++
                    continue
                }
                // A fall while above target, or a rise while below target, is a correction we can score.
                // The mixed cases (rise while above target, fall while below target) cannot be turned
                // into a multiplier, so the row is dropped instead of being given a made-up label.
                if ((actualDelta > 0) != (neededDelta > 0)) {
                    rejectedMixed++
                    continue
                }
            }

            val responseWeight = if (atTarget) 1.0 else correctionWeight(neededDelta, actualDelta)

            val dampedBasalWeight = 1.0 + (responseWeight - 1.0) * LABEL_DAMPING
            val idealScale = (r.currentScale * dampedBasalWeight)
                .coerceIn(BasalLabelWindow.BASAL_MIN, BasalLabelWindow.BASAL_MAX)

            val t3cWeight = if (abs(neededDelta) < T3C_DEADBAND_MGDL) 1.0 else responseWeight
            val idealAgg = (r.currentAgg * t3cWeight)
                .coerceIn(BasalLabelWindow.T3C_MIN, BasalLabelWindow.T3C_MAX)

            inputs.add(r.features)
            basalTargets.add(doubleArrayOf(idealScale))
            t3cTargets.add(doubleArrayOf(idealAgg))
        }

        if (inputs.isEmpty()) return null
        return BasalMlDataset(
            inputs = inputs,
            basalTargets = basalTargets,
            t3cTargets = t3cTargets,
            stats = BasalMlParseStats(
                rejectedContaminated = rejectedContaminated,
                rejectedFlat = rejectedFlat,
                rejectedMixed = rejectedMixed,
                legacyUncensored = legacyUncensored,
            ),
        )
    }

    /**
     * How much of the needed correction the basal rate actually delivered, as a multiplier for the
     * current scale.
     *
     * Both deltas point the same way when this is called, so `achieved` is positive.
     * - Above target: `achieved` below 1 means the fall was too slow, so ask for MORE basal.
     * - Below target: `achieved` below 1 means the recovery was too slow, so ask for LESS basal.
     *
     * The ratio therefore has to be inverted above target. Using one single `needed / actual` ratio for
     * both sides would tell the model to raise basal while the user is low and still climbing back.
     */
    private fun correctionWeight(neededDelta: Double, actualDelta: Double): Double {
        val achieved = actualDelta / neededDelta
        val weight = if (neededDelta > 0) 1.0 / achieved else achieved
        if (!weight.isFinite()) return 1.0
        return weight.coerceIn(RAW_WEIGHT_MIN, RAW_WEIGHT_MAX)
    }

    /**
     * Tells whether the label window of row [i] was driven by basal alone.
     *
     * The 30-minute BG move used to be attributed entirely to basal, with nothing checking whether an
     * SMB, a manual bolus or a meal landed in the same window. Even a perfect regressor then learns
     * the wrong function. Two checks run here:
     * 1. the explicit `bolusU` / `cobG` columns when the CSV carries them;
     * 2. an IOB jump larger than what the recorded basal rates alone can deliver, which also catches
     *    old rows written before those columns existed.
     */
    private fun windowStatus(rows: List<RawRow>, i: Int): WindowStatus {
        val r = rows[i]
        val hasExplicitColumns = r.bolusU != null || r.cobG != null
        if (r.cobG != null && r.cobG > COB_CENSOR_G) return WindowStatus.CONTAMINATED
        // The anchor row's own bolus counts too. `bolusU` on row i is the insulin delivered at this tick plus
        // whatever landed since the tick before, so it acts DURING this row's label window. Only the future rows
        // used to be checked, and the IOB-jump fallback cannot see this case either: the IOB is already high at the
        // anchor, so there is no jump left to spot.
        if (r.bolusU != null && r.bolusU > BOLUS_CENSOR_U) return WindowStatus.CONTAMINATED

        val maxTs = r.ts + LABEL_HORIZON_MAX_MS
        var expectedBasalU = 0.0
        var prevTs = r.ts
        var prevRate = r.basalUph
        var maxIob = r.iobU
        for (j in i + 1 until rows.size) {
            val f = rows[j]
            if (f.ts > maxTs) break
            if (f.bolusU != null && f.bolusU > BOLUS_CENSOR_U) return WindowStatus.CONTAMINATED
            if (f.cobG != null && f.cobG > COB_CENSOR_G) return WindowStatus.CONTAMINATED
            val dtHours = (f.ts - prevTs) / MS_PER_HOUR
            if (dtHours > 0.0) expectedBasalU += prevRate.coerceAtLeast(0.0) * dtHours
            prevTs = f.ts
            prevRate = f.basalUph
            if (f.iobU > maxIob) maxIob = f.iobU
        }
        // IOB decay is ignored on purpose, so this only fires on a clear jump.
        if (maxIob - r.iobU > expectedBasalU + IOB_JUMP_CENSOR_U) return WindowStatus.CONTAMINATED

        return if (hasExplicitColumns) WindowStatus.CLEAN else WindowStatus.LEGACY_UNKNOWN
    }

    /**
     * BG réellement mesuré ~[LABEL_HORIZON_MS] après la ligne [i] : ligne future dont le timestamp est le plus
     * proche de la cible, dans la fenêtre [[LABEL_HORIZON_MIN_MS], [LABEL_HORIZON_MAX_MS]]. `null` si aucune
     * (fin du log ou trou de données) → la ligne ne peut pas être labellisée. [rows] doit être trié par ts.
     */
    private fun realizedFutureBg(rows: List<RawRow>, i: Int): Double? {
        val base = rows[i].ts
        val targetTs = base + LABEL_HORIZON_MS
        val minTs = base + LABEL_HORIZON_MIN_MS
        val maxTs = base + LABEL_HORIZON_MAX_MS
        var best: Double? = null
        var bestDiff = Long.MAX_VALUE
        for (j in i + 1 until rows.size) {
            val ts = rows[j].ts
            if (ts < minTs) continue
            if (ts > maxTs) break
            val diff = abs(ts - targetTs)
            if (diff < bestDiff) {
                bestDiff = diff
                best = rows[j].bg
            }
        }
        return best
    }
}
