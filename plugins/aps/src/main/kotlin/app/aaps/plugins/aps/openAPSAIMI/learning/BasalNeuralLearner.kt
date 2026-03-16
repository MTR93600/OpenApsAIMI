package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import java.io.File

/**
 * BasalNeuralLearner identifies user-specific glycemic response patterns
 * to optimize basal adjustments.
 *
 * It supports two modes:
 * 1. T3C Brittle Mode (Aggressive corrective PI basal)
 * 2. Universal Adaptive Basal (Subtle scaling of standard TBRs for all users)
 */
@Singleton
class BasalNeuralLearner @Inject constructor(
    private val context: Context,
    private val preferences: Preferences,
    private val log: AAPSLogger
) {
    private var internalAggressivenessFactor = 1.0 // Heuristic fallback for T3C
    private var internalBasalScalingFactor = 1.0    // Heuristic fallback for Universal
    
    private var neuralT3cNet: AimiNeuralNetwork? = null
    private var neuralBasalNet: AimiNeuralNetwork? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        loadModels()
    }

    private fun loadModels() {
        val externalDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS + "/AAPS") ?: return
        val t3cWeights = File(externalDir, "t3c_brain_weights.json")
        val basalWeights = File(externalDir, "basal_adaptive_weights.json")

        if (t3cWeights.exists()) {
            neuralT3cNet = AimiNeuralNetwork.loadFromFile(t3cWeights)
        }
        if (basalWeights.exists()) {
            neuralBasalNet = AimiNeuralNetwork.loadFromFile(basalWeights)
        }
    }

    /**
     * Returns the aggressiveness factor for T3C Brittle Mode.
     */
    fun getT3cAdaptiveFactor(
        bg: Double,
        basal: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double
    ): Double {
        val baseAggressiveness = preferences.get(DoubleKey.OApsAIMIT3cAggressiveness)
        val neuralFactor = neuralT3cNet?.predict(floatArrayOf(bg.toFloat(), basal.toFloat(), accel.toFloat(), duraMin.toFloat(), duraAvg.toFloat(), iob.toFloat()))?.get(0)
        
        return baseAggressiveness * (neuralFactor ?: internalAggressivenessFactor)
    }

    /**
     * Returns the scaling factor for Universal Adaptive Basal.
     */
    fun getUniversalBasalMultiplier(
        bg: Double,
        basal: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double
    ): Double {
        if (!preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)) return 1.0
        val maxScaling = preferences.get(DoubleKey.OApsAIMIAdaptiveBasalMaxScaling)
        
        val neuralFactor = neuralBasalNet?.predict(floatArrayOf(bg.toFloat(), basal.toFloat(), accel.toFloat(), duraMin.toFloat(), duraAvg.toFloat(), iob.toFloat()))?.get(0)
        
        return (neuralFactor ?: internalBasalScalingFactor).coerceIn(0.7, max(1.0, maxScaling))
    }

    /**
     * Updates the internal state and LOGS data for Neural Training.
     */
    fun updateLearning(
        bgBefore: Double,
        bgAfter: Double,
        basalDelivered: Double,
        targetBg: Double,
        accel: Double,
        duraISFminutes: Double,
        duraISFaverage: Double,
        iob: Double
    ) {
        val isT3cActive = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)
        val isAdaptiveBasalActive = preferences.get(BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled)
        val delta = bgAfter - bgBefore
        
        // 1. T3C Heuristic (Aggressive)
        if (isT3cActive) {
            if (bgBefore > 180.0 && delta >= 0) {
                internalAggressivenessFactor = min(internalAggressivenessFactor + 0.05, 2.0)
            }
            if (delta < -15.0) {
                internalAggressivenessFactor = max(internalAggressivenessFactor - 0.1, 0.5)
            }
        }

        // 2. Universal Adaptive Basal Heuristic (Subtle)
        if (isAdaptiveBasalActive) {
            // If BG is high (>140) and not dropping fast enough
            if (bgBefore > 140.0 && delta > -2.0) {
                internalBasalScalingFactor = min(internalBasalScalingFactor + 0.01, 1.5)
            }
            // If BG is low (<90) or dropping too fast
            if (bgBefore < 90.0 || delta < -8.0) {
                internalBasalScalingFactor = max(internalBasalScalingFactor - 0.02, 0.8)
            }
        }

        // 3. Data Logging
        scope.launch {
            try {
                logRecord(bgBefore, bgAfter, basalDelivered, targetBg, accel, duraISFminutes, duraISFaverage, iob)
            } catch (e: Exception) {
                log.error("BASAL_LOG", "Failed to log records: ${e.message}")
            }
        }
    }

    private fun logRecord(
        bg: Double,
        eventualBg: Double,
        basal: Double,
        target: Double,
        accel: Double,
        duraMin: Double,
        duraAvg: Double,
        iob: Double
    ) {
        val externalDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS + "/AAPS") ?: return
        val file = File(externalDir, "basal_adaptive_records.csv")
        
        if (!file.exists()) {
            file.writeText("timestamp,bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,t3cAgg,basalScale\n")
        }
        
        val row = "${System.currentTimeMillis()},$bg,$eventualBg,$basal,$target,$accel,$duraMin,$duraAvg,$iob,$internalAggressivenessFactor,$internalBasalScalingFactor\n"
        file.appendText(row)
    }
}
