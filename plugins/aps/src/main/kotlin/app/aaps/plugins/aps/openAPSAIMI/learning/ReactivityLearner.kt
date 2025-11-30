package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import org.json.JSONObject
import java.io.File
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

@Singleton
class ReactivityLearner @Inject constructor(
    private val context: Context,
    private val log: AAPSLogger
) {
    private val fileName = "aimi_reactivity_learner.json"
    private val file by lazy { File(context.filesDir, fileName) }

    // Time buckets
    enum class TimeBucket {
        MORNING,    // 06:00 - 11:00
        AFTERNOON,  // 11:00 - 17:00
        EVENING,    // 17:00 - 23:00
        NIGHT       // 23:00 - 06:00
    }

    // Learned factors (default 1.0)
    private val factors = mutableMapOf(
        TimeBucket.MORNING to 1.0,
        TimeBucket.AFTERNOON to 1.0,
        TimeBucket.EVENING to 1.0,
        TimeBucket.NIGHT to 1.0
    )

    init {
        load()
    }

    fun getFactor(time: LocalTime): Double {
        val bucket = getBucket(time)
        return factors[bucket] ?: 1.0
    }

    fun getFactorForBucket(bucket: TimeBucket): Double {
        return factors[bucket] ?: 1.0
    }

    private var currentMealStartBg = 0.0
    private var currentMealPeakBg = 0.0
    private var currentMealMinBg = 999.0
    private var currentMealStartTime = 0L
    private var isTrackingMeal = false
    private var mealBucket: TimeBucket? = null

    fun process(currentBg: Double, isMealActive: Boolean, time: LocalTime) {
        if (isMealActive) {
            if (!isTrackingMeal) {
                // Start new meal track
                isTrackingMeal = true
                currentMealStartBg = currentBg
                currentMealPeakBg = currentBg
                currentMealMinBg = currentBg
                currentMealStartTime = System.currentTimeMillis()
                mealBucket = getBucket(time)
            } else {
                // Update existing track
                currentMealPeakBg = max(currentMealPeakBg, currentBg)
                currentMealMinBg = min(currentMealMinBg, currentBg)
            }
        } else {
            if (isTrackingMeal) {
                // Meal finished, analyze
                isTrackingMeal = false
                val durationMinutes = ((System.currentTimeMillis() - currentMealStartTime) / 60000).toInt()
                
                // Only analyze if meal was significant (e.g. > 60 mins)
                if (durationMinutes > 60 && mealBucket != null) {
                    update(mealBucket!!, currentMealPeakBg, durationMinutes, currentMealMinBg)
                }
                
                // Reset
                mealBucket = null
                currentMealPeakBg = 0.0
                currentMealMinBg = 999.0
            }
        }
    }

    /**
     * Updates the reactivity factor for a specific time bucket based on post-prandial performance.
     * @param bucket The time bucket to update
     * @param peakBg The peak BG reached after a meal (mg/dL)
     * @param recoveryTimeMinutes Time in minutes to return to range (< 140 mg/dL)
     * @param minBg Post-correction minimum BG (to detect hypos)
     */
    private fun update(bucket: TimeBucket, peakBg: Double, recoveryTimeMinutes: Int, minBg: Double) {
        var currentFactor = factors[bucket] ?: 1.0
        var newFactor = currentFactor

        // 1. Hypo Safety Check
        // If we went too low (< 70), we were too aggressive.
        if (minBg < 70) {
            newFactor *= 0.90 // Decrease by 10% (was 5%)
            log.debug(LTag.APS, "ReactivityLearner: Hypo detected ($minBg), decreasing $bucket factor.")
        } 
        // 2. Hyper / Slow Recovery Check
        // If peak was high (> 160) OR recovery took too long (> 180 min), we were too passive.
        else if (peakBg > 160 || recoveryTimeMinutes > 180) {
            newFactor *= 1.10 // Increase by 10% (was 2%)
            log.debug(LTag.APS, "ReactivityLearner: Hyper/Slow recovery (Peak:$peakBg, Time:$recoveryTimeMinutes), increasing $bucket factor.")
        }
        // 3. Good Performance
        // If peak < 160 AND recovery < 120 min AND no hypo, we are doing well.
        // Maybe slight decay towards 1.0? Or just keep it. For now, keep it.

        // Safety Clamp (0.5x to 2.0x)
        newFactor = max(0.5, min(2.0, newFactor))

        if (newFactor != currentFactor) {
            log.debug(LTag.APS, "ReactivityLearner: Updating $bucket factor from $currentFactor to $newFactor")
            factors[bucket] = newFactor
            save()
        }
    }

    private fun getBucket(time: LocalTime): TimeBucket {
        return when (time.hour) {
            in 6..10 -> TimeBucket.MORNING
            in 11..16 -> TimeBucket.AFTERNOON
            in 17..22 -> TimeBucket.EVENING
            else -> TimeBucket.NIGHT
        }
    }

    private fun load() {
        try {
            if (file.exists()) {
                val json = JSONObject(file.readText())
                TimeBucket.values().forEach { bucket ->
                    factors[bucket] = json.optDouble(bucket.name, 1.0)
                }
            }
        } catch (e: Exception) {
            log.error(LTag.APS, "Error loading ReactivityLearner data", e)
        }
    }

    private fun save() {
        try {
            val json = JSONObject()
            factors.forEach { (bucket, value) ->
                json.put(bucket.name, value)
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            log.error(LTag.APS, "Error saving ReactivityLearner data", e)
        }
    }
}
