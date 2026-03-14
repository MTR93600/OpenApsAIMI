package app.aaps.plugins.aps.openAPSAIMI.learning

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
import kotlin.math.min

/**
 * T3cNeuralLearner — ML model to refine T3C basal adjustments.
 *
 * Logic:
 * - Inputs: [BG, Delta, Accel, IOB]
 * - Output: Adjustment Factor (scale 0.5 to 1.5)
 */
object T3cNeuralLearner {
    private const val TAG = "T3cNeuralLearner"
    const val INPUT_SIZE = 4

    private val modelRef = AtomicReference<AimiNeuralNetwork?>(null)
    private val trainMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cbFailures = AtomicInteger(0)
    private val cbCoolingUntilMs = AtomicLong(0L)
    private val lastTrainMs = AtomicLong(0L)

    private const val CB_MAX_FAILURES = 3
    private const val CB_COOLDOWN_MS = 6 * 60 * 60 * 1000L
    private const val TRAIN_INTERVAL_MS = 12 * 60 * 60 * 1000L // 12h

    fun loadModel(dir: File) {
        scope.launch {
            val net = T3cModelStore.load(dir, INPUT_SIZE)
            modelRef.set(net)
            if (net != null) {
                Log.i(TAG, "T3C Model loaded from disk")
            }
        }
    }

    fun getAdjustmentFactor(bg: Double, delta: Double, accel: Double, iob: Double): Double {
        val now = System.currentTimeMillis()
        if (cbFailures.get() >= CB_MAX_FAILURES && now < cbCoolingUntilMs.get()) return 1.0

        val model = modelRef.get() ?: return 1.0

        return try {
            val features = floatArrayOf(bg.toFloat(), delta.toFloat(), accel.toFloat(), iob.toFloat())
            val out = model.predict(features)
            val factor = out.firstOrNull() ?: 1.0
            factor.coerceIn(0.5, 1.5)
        } catch (e: Exception) {
            recordFailure()
            1.0
        }
    }

    private fun recordFailure() {
        val failures = cbFailures.incrementAndGet()
        if (failures >= CB_MAX_FAILURES) {
            cbCoolingUntilMs.set(System.currentTimeMillis() + CB_COOLDOWN_MS)
        }
    }

    // Training logic will be added when we have a CSV sink for T3C events
    fun maybeTrainAsync(dir: File, csvFile: File) {
        val now = System.currentTimeMillis()
        if (now - lastTrainMs.get() < TRAIN_INTERVAL_MS) return
        if (cbFailures.get() >= CB_MAX_FAILURES && now < cbCoolingUntilMs.get()) return

        scope.launch {
            if (trainMutex.isLocked) return@launch
            trainMutex.withLock {
                try {
                    // Placeholder for training logic similar to AimiSmbTrainer
                    // We need to collect [bg, delta, accel, iob] -> target_adjustment
                    Log.d(TAG, "T3C training placeholder triggered")
                } catch (e: Exception) {
                    recordFailure()
                }
            }
        }
    }
}
