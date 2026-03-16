package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * T3cNeuralLearner identifies user-specific glycemic response patterns 
 * during Brittle Mode intervention.
 * 
 * Strategy:
 * 1. Track stability after T3C engagements.
 * 2. If BG remains high/stagnant after correction -> Increase aggressiveness.
 * 3. If BG drops too fast or causes hypo -> Reduce aggressiveness.
 */
@Singleton
class T3cNeuralLearner @Inject constructor(
    private val preferences: Preferences,
    private val log: AAPSLogger
) {
    private var internalAggressivenessFactor = 1.0
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /**
     * Estimates the adaptive factor based on recent performance.
     */
    fun getAdaptiveFactor(): Double {
        val baseAggressiveness = preferences.get(DoubleKey.OApsAIMIT3cAggressiveness)
        return baseAggressiveness * internalAggressivenessFactor
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
        val delta = bgAfter - bgBefore
        
        // 1. Heuristic logic (Live adjustment)
        if (isT3cActive) {
            if (bgBefore > 180.0 && delta >= 0) {
                internalAggressivenessFactor = min(internalAggressivenessFactor + 0.05, 2.0)
            }
            if (delta < -15.0) {
                internalAggressivenessFactor = max(internalAggressivenessFactor - 0.1, 0.5)
            }
        }

        // 2. Data Logging (Neural "Food")
        // We log even if T3c is OFF for "Audit/Discovery" mode
        scope.launch {
            try {
                logRecord(bgBefore, bgAfter, basalDelivered, targetBg, accel, duraISFminutes, duraISFaverage, iob)
            } catch (e: Exception) {
                log.error("T3C_LOG", "Failed to log T3C record: ${e.message}")
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
        val externalDir = android.os.Environment.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS + "/AAPS") ?: return
        val file = java.io.File(externalDir, "t3c_records.csv")
        
        if (!file.exists()) {
            file.writeText("timestamp,bg,eventualBg,basal,target,accel,duraMin,duraAvg,iob,currentAggFactor\n")
        }
        
        val row = "${System.currentTimeMillis()},$bg,$eventualBg,$basal,$target,$accel,$duraMin,$duraAvg,$iob,$internalAggressivenessFactor\n"
        file.appendText(row)
    }
}
