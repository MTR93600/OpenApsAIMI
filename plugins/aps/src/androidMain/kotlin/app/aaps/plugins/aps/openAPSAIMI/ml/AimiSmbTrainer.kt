package app.aaps.plugins.aps.openAPSAIMI.ml

import android.util.Log
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * AimiSmbTrainer — Singleton managing the ML model lifecycle for SMB refinement.
 *
 * Safety contracts:
 *  - refine() is always O(1): fallback to predictedSmb on any error
 *  - training runs on Dispatchers.IO, never on the hot-path thread
 *  - circuit breaker disables ML for 6h after 3 consecutive failures
 *  - ML correction is clamped to ±min(0.05U, 25% of predictedSmb)
 */
object AimiSmbTrainer {

    private const val TAG = "AimiSmbTrainer"

    // Input dimension: 10 base features + 4 latent physio features + 3 patient-mode features +
    // 3 causal-context features + 1 trendIndicator
    const val INPUT_SIZE = SmbRefinementFeatureSchema.INPUT_SIZE

    // Training rate limit
    private const val TRAIN_INTERVAL_MS  = 6 * 60 * 60 * 1000L  // 6h
    private const val MIN_NEW_ROWS_TO_RETRAIN = 200

    /** Index of the bg column in the SMB feature vector (`SmbRefinementFeatureSchema` lists it first). */
    private const val BG_FEATURE_INDEX = 0

    /**
     * Accepted output band for a published SMB model, in insulin units.
     *
     * The model predicts an SMB dose, not a multiplier, so the band is in units. The configured
     * maximum SMB sits well below the upper bound, which is there to drop an absurd or negative
     * answer rather than to shape therapy.
     */
    private val SMB_OUTPUT_RANGE = 0.0..5.0

    /**
     * Smallest bg response we accept from a published SMB model, in insulin units.
     *
     * It is set to the runtime correction clamp on purpose. `refine` only ever moves the dose by
     * `min(0.05 U, 25 % of the dose)`, so a model whose answer moves less than 0.05 U across the bg
     * anchors cannot change what the pump does — it can only add the same small offset to every dose.
     * That is the failure this gate exists to catch: the basal head shipped a constant model that ran
     * for 40 days on two devices because nothing checked whether the answer moved at all.
     */
    private const val SMB_MIN_OUTPUT_SPREAD = 0.05

    /**
     * A published model must beat the best constant predictor on the held-out rows by this factor.
     *
     * The spread probe alone cannot reject noise: over a wide bg sweep a model fitted to pure label
     * noise moves MORE than one that found the real function. Held-out error against the best constant
     * is what separates them.
     */
    private const val SMB_MAX_BASELINE_MAE_RATIO = 0.95

    // ---- State ---------------------------------------------------------------
    private val modelRef   = AtomicReference<AimiNeuralNetwork?>(null)
    private val trainMutex = Mutex()
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Circuit breaker (shared component)
    private val circuitBreaker = TrainingCircuitBreaker()

    // Training rate limit
    private val lastTrainMs   = AtomicLong(0L)
    private val rowsAtLastTrain = AtomicLong(0L)

    /** Set once the weights trained on an unreadable corpus have been thrown away. */
    private val staleModelDiscarded = AtomicBoolean(false)

    // ---- Public API ----------------------------------------------------------

    /** Load previously saved model from disk. Call once on plugin start. */
    fun loadModel(dir: File) {
        scope.launch {
            val net = AimiSmbModelStore.load(dir, INPUT_SIZE)
            modelRef.set(net)
            if (net != null) {
                Log.i(TAG, "Model loaded from disk (${INPUT_SIZE} inputs)")
            } else {
                Log.i(TAG, "No pre-trained model found — ML refinement inactive until first training")
            }
        }
    }

