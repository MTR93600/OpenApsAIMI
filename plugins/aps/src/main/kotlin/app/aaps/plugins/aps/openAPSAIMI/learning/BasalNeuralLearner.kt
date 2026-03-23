package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import android.os.Environment
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
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
import kotlin.math.abs
import java.io.File
import java.util.Locale

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
    private val storageHelper: app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper,
    private val log: AAPSLogger
) {
    enum class GovernanceAction {
        WARMUP,
        KEEP,
        HOLD_CONSERVATIVE,
        ALLOW_GENTLE_INCREASE
    }

    data class GovernanceSnapshot(
        val action: GovernanceAction,
        val confidence: Double,
        val sampleCount: Int,
        val hypoRate: Double,
        val severeHypoCount: Int,
        val highRate: Double,
        val meanAbsTargetError: Double,
        val reason: String,
        val timestamp: Long
    )

    private data class LearningSample(
        val timestamp: Long,
        val bgBefore: Double,
        val bgAfter: Double,
        val targetBg: Double
    )

    private var internalAggressivenessFactor = 1.0 // Heuristic fallback for T3C
    private var internalBasalScalingFactor = 1.0    // Heuristic fallback for Universal
    
    private var neuralT3cNet: AimiNeuralNetwork? = null
    private var neuralBasalNet: AimiNeuralNetwork? = null

    private val governanceWindow = ArrayDeque<LearningSample>(GOVERNANCE_WINDOW_MAX + 1)
    private var lastGovernanceSnapshot = GovernanceSnapshot(
        action = GovernanceAction.WARMUP,
        confidence = 0.0,
        sampleCount = 0,
        hypoRate = 0.0,
        severeHypoCount = 0,
        highRate = 0.0,
        meanAbsTargetError = 0.0,
        reason = "Warmup",
        timestamp = System.currentTimeMillis()
    )
    private var lastGovernanceLogAt = 0L
    
    init {
        loadModels()
    }

    private fun loadModels() {
        val t3cWeights = storageHelper.getAimiFile("t3c_brain_weights.json")
        val basalWeights = storageHelper.getAimiFile("basal_adaptive_weights.json")

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
                internalAggressivenessFactor = min(internalAggressivenessFactor + 0.05, 3.0)
            }
            if (delta < -15.0) {
                internalAggressivenessFactor = max(internalAggressivenessFactor - 0.1, 0.5)
            }
        }

        // 2. Universal Adaptive Basal Heuristic (Subtle)
        if (isAdaptiveBasalActive) {
            // If BG is high (>140) and not dropping fast enough
            if (bgBefore > 140.0 && delta > -2.0) {
                internalBasalScalingFactor = min(internalBasalScalingFactor + 0.01, 3.0)
            }
            // If BG is low (<90) or dropping too fast
            if (bgBefore < 90.0 || delta < -8.0) {
                internalBasalScalingFactor = max(internalBasalScalingFactor - 0.02, 0.8)
            }
        }

        // 3. Data Logging (Synchronous for reliability in loop)
        try {
            logRecord(bgBefore, bgAfter, basalDelivered, targetBg, accel, duraISFminutes, duraISFaverage, iob)
            updateGovernanceWindow(bgBefore, bgAfter, targetBg)
            evaluateGovernance()
        } catch (e: Exception) {
            log.error("LEARNER_LOG", "Failed to log records: ${e.message}")
        }
    }

    @Synchronized
    fun getGovernanceSnapshot(): GovernanceSnapshot = lastGovernanceSnapshot

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
        val file = storageHelper.getAimiFile("basal_adaptive_records.csv")
        
        if (!file.exists()) {
            file.writeText("timestamp,bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,t3cAgg,basalScale\n")
        }
        
        val row = "${System.currentTimeMillis()},$bg,$eventualBg,$basal,$target,$accel,$duraMin,$duraAvg,$iob,$internalAggressivenessFactor,$internalBasalScalingFactor\n"
        file.appendText(row)
    }

    private fun updateGovernanceWindow(bgBefore: Double, bgAfter: Double, targetBg: Double) {
        if (!bgBefore.isFinite() || !bgAfter.isFinite() || !targetBg.isFinite()) return
        governanceWindow.addFirst(
            LearningSample(
                timestamp = System.currentTimeMillis(),
                bgBefore = bgBefore,
                bgAfter = bgAfter,
                targetBg = targetBg
            )
        )
        while (governanceWindow.size > GOVERNANCE_WINDOW_MAX) governanceWindow.removeLast()
    }

    private fun evaluateGovernance() {
        val now = System.currentTimeMillis()
        if (governanceWindow.isEmpty()) return

        val samples = governanceWindow.toList()
        val count = samples.size
        val hypoCount = samples.count { it.bgAfter < 80.0 }
        val severeHypoCount = samples.count { it.bgAfter < 70.0 }
        val highCount = samples.count { it.bgAfter > 180.0 }
        val meanAbsTargetError = samples.map { abs(it.bgAfter - it.targetBg) }.average()

        val hypoRate = hypoCount.toDouble() / count.toDouble()
        val highRate = highCount.toDouble() / count.toDouble()
        val confidence = (count.toDouble() / GOVERNANCE_WINDOW_MAX.toDouble()).coerceIn(0.0, 1.0)

        val action: GovernanceAction
        val reason: String
        if (count < GOVERNANCE_MIN_SAMPLES) {
            action = GovernanceAction.WARMUP
            reason = "Not enough samples"
        } else if (severeHypoCount >= 1 || hypoRate >= 0.20) {
            action = GovernanceAction.HOLD_CONSERVATIVE
            reason = "Hypo pressure detected"
        } else if (highRate >= 0.45 && hypoRate <= 0.05 && meanAbsTargetError >= 35.0) {
            action = GovernanceAction.ALLOW_GENTLE_INCREASE
            reason = "Persistent hyper pattern"
        } else {
            action = GovernanceAction.KEEP
            reason = "Balanced risk profile"
        }

        lastGovernanceSnapshot = GovernanceSnapshot(
            action = action,
            confidence = confidence,
            sampleCount = count,
            hypoRate = hypoRate,
            severeHypoCount = severeHypoCount,
            highRate = highRate,
            meanAbsTargetError = meanAbsTargetError,
            reason = reason,
            timestamp = now
        )

        if (action != GovernanceAction.WARMUP) {
            applyGovernanceGuardrails(action)
        }
        maybeLogGovernance(lastGovernanceSnapshot)
    }

    private fun applyGovernanceGuardrails(action: GovernanceAction) {
        when (action) {
            GovernanceAction.HOLD_CONSERVATIVE -> {
                // Soft rollback toward neutral to avoid over-learning during hypo-prone windows.
                internalBasalScalingFactor = max(0.85, internalBasalScalingFactor * 0.98)
                internalAggressivenessFactor = max(0.70, internalAggressivenessFactor * 0.97)
            }
            GovernanceAction.ALLOW_GENTLE_INCREASE -> {
                // Keep adaptation gentle even in strong hyper windows.
                internalBasalScalingFactor = min(internalBasalScalingFactor, 1.35)
                internalAggressivenessFactor = min(internalAggressivenessFactor, 2.20)
            }
            GovernanceAction.KEEP, GovernanceAction.WARMUP -> {
                // No guardrail override.
            }
        }
    }

    private fun maybeLogGovernance(snapshot: GovernanceSnapshot) {
        val now = System.currentTimeMillis()
        val hasActionChanged = snapshot.action != lastLoggedAction
        if (!hasActionChanged && (now - lastGovernanceLogAt) < GOVERNANCE_LOG_MIN_MS) return

        lastGovernanceLogAt = now
        lastLoggedAction = snapshot.action

        log.info(
            LTag.APS,
            String.format(
                Locale.US,
                "action=%s conf=%.2f n=%d hypo=%.2f severe=%d high=%.2f mae=%.1f reason=%s",
                snapshot.action.name,
                snapshot.confidence,
                snapshot.sampleCount,
                snapshot.hypoRate,
                snapshot.severeHypoCount,
                snapshot.highRate,
                snapshot.meanAbsTargetError,
                snapshot.reason
            )
        )
    }

    private var lastLoggedAction: GovernanceAction = GovernanceAction.WARMUP

    private companion object {
        const val GOVERNANCE_WINDOW_MAX = 288 // ~24h @ 5-min cadence
        const val GOVERNANCE_MIN_SAMPLES = 36 // ~3h warmup
        const val GOVERNANCE_LOG_MIN_MS = 5 * 60 * 1000L
    }
}
