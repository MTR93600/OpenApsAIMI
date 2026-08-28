package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * In-memory circadian meal prior. File persist (`AimiStorageHelper` / json) stays dump until a host lot.
 * With zero samples this matches dump `priorForHour` before the first load.
 */
internal data class CircadianMealProfileSnapshot(
    val breakfastCenterHour: Double = 8.25,
    val breakfastSamples: Int = 0,
    val lunchCenterHour: Double = 12.50,
    val lunchSamples: Int = 0,
    val dinnerCenterHour: Double = 19.00,
    val dinnerSamples: Int = 0,
    val snackCenterHour: Double = 16.00,
    val snackSamples: Int = 0,
    val dawnCenterHour: Double = 6.25,
    val dawnSamples: Int = 0,
)

internal object CircadianMealProfileStore {

    @Volatile
    private var profile = CircadianMealProfileSnapshot()

    fun priorForHour(hourOfDay: Int): Double {
        val base = defaultPriorForHour(hourOfDay)
        val current = profile
        val hour = normalizeHour(hourOfDay.toDouble())
        val breakfast = slotPrior(hour, current.breakfastCenterHour, current.breakfastSamples, peak = 0.72, width = 1.75)
        val lunch = slotPrior(hour, current.lunchCenterHour, current.lunchSamples, peak = 0.88, width = 1.60)
        val dinner = slotPrior(hour, current.dinnerCenterHour, current.dinnerSamples, peak = 0.84, width = 1.90)
        val snack = slotPrior(hour, current.snackCenterHour, current.snackSamples, peak = 0.56, width = 1.50)
        val learnedMeal = max(max(breakfast, lunch), max(dinner, snack))
        val totalMealSamples = current.breakfastSamples + current.lunchSamples +
            current.dinnerSamples + current.snackSamples
        val blend = when {
            totalMealSamples >= 12 -> 0.60
            totalMealSamples >= 8 -> 0.50
            totalMealSamples >= 4 -> 0.38
            totalMealSamples >= 2 -> 0.24
            totalMealSamples == 1 -> 0.12
            else -> 0.0
        }
        val dawnPenalty = slotPrior(
            hour = hour,
            centerHour = current.dawnCenterHour,
            samples = current.dawnSamples,
            peak = 0.20,
            width = 1.30,
        )
        return (((1.0 - blend) * base) + (blend * max(base, learnedMeal)) - dawnPenalty)
            .coerceIn(0.10, 0.95)
    }

    internal fun defaultPriorForHour(hourOfDay: Int): Double = when (hourOfDay) {
        in 5..8 -> 0.22
        in 9..10 -> 0.42
        in 11..14 -> 0.85
        in 17..21 -> 0.80
        in 15..16 -> 0.65
        in 22..23 -> 0.45
        4 -> 0.18
        else -> 0.15
    }

    internal fun replaceProfileForTests(snapshot: CircadianMealProfileSnapshot) {
        profile = snapshot
    }

    internal fun resetForTests() {
        profile = CircadianMealProfileSnapshot()
    }

    private fun slotPrior(
        hour: Double,
        centerHour: Double,
        samples: Int,
        peak: Double,
        width: Double,
    ): Double {
        if (samples <= 0) return 0.0
        val confidence = when {
            samples >= 12 -> 1.0
            samples >= 8 -> 0.85
            samples >= 4 -> 0.65
            samples >= 2 -> 0.45
            else -> 0.25
        }
        val distance = circularDistance(hour, centerHour)
        val gaussian = exp(-((distance * distance) / (2.0 * width * width)))
        return (peak * confidence * gaussian).coerceIn(0.0, 1.0)
    }

    private fun circularDistance(a: Double, b: Double): Double {
        val raw = abs(normalizeHour(a) - normalizeHour(b))
        return minOf(raw, 24.0 - raw)
    }

    private fun normalizeHour(value: Double): Double {
        var normalized = value % 24.0
        if (normalized < 0.0) normalized += 24.0
        return normalized
    }
}
