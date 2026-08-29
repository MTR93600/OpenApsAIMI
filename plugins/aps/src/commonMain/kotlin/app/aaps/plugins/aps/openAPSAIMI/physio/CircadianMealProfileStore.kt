package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.data.json.OrgJsonCompat.optDoubleCompat
import app.aaps.core.data.json.OrgJsonCompat.optIntCompat
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("breakfast_center_hour", breakfastCenterHour)
        put("breakfast_samples", breakfastSamples)
        put("lunch_center_hour", lunchCenterHour)
        put("lunch_samples", lunchSamples)
        put("dinner_center_hour", dinnerCenterHour)
        put("dinner_samples", dinnerSamples)
        put("snack_center_hour", snackCenterHour)
        put("snack_samples", snackSamples)
        put("dawn_center_hour", dawnCenterHour)
        put("dawn_samples", dawnSamples)
    }

    companion object {
        fun fromJsonObject(json: JsonObject): CircadianMealProfileSnapshot = CircadianMealProfileSnapshot(
            breakfastCenterHour = json.optDoubleCompat("breakfast_center_hour", 8.25),
            breakfastSamples = json.optIntCompat("breakfast_samples", 0),
            lunchCenterHour = json.optDoubleCompat("lunch_center_hour", 12.50),
            lunchSamples = json.optIntCompat("lunch_samples", 0),
            dinnerCenterHour = json.optDoubleCompat("dinner_center_hour", 19.00),
            dinnerSamples = json.optIntCompat("dinner_samples", 0),
            snackCenterHour = json.optDoubleCompat("snack_center_hour", 16.00),
            snackSamples = json.optIntCompat("snack_samples", 0),
            dawnCenterHour = json.optDoubleCompat("dawn_center_hour", 6.25),
            dawnSamples = json.optIntCompat("dawn_samples", 0),
        )
    }
}

internal object CircadianMealProfileStore {
    private const val FILE_NAME = "circadian_meal_profile.json"
    private const val MEAL_DEDUPE_WINDOW_MS = 90L * 60L * 1000L
    private const val DAWN_DEDUPE_WINDOW_MS = 60L * 60L * 1000L

    private enum class Slot {
        BREAKFAST,
        LUNCH,
        DINNER,
        SNACK,
        DAWN,
    }

    /** Guards [loaded] and [profile] during the first load. Replaces `synchronized(this)`. */
    private val lock = AapsLock()

    @Volatile
    private var loaded = false

    @Volatile
    private var profile = CircadianMealProfileSnapshot()

    private val lastObservedBySlot = mutableMapOf<Slot, Long>()

    fun ensureLoaded(storage: AimiStorage) {
        if (loaded) return
        lock.withLock {
            if (loaded) return
            val path = storage.file(FILE_NAME)
            if (storage.exists(path) && storage.canRead(path)) {
                val content = storage.readText(path)
                if (content != null && content.isNotEmpty()) {
                    runCatching {
                        profile = CircadianMealProfileSnapshot.fromJsonObject(
                            Json.parseToJsonElement(content).jsonObject
                        )
                    }
                }
            }
            loaded = true
        }
    }

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

    fun observeMealWindow(storage: AimiStorage, slotLabel: String, eventTimeMs: Long) {
        ensureLoaded(storage)
        val eventHour = hourOfDayFrom(eventTimeMs)
        val slot = when (slotLabel.lowercase()) {
            "bfast", "breakfast" -> Slot.BREAKFAST
            "lunch" -> Slot.LUNCH
            "dinner" -> Slot.DINNER
            "snack" -> Slot.SNACK
            "highcarb", "meal" -> inferMealSlot(eventHour)
            else -> inferMealSlot(eventHour)
        }
        observeSlot(storage, slot, eventTimeMs, MEAL_DEDUPE_WINDOW_MS)
    }

    fun observeEstimatedMeal(storage: AimiStorage, eventTimeMs: Long) {
        ensureLoaded(storage)
        observeSlot(
            storage = storage,
            slot = inferMealSlot(hourOfDayFrom(eventTimeMs)),
            eventTimeMs = eventTimeMs,
            dedupeWindowMs = MEAL_DEDUPE_WINDOW_MS,
        )
    }

