package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-Scale Basal Learner
 * 
 * Replaces the old single-scale (1x/day) learner with a 3-tier approach:
 * - Short-term (30 min): Reacts to acute changes (stress, illness onset)
 * - Medium-term (6 hours): Captures intra-day patterns
 * - Long-term (24 hours): Structural adjustments based on TDD
 * 
 * Each tier uses exponentially weighted observations to prioritize recent data.
 */
@Singleton
class BasalLearner @Inject constructor(
    private val context: Context,
    private val log: AAPSLogger,
    private val storageHelper: AimiStorageHelper
) {
    private val fileName = "aimi_basal_learner.json"
    private val file by lazy { storageHelper.getAimiFile(fileName) }

    // === Multi-Scale Multipliers ===
    private var shortTermMultiplier = 1.0   // Updated every 30 min
    private var mediumTermMultiplier = 1.0  // Updated every 6 hours
    private var longTermMultiplier = 1.0    // Updated every 24 hours

    // === Timestamps for update scheduling ===
    private var lastShortUpdate = 0L
    private var lastMediumUpdate = 0L
    private var lastLongUpdate = 0L

    // === Accumulators for each scale ===
    private val shortTermBuffer = mutableListOf<TimestampedBg>()
    private val mediumTermBuffer = mutableListOf<TimestampedBg>()

    // === Legacy accumulators (for long-term, fasting-based) ===
    private var fastingSamples = 0
    private var fastingSlopeSum = 0.0
    private var fastingBgSum = 0.0

    // === Configuration ===
    companion object {
        private const val SHORT_INTERVAL_MS = 30 * 60 * 1000L       // 30 minutes
        private const val MEDIUM_INTERVAL_MS = 6 * 60 * 60 * 1000L  // 6 hours
        private const val LONG_INTERVAL_MS = 24 * 60 * 60 * 1000L   // 24 hours

        private const val SHORT_WINDOW_MS = 2 * 60 * 60 * 1000L     // 2 hours of data
        private const val MEDIUM_WINDOW_MS = 24 * 60 * 60 * 1000L   // 24 hours of data

        private const val TAU_SHORT_MS = 30 * 60 * 1000.0           // 30 min half-life
        private const val TAU_MEDIUM_MS = 3 * 60 * 60 * 1000.0      // 3 hour half-life

        private const val ALPHA_SHORT = 0.25   // Fast EMA
        private const val ALPHA_MEDIUM = 0.15  // Moderate EMA
        private const val ALPHA_LONG = 0.10    // Slow EMA

        private const val CLAMP_MIN = 0.70
        private const val CLAMP_MAX = 2.50
    }

    data class TimestampedBg(val timestamp: Long, val bg: Double, val delta: Double)

    init {
        load()
    }

    /**
     * Returns the combined multiplier from all three scales.
     * Weights: Short 40%, Medium 35%, Long 25%
     */
    fun getMultiplier(): Double {
        val combined = shortTermMultiplier * 0.40 +
                       mediumTermMultiplier * 0.35 +
                       longTermMultiplier * 0.25
        return combined.coerceIn(CLAMP_MIN, CLAMP_MAX)
    }

    /**
     * Main processing function. Called every 5 minutes from DetermineBasalAIMI2.
     */
    fun process(
        currentBg: Double,
        currentDelta: Double,
        tdd7Days: Double,
        tdd30Days: Double,
        isFastingTime: Boolean
    ) {
        val now = System.currentTimeMillis()
        val observation = TimestampedBg(now, currentBg, currentDelta)

        // Add to buffers
        shortTermBuffer.add(observation)
        mediumTermBuffer.add(observation)

        // Prune old data
        pruneBuffer(shortTermBuffer, SHORT_WINDOW_MS, now)
        pruneBuffer(mediumTermBuffer, MEDIUM_WINDOW_MS, now)

        // === SHORT-TERM UPDATE (every 30 min) ===
        if (now - lastShortUpdate >= SHORT_INTERVAL_MS && shortTermBuffer.size >= 3) {
            updateShortTerm(now)
            lastShortUpdate = now
        }

        // === MEDIUM-TERM UPDATE (every 6 hours) ===
        if (now - lastMediumUpdate >= MEDIUM_INTERVAL_MS && mediumTermBuffer.size >= 12) {
            updateMediumTerm(now)
            lastMediumUpdate = now
        }

        // === LONG-TERM UPDATE (every 24 hours, fasting-based) ===
        if (isFastingTime && currentBg > 70.0) {
            fastingSamples++
            fastingSlopeSum += currentDelta
            fastingBgSum += currentBg
        }

        if (now - lastLongUpdate >= LONG_INTERVAL_MS) {
            updateLongTerm(tdd7Days, tdd30Days)
            lastLongUpdate = now
            // Reset fasting accumulators
            fastingSamples = 0
            fastingSlopeSum = 0.0
            fastingBgSum = 0.0
        }

        save()
    }

    /**
     * Event-driven update: Force recalculation after hypo events.
     * Called externally when hypo is detected.
     */
    fun onHypoDetected() {
        log.info(LTag.APS, "BasalLearner: Hypo detected, reducing short-term multiplier")
        shortTermMultiplier = (shortTermMultiplier * 0.90).coerceIn(CLAMP_MIN, CLAMP_MAX)
        save()
    }

    /**
     * Event-driven update: Force recalculation after persistent hyper.
     * Called externally when hyper > 180 for > 60 min.
     */
    fun onPersistentHyper() {
        log.info(LTag.APS, "BasalLearner: Persistent hyper detected, increasing short-term multiplier")
        shortTermMultiplier = (shortTermMultiplier * 1.10).coerceIn(CLAMP_MIN, CLAMP_MAX)
        save()
    }

    // === Private Update Functions ===

    private fun updateShortTerm(now: Long) {
        if (shortTermBuffer.isEmpty()) return

        // Compute weighted average delta (temporal decay)
        val weightedError = computeWeightedError(shortTermBuffer, now, TAU_SHORT_MS)

        // Adjust multiplier based on error
        // Positive error (rising) → need more basal → increase multiplier
        // Negative error (dropping) → need less basal → decrease multiplier
        val adjustment = when {
            weightedError > 1.0 -> 1.05   // Rising → +5%
            weightedError > 0.5 -> 1.02   // Slightly rising → +2%
            weightedError < -1.0 -> 0.95  // Dropping → -5%
            weightedError < -0.5 -> 0.98  // Slightly dropping → -2%
            else -> 1.0                   // Stable
        }

        val newValue = shortTermMultiplier * adjustment
        shortTermMultiplier = ema(shortTermMultiplier, newValue, ALPHA_SHORT).coerceIn(CLAMP_MIN, CLAMP_MAX)

        log.debug(LTag.APS, "BasalLearner: Short-term update. WeightedError=${"%.2f".format(weightedError)}, " +
            "Adjustment=$adjustment, NewMultiplier=${"%.3f".format(shortTermMultiplier)}")
    }

    private fun updateMediumTerm(now: Long) {
        if (mediumTermBuffer.isEmpty()) return

        val weightedError = computeWeightedError(mediumTermBuffer, now, TAU_MEDIUM_MS)
        val avgBg = computeWeightedAvgBg(mediumTermBuffer, now, TAU_MEDIUM_MS)

        // More nuanced adjustment based on both trend and level
        val adjustment = when {
            // High & Stable/Rising → Need significantly more
            avgBg > 150 && weightedError >= -0.5 -> 1.12
            // High & Dropping → Slight increase (still elevated)
            avgBg > 150 && weightedError < -0.5 -> 1.05
            // Low & Stable/Dropping → Need less
            avgBg < 85 && weightedError <= 0.5 -> 0.90
            // Low & Rising → Slight decrease
            avgBg < 85 && weightedError > 0.5 -> 0.95
            // Normal range, adjust based on trend
            weightedError > 1.0 -> 1.06
            weightedError > 0.5 -> 1.03
            weightedError < -1.0 -> 0.94
            weightedError < -0.5 -> 0.97
            else -> 1.0
        }

        val newValue = mediumTermMultiplier * adjustment
        mediumTermMultiplier = ema(mediumTermMultiplier, newValue, ALPHA_MEDIUM).coerceIn(CLAMP_MIN, CLAMP_MAX)

        log.debug(LTag.APS, "BasalLearner: Medium-term update. AvgBG=${"%.0f".format(avgBg)}, " +
            "WeightedError=${"%.2f".format(weightedError)}, NewMultiplier=${"%.3f".format(mediumTermMultiplier)}")
    }

    private fun updateLongTerm(tdd7Days: Double, tdd30Days: Double) {
        val fastingScore = if (fastingSamples > 10) fastingSlopeSum / fastingSamples else 0.0
        val avgFastingBg = if (fastingSamples > 10) fastingBgSum / fastingSamples else 100.0

        var adjustment = 1.0

        // TDD Trend Analysis
        if (tdd30Days > 0) {
            val tddRatio = tdd7Days / tdd30Days
            adjustment *= when {
                tddRatio > 1.15 -> 1.10  // Significant increase in insulin needs
                tddRatio > 1.05 -> 1.05
                tddRatio < 0.85 -> 0.90  // Significant decrease
                tddRatio < 0.95 -> 0.95
                else -> 1.0
            }
        }

        // Fasting Analysis
        adjustment *= when {
            avgFastingBg > 140 && fastingScore > -0.5 -> 1.10  // High & stable/rising
            avgFastingBg > 120 && fastingScore > 0.5 -> 1.08   // Elevated & rising
            avgFastingBg < 80 && fastingScore < 0.5 -> 0.90    // Low & stable/dropping
            fastingScore > 1.0 -> 1.06                         // Rising
            fastingScore < -1.0 -> 0.94                        // Dropping
            else -> 1.0
        }

        val newValue = longTermMultiplier * adjustment
        longTermMultiplier = ema(longTermMultiplier, newValue, ALPHA_LONG).coerceIn(CLAMP_MIN, CLAMP_MAX)

        log.info(LTag.APS, "BasalLearner: Long-term update. TDD7/30=${"%.2f".format(tdd7Days/max(1.0, tdd30Days))}, " +
            "AvgFastingBG=${"%.0f".format(avgFastingBg)}, FastingSlope=${"%.2f".format(fastingScore)}, " +
            "NewMultiplier=${"%.3f".format(longTermMultiplier)}")
    }

    // === Helper Functions ===

    private fun computeWeightedError(buffer: List<TimestampedBg>, now: Long, tau: Double): Double {
        if (buffer.isEmpty()) return 0.0

        var weightedSum = 0.0
        var weightSum = 0.0

        for (obs in buffer) {
            val ageMs = (now - obs.timestamp).toDouble()
            val weight = exp(-ageMs / tau)
            weightedSum += obs.delta * weight
            weightSum += weight
        }

        return if (weightSum > 0) weightedSum / weightSum else 0.0
    }

    private fun computeWeightedAvgBg(buffer: List<TimestampedBg>, now: Long, tau: Double): Double {
        if (buffer.isEmpty()) return 100.0

        var weightedSum = 0.0
        var weightSum = 0.0

        for (obs in buffer) {
            val ageMs = (now - obs.timestamp).toDouble()
            val weight = exp(-ageMs / tau)
            weightedSum += obs.bg * weight
            weightSum += weight
        }

        return if (weightSum > 0) weightedSum / weightSum else 100.0
    }

    private fun pruneBuffer(buffer: MutableList<TimestampedBg>, windowMs: Long, now: Long) {
        buffer.removeAll { now - it.timestamp > windowMs }
    }

    private fun ema(prev: Double, obs: Double, alpha: Double): Double {
        return (1 - alpha) * prev + alpha * obs
    }

    // === Persistence ===

    private fun load() {
        storageHelper.loadFileSafe(file,
            onSuccess = { content ->
                val json = JSONObject(content)
                shortTermMultiplier = json.optDouble("shortTermMultiplier", 1.0)
                mediumTermMultiplier = json.optDouble("mediumTermMultiplier", 1.0)
                longTermMultiplier = json.optDouble("longTermMultiplier", 1.0)
                lastShortUpdate = json.optLong("lastShortUpdate", 0L)
                lastMediumUpdate = json.optLong("lastMediumUpdate", 0L)
                lastLongUpdate = json.optLong("lastLongUpdate", 0L)
                log.info(LTag.APS, "BasalLearner: ✅ Loaded multipliers S=${"%.3f".format(shortTermMultiplier)} " +
                    "M=${"%.3f".format(mediumTermMultiplier)} L=${"%.3f".format(longTermMultiplier)}")
            },
            onError = { e ->
                log.warn(LTag.APS, "BasalLearner: Load failed, using defaults (multiplier=1.0)")
            }
        )
    }

    private fun save() {
        val json = JSONObject()
        json.put("shortTermMultiplier", shortTermMultiplier)
        json.put("mediumTermMultiplier", mediumTermMultiplier)
        json.put("longTermMultiplier", longTermMultiplier)
        json.put("lastShortUpdate", lastShortUpdate)
        json.put("lastMediumUpdate", lastMediumUpdate)
        json.put("lastLongUpdate", lastLongUpdate)
        storageHelper.saveFileSafe(file, json.toString())
    }

    // === Legacy API compatibility ===
    @Deprecated("Use getMultiplier() instead", ReplaceWith("getMultiplier()"))
    val basalMultiplier: Double
        get() = getMultiplier()
}
