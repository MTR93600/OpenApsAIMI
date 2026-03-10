package app.aaps.plugins.aps.openAPSAIMI.ml

import android.util.Log
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

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

    // Input dimension: 10 physio features + 1 trendIndicator
    const val INPUT_SIZE = 11

    // Circuit breaker settings
    private const val CB_MAX_FAILURES = 3
    private const val CB_COOLDOWN_MS  = 6 * 60 * 60 * 1000L  // 6h

    // Training rate limit
    private const val TRAIN_INTERVAL_MS  = 6 * 60 * 60 * 1000L  // 6h
    private const val MIN_NEW_ROWS_TO_RETRAIN = 200

    // ---- State ---------------------------------------------------------------
    private val modelRef   = AtomicReference<AimiNeuralNetwork?>(null)
    private val trainMutex = Mutex()
    private val scope      = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Circuit breaker
    private val cbFailures       = AtomicInteger(0)
    private val cbCoolingUntilMs = AtomicLong(0L)

    // Training rate limit
    private val lastTrainMs   = AtomicLong(0L)
    private val rowsAtLastTrain = AtomicLong(0L)

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
    fun refine(predictedSmb: Float, features: FloatArray): Float {
        if (features.size != INPUT_SIZE) return predictedSmb

        val now = System.currentTimeMillis()
        if (isCircuitOpen(now)) return predictedSmb

        val model = modelRef.get() ?: return predictedSmb

        return try {
            val out = model.predict(features)

            val mlOut = out.firstOrNull()?.toFloat() ?: return predictedSmb
            if (!mlOut.isFinite()) return predictedSmb

            // Clamp: max correction is the smaller of 0.05U or 25% of predictedSmb
            val maxDelta = min(0.05f, predictedSmb * 0.25f).coerceAtLeast(0f)
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
        val featureNames = listOf(
            "bg","iob","cob","delta","shortAvgDelta","longAvgDelta",
            "tdd7DaysPerHour","tdd2DaysPerHour","tddPerHour","tdd24HrsPerHour"
        )
        val targetName = "smbGiven"

        val featureIndices = featureNames.map { headers.indexOf(it) }
        val targetIndex    = headers.indexOf(targetName)

        if (featureIndices.any { it == -1 } || targetIndex == -1) {
            Log.w(TAG, "CSV missing required columns — skip training")
            return
        }

        val inputs  = mutableListOf<FloatArray>()
        val targets = mutableListOf<DoubleArray>()

        for (line in dataLines) {
            val cols = line.split(",").map { it.trim() }
            if (cols.size <= targetIndex) continue

            val rawFeatures = featureIndices.map { idx -> cols.getOrNull(idx)?.toFloatOrNull() }
            if (rawFeatures.any { it == null }) continue

            val raw = rawFeatures.map { it!! }.toFloatArray()

            // Approximate trendIndicator for offline training
            val trendIndicator = computeTrendIndicator(raw)
            val enhanced = raw.copyOf(raw.size + 1).also { it[raw.size] = trendIndicator }

            targets.add(doubleArrayOf(cols[targetIndex].toDoubleOrNull() ?: continue))
            inputs.add(enhanced)
        }

        if (inputs.size < 10) {
            Log.w(TAG, "Insufficient training samples (${inputs.size}) — skip")
            return
        }

        Log.i(TAG, "Training on ${inputs.size} samples…")

        // Simple single-pass training (K-fold is too slow here)
        val net = AimiNeuralNetwork(
            inputSize = INPUT_SIZE,
            hiddenSize = 8,
            outputSize = 1,
            config = TrainingConfig(learningRate = 0.001, epochs = 300),
            regularizationLambda = 0.01
        )
        // Split 80/20 for train/val
        val splitIdx = (inputs.size * 0.8).toInt().coerceAtLeast(1)
        val trainInputs  = inputs.subList(0, splitIdx)
        val trainTargets = targets.subList(0, splitIdx)
        val valInputs    = inputs.subList(splitIdx, inputs.size).takeIf { it.isNotEmpty() } ?: trainInputs
        val valTargets   = targets.subList(splitIdx, targets.size).takeIf { it.isNotEmpty() } ?: trainTargets
        net.trainWithValidation(trainInputs, trainTargets, valInputs, valTargets)

        // Validate before committing
        val probe = FloatArray(INPUT_SIZE) { 0f }
        val testOut = net.predict(probe)
        if (!testOut.all { it.isFinite() }) {
            Log.w(TAG, "Trained model produced NaN/Inf — discarding")
            recordFailure()
            return
        }

        // Commit
        val saved = AimiSmbModelStore.save(dir, net)
        if (saved) {
            modelRef.set(net)
            lastTrainMs.set(System.currentTimeMillis())
            rowsAtLastTrain.set(totalRows)
            cbFailures.set(0)   // reset circuit breaker on success
            Log.i(TAG, "Model trained and saved successfully (${inputs.size} rows)")
        } else {
            recordFailure()
        }
    }

    // ---- Helpers -------------------------------------------------------------

    private fun computeTrendIndicator(raw: FloatArray): Float {
        // raw: [bg, iob, cob, delta, shortAvgDelta, longAvgDelta, ...]
        val bg           = raw.getOrElse(0) { 120f }.toDouble()
        val iob          = raw.getOrElse(1) { 0f }.toDouble()
        val delta        = raw.getOrElse(3) { 0f }
        val shortAvgDelta = raw.getOrElse(4) { 0f }
        val longAvgDelta  = raw.getOrElse(5) { 0f }
        val combinedDelta = (delta + shortAvgDelta + longAvgDelta) / 3f
        val stressScore   = if (bg > 150) 40.0 else 0.0
        val metabolicLoad = iob * 5.0
        val baseTrend = (combinedDelta * 5.0f) + (stressScore * 0.1).toFloat() - (metabolicLoad * 0.5).toFloat()
        val sig = (1f / (1f + exp(-baseTrend.toDouble()))).toFloat()
        return 0.5f + sig * 0.7f
    }


    private fun isCircuitOpen(now: Long): Boolean {
        return cbFailures.get() >= CB_MAX_FAILURES && now < cbCoolingUntilMs.get()
    }

    private fun recordFailure() {
        val failures = cbFailures.incrementAndGet()
        if (failures >= CB_MAX_FAILURES) {
            val until = System.currentTimeMillis() + CB_COOLDOWN_MS
            cbCoolingUntilMs.set(until)
            Log.w(TAG, "Circuit breaker OPEN — ML disabled for 6h after $failures consecutive failures")
        }
    }
}