    /**
     * Fire-and-forget training trigger.
     * Respects rate limit (6h) and minimum new-rows requirement (200).
     * Never blocks the caller.
     */
    fun maybeTrainAsync(dir: File, csvFile: File) {
        val now = System.currentTimeMillis()

        // Rate limit guard (fast path, no coroutine needed)
        if (now - lastTrainMs.get() < TRAIN_INTERVAL_MS) return

        // Circuit breaker guard
        if (isCircuitOpen(now)) return

        scope.launch {
            if (trainMutex.isLocked) return@launch  // Another training in progress
            trainMutex.withLock {
                try {
                    trainNow(dir, csvFile)
                } catch (e: Exception) {
                    recordFailure()
                    Log.e(TAG, "Training failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Refine [predictedSmb] using the in-memory model.
     *
     * - Returns [predictedSmb] unchanged if model is null, circuit is open,
     *   or any exception is thrown.
     * - Clamps the ML correction to ±min(0.05U, 25% of predictedSmb).
     */
    internal fun refine(
        predictedSmb: Float,
        features: FloatArray,
        behaviorProfile: AimiBehaviorRuntimeProfile? = null,
    ): Float {
        if (features.size != INPUT_SIZE) return predictedSmb

        val now = System.currentTimeMillis()
        if (isCircuitOpen(now)) return predictedSmb

        val model = modelRef.get() ?: return predictedSmb

        return try {
            val out = model.predict(features)

            val mlOut = out.firstOrNull()?.toFloat() ?: return predictedSmb
            if (!mlOut.isFinite()) return predictedSmb

            val maxDelta = correctionClamp(predictedSmb, behaviorProfile)
            val delta    = (mlOut - predictedSmb).coerceIn(-maxDelta, maxDelta)
            val refined  = predictedSmb + delta

            if (!refined.isFinite() || refined < 0f) predictedSmb else refined
        } catch (e: Exception) {
            recordFailure()
            Log.w(TAG, "refine() exception: ${e.message}")
            predictedSmb
        }
    }

    // ---- Internal training ---------------------------------------------------

    private suspend fun trainNow(dir: File, csvFile: File) {
        if (!csvFile.exists()) {
            Log.d(TAG, "CSV not found — skip training")
            return
        }

        val allLines = csvFile.readLines()
        val dataLines = allLines.drop(1).filter { it.isNotBlank() }
        val totalRows = dataLines.size.toLong()

        val newRows = totalRows - rowsAtLastTrain.get()
        if (newRows < MIN_NEW_ROWS_TO_RETRAIN) {
            Log.d(TAG, "Only $newRows new rows (need $MIN_NEW_ROWS_TO_RETRAIN) — skip training")
            return
        }

        val headers = allLines.firstOrNull()?.split(",")?.map { it.trim() } ?: return
        val headerCheck = AimiSmbCorpus.checkCorpusHeader(headers)
        if (!headerCheck.valid) {
            Log.e(TAG, "SMB corpus refused — no training. ${headerCheck.reason}")
            discardModelTrainedOnUnreadableCorpus(dir)
            return
        }
        val corpus = AimiSmbCorpus.buildTrainingCorpus(headers, dataLines) ?: return
        val inputs = corpus.inputs
        val targets = corpus.targets

        if (inputs.size < 10) {
            Log.w(TAG, "Insufficient training samples (${inputs.size}) — skip")
            return
        }

        Log.i(TAG, "Training on ${inputs.size} samples…")

        // Single-pass train → probe-validate → atomic publish via the shared pipeline.
        //
        // The liveness gates below used to be off for this head, so it could publish a model that
        // answers the same value for every input. Measured: trained on a single constant label it
        // published, with a spread of 0.0125 U over random inputs.
        //
        // `requireIncumbentBeat` stays off on purpose. Comparing against the model on disk is what
        // froze the basal head for 40 days: a dead incumbent anchored the comparison and every later
        // candidate was dropped. The liveness probes are the safe way to keep a bad model out; a
        // val-loss ratchet is not.
        val net = NeuralModelTrainer.trainAndPublish(
            weightsFile = AimiSmbModelStore.modelFile(dir),
            split = NeuralModelTrainer.split80_20(inputs, targets),
            config = TrainingConfig(learningRate = 0.001, epochs = 300),
            inputSize = INPUT_SIZE,
            regularizationLambda = 0.01,
            outputRange = SMB_OUTPUT_RANGE,
            spreadFeatureIndex = BG_FEATURE_INDEX,
            spreadSweepValues = BasalNeuralLearner.ClinicalBgAnchors.PROBE_BG_MGDL,
            minOutputSpread = SMB_MIN_OUTPUT_SPREAD,
            maxBaselineMaeRatio = SMB_MAX_BASELINE_MAE_RATIO,
            log = { Log.i(TAG, it) },
        )
        if (net != null) {
            modelRef.set(net)
            lastTrainMs.set(System.currentTimeMillis())
            rowsAtLastTrain.set(totalRows)
            circuitBreaker.reset()   // reset circuit breaker on success
            Log.i(TAG, "Model trained and saved successfully (${inputs.size} rows)")
        } else {
            recordFailure()
        }
    }

    // ---- Helpers -------------------------------------------------------------

    /**
     * Throws away the stored SMB weights, once, after the corpus guard refused the file.
     *
     * The weights on disk were fitted against whatever column the stale header pointed at, so they
     * answer in the wrong unit and `refine` keeps using them until a training run succeeds. Clearing
     * [modelRef] and deleting the weight file sends `refine` back to returning `predictedSmb`
     * unchanged, which is already what it does when no model is loaded.
     *
     * It runs at most once per app start: a corpus that stays unreadable must not turn into a delete
     * on every tick.
     *
     * ⚠️ ASYNC IMPACT: runs on the existing `Dispatchers.IO` training coroutine (same as `trainNow`).
     * `modelRef.set(null)` is visible to the hot-path `refine()`; the File delete is IO-only.
     * `loadModel` is also fire-and-forget on that dispatcher — same pre-existing race as a training
     * publish vs a plugin-start load.
     */
    private fun discardModelTrainedOnUnreadableCorpus(dir: File) {
        if (!staleModelDiscarded.compareAndSet(false, true)) return
        modelRef.set(null)
        val removed = AimiSmbModelStore.delete(dir)
        Log.w(
            TAG,
            "SMB weights discarded: they were trained on an unreadable corpus. " +
                "Weight file removed=$removed. refine() now returns the rule-based dose until a " +
                "training run on a readable corpus publishes new weights.",
        )
    }

    internal fun correctionClamp(
        predictedSmb: Float,
        behaviorProfile: AimiBehaviorRuntimeProfile? = null,
    ): Float {
        val baseClamp = min(0.05f, predictedSmb * 0.25f).coerceAtLeast(0f)
        val multiplier = behaviorProfile?.mlCorrectionFractionMultiplier() ?: 1.0f
        return (baseClamp * multiplier).coerceAtLeast(0f)
    }


    private fun isCircuitOpen(now: Long): Boolean = circuitBreaker.isOpen(now)

    private fun recordFailure() {
        if (circuitBreaker.recordFailure()) {
            Log.w(TAG, "Circuit breaker OPEN — ML disabled for 6h after ${TrainingCircuitBreaker.DEFAULT_MAX_FAILURES} consecutive failures")
        }
    }
}