    fun observeDawnPhase(
        storage: AimiStorage,
        eventTimeMs: Long,
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        mealSignalsActive: Boolean,
    ) {
        ensureLoaded(storage)
        if (mealSignalsActive) return
        val phase = phaseOutput?.phase ?: return
        if (!(phase.isHormonalRisk || phase.isEndogenousRisk)) return
        observeSlot(storage, Slot.DAWN, eventTimeMs, DAWN_DEDUPE_WINDOW_MS)
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
        loaded = true
        lastObservedBySlot.clear()
    }

    internal fun resetForTests() {
        profile = CircadianMealProfileSnapshot()
        loaded = false
        lastObservedBySlot.clear()
    }

    private fun observeSlot(
        storage: AimiStorage,
        slot: Slot,
        eventTimeMs: Long,
        dedupeWindowMs: Long,
    ) {
        val lastObserved = lastObservedBySlot[slot]
        if (lastObserved != null && abs(eventTimeMs - lastObserved) < dedupeWindowMs) return
        val hour = hourOfDayFrom(eventTimeMs)
        profile = when (slot) {
            Slot.BREAKFAST -> profile.copy(
                breakfastCenterHour = blendHour(profile.breakfastCenterHour, profile.breakfastSamples, hour),
                breakfastSamples = incrementSamples(profile.breakfastSamples),
            )
            Slot.LUNCH -> profile.copy(
                lunchCenterHour = blendHour(profile.lunchCenterHour, profile.lunchSamples, hour),
                lunchSamples = incrementSamples(profile.lunchSamples),
            )
            Slot.DINNER -> profile.copy(
                dinnerCenterHour = blendHour(profile.dinnerCenterHour, profile.dinnerSamples, hour),
                dinnerSamples = incrementSamples(profile.dinnerSamples),
            )
            Slot.SNACK -> profile.copy(
                snackCenterHour = blendHour(profile.snackCenterHour, profile.snackSamples, hour),
                snackSamples = incrementSamples(profile.snackSamples),
            )
            Slot.DAWN -> profile.copy(
                dawnCenterHour = blendHour(profile.dawnCenterHour, profile.dawnSamples, hour),
                dawnSamples = incrementSamples(profile.dawnSamples),
            )
        }
        lastObservedBySlot[slot] = eventTimeMs
        persist(storage)
    }

    private fun persist(storage: AimiStorage) {
        val path = storage.file(FILE_NAME)
        storage.writeText(path, profile.toJsonObject().toString())
    }

    private fun inferMealSlot(hour: Double): Slot = when {
        hour in 4.0..10.5 -> Slot.BREAKFAST
        hour in 10.5..15.5 -> Slot.LUNCH
        hour in 17.0..22.5 -> Slot.DINNER
        else -> Slot.SNACK
    }

    private fun incrementSamples(current: Int): Int = (current + 1).coerceAtMost(30)

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

    private fun blendHour(currentCenter: Double, samples: Int, observedHour: Double): Double {
        if (samples <= 0) return normalizeHour(observedHour)
        val alpha = 1.0 / (samples.coerceAtMost(12) + 1.0)
        val delta = shortestHourDelta(currentCenter, observedHour)
        return normalizeHour(currentCenter + delta * alpha)
    }

    private fun hourOfDayFrom(timeMs: Long): Double {
        val localDateTime = Instant.fromEpochMilliseconds(timeMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
        val localHour = localDateTime.hour + (localDateTime.minute / 60.0)
        return normalizeHour(localHour)
    }

    private fun circularDistance(a: Double, b: Double): Double {
        val raw = abs(normalizeHour(a) - normalizeHour(b))
        return minOf(raw, 24.0 - raw)
    }

    private fun shortestHourDelta(from: Double, to: Double): Double {
        val delta = normalizeHour(to) - normalizeHour(from)
        return when {
            delta > 12.0 -> delta - 24.0
            delta < -12.0 -> delta + 24.0
            else -> delta
        }
    }

    private fun normalizeHour(value: Double): Double {
        var normalized = value % 24.0
        if (normalized < 0.0) normalized += 24.0
        return normalized
    }
}
