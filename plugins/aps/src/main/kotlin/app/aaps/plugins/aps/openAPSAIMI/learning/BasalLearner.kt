package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class BasalLearner @Inject constructor(
    private val context: Context,
    private val log: AAPSLogger
) {
    private val fileName = "aimi_basal_learner.json"
    private val file by lazy { File(context.filesDir, fileName) }
    
    // Learned multiplier (default 1.0)
    var basalMultiplier: Double = 1.0
        private set

    init {
        load()
    }

    fun getMultiplier(): Double {
        return basalMultiplier
    }

    private var fastingSamples = 0
    private var fastingSlopeSum = 0.0
    private var lastUpdateTimestamp = 0L

    /**
     * Processes new data to update the learner.
     * Should be called periodically (e.g. every 5 minutes).
     */
    fun process(currentBg: Double, currentDelta: Double, tdd7Days: Double, tdd30Days: Double, isFastingTime: Boolean) {
        val now = System.currentTimeMillis()
        
        // Only update once per day (approx) or when enough data is gathered
        if (now - lastUpdateTimestamp > 24 * 60 * 60 * 1000) {
            // Calculate scores and update
            val fastingScore = if (fastingSamples > 10) fastingSlopeSum / fastingSamples else 0.0
            update(tdd7Days, tdd30Days, fastingScore)
            
            // Reset daily accumulators
            fastingSamples = 0
            fastingSlopeSum = 0.0
            lastUpdateTimestamp = now
            save()
        }

        if (isFastingTime && currentBg in 80.0..140.0) {
            fastingSamples++
            fastingSlopeSum += currentDelta
        }
    }

    /**
     * Updates the basal multiplier based on TDD trends and fasting stability.
     * @param tdd7Days Average TDD over 7 days
     * @param tdd30Days Average TDD over 30 days (if available, else use tdd7Days)
     * @param fastingStabilityScore -1.0 (dropping) to 1.0 (rising), 0.0 is stable.
     */
    private fun update(tdd7Days: Double, tdd30Days: Double, fastingStabilityScore: Double) {
        var newMultiplier = basalMultiplier

        // 1. TDD Trend Analysis
        // If 7-day TDD is significantly higher than 30-day, we might need more basal.
        if (tdd30Days > 0) {
            val tddRatio = tdd7Days / tdd30Days
            if (tddRatio > 1.1) {
                newMultiplier *= 1.01 // Increase by 1%
                log.debug(LTag.APS, "BasalLearner: TDD rising (Ratio $tddRatio), increasing basal.")
            } else if (tddRatio < 0.9) {
                newMultiplier *= 0.99 // Decrease by 1%
                log.debug(LTag.APS, "BasalLearner: TDD falling (Ratio $tddRatio), decreasing basal.")
            }
        }

        // 2. Fasting Stability Analysis
        // If fasting BG is consistently rising (score > 0.5), increase basal.
        // If fasting BG is consistently dropping (score < -0.5), decrease basal.
        // Score is average delta per 5 min.
        if (fastingStabilityScore > 0.5) {
            newMultiplier *= 1.02 // Increase by 2%
            log.debug(LTag.APS, "BasalLearner: Fasting rise (AvgDelta $fastingStabilityScore), increasing basal.")
        } else if (fastingStabilityScore < -0.5) {
            newMultiplier *= 0.98 // Decrease by 2%
            log.debug(LTag.APS, "BasalLearner: Fasting drop (AvgDelta $fastingStabilityScore), decreasing basal.")
        }

        // Safety Clamp (0.8x to 1.3x)
        newMultiplier = max(0.8, min(1.3, newMultiplier))

        if (newMultiplier != basalMultiplier) {
            log.debug(LTag.APS, "BasalLearner: Updating multiplier from $basalMultiplier to $newMultiplier")
            basalMultiplier = newMultiplier
            save()
        }
    }

    private fun load() {
        try {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                basalMultiplier = json.optDouble("basalMultiplier", 1.0)
                lastUpdateTimestamp = json.optLong("lastUpdateTimestamp", 0L)
            }
        } catch (e: Exception) {
            log.error(LTag.APS, "Error loading BasalLearner data", e)
        }
    }

    private fun save() {
        try {
            val json = JSONObject()
            json.put("basalMultiplier", basalMultiplier)
            json.put("lastUpdateTimestamp", lastUpdateTimestamp)
            file.writeText(json.toString())
        } catch (e: Exception) {
            log.error(LTag.APS, "Error saving BasalLearner data", e)
        }
    }
}
