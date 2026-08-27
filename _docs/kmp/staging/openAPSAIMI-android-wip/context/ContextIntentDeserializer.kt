package app.aaps.plugins.aps.openAPSAIMI.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import app.aaps.core.data.json.OrgJsonCompat.hasCompat
import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.*

/**
 * Deserializer for ContextIntent from Nightscout JSON.
 * Parses compact JSON format from syncContextToNS().
 */
object ContextIntentDeserializer {
    
    fun deserialize(json: String, aapsLogger: AAPSLogger): ContextIntent? {
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val type = obj.getValue("type").jsonPrimitive.content
            
            when (type) {
                "Activity" -> {
                    val start = obj.optLongCompat("start", 0L)
                    Activity(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = Intensity.valueOf(obj.getValue("int").jsonPrimitive.content),
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat(),
                        activityType = Activity.ActivityType.valueOf(obj.getValue("act").jsonPrimitive.content)
                    )
                }
                
                "Stress" -> {
                    val start = obj.optLongCompat("start", 0L)
                    Stress(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = Intensity.valueOf(obj.getValue("int").jsonPrimitive.content),
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat(),
                        stressType = Stress.StressType.valueOf(obj.getValue("stress").jsonPrimitive.content)
                    )
                }
                
                "Illness" -> {
                    val start = obj.optLongCompat("start", 0L)
                    Illness(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = Intensity.valueOf(obj.getValue("int").jsonPrimitive.content),
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat(),
                        symptomType = Illness.SymptomType.valueOf(obj.getValue("symptom").jsonPrimitive.content)
                    )
                }
                
                "UnannouncedMeal", "UnannouncedMealRisk" -> {
                    val start = obj.optLongCompat("start", 0L)
                    UnannouncedMealRisk(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = Intensity.MEDIUM,
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat()
                    )
                }

                "SlowCarbMeal", "SlowMeal", "FatMeal" -> {
                    val start = obj.optLongCompat("start", 0L)
                    SlowCarbMeal(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = runCatching { Intensity.valueOf(obj.let { o -> if (o.hasCompat("int")) o.optStringCompat("int") else "MEDIUM" }) }.getOrDefault(Intensity.MEDIUM),
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat()
                    )
                }

                "HypoRecovery", "Hypo", "Hypoglycemia" -> {
                    val start = obj.optLongCompat("start", 0L)
                    HypoRecovery(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = runCatching { Intensity.valueOf(obj.let { o -> if (o.hasCompat("int")) o.optStringCompat("int") else "MEDIUM" }) }.getOrDefault(Intensity.MEDIUM),
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat()
                    )
                }

                "Alcohol" -> {
                    val start = obj.optLongCompat("start", 0L)
                    Alcohol(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = Intensity.MEDIUM,
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat(),
                        units = obj.getValue("units").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat()
                    )
                }
                
                "Travel" -> {
                    val start = obj.optLongCompat("start", 0L)
                    Travel(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = Intensity.MEDIUM,
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat(),
                        timezoneShiftHours = obj.getValue("tz").jsonPrimitive.intOrNull ?: error("JSONObject[\"tz\"] not an int.")
                    )
                }
                
                "MenstrualCycle" -> {
                    val start = obj.optLongCompat("start", 0L)
                    MenstrualCycle(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = Intensity.valueOf(obj.getValue("int").jsonPrimitive.content),
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat(),
                        phase = MenstrualCycle.CyclePhase.valueOf(obj.getValue("phase").jsonPrimitive.content)
                    )
                }
                
                "Custom" -> {
                    val start = obj.optLongCompat("start", 0L)
                    Custom(
                        startTimeMs = if (start > 0) start else aimiWallClockMs(),
                        durationMs = obj.getValue("dur").jsonPrimitive.longOrNull ?: error("JSONObject[\"dur\"] not a long."),
                        intensity = Intensity.valueOf(obj.getValue("int").jsonPrimitive.content),
                        confidence = obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.").toFloat(),
                        description = obj.getValue("desc").jsonPrimitive.content,
                        suggestedStrategy = obj.optStringCompat("strat")
                    )
                }
                
                else -> {
                    aapsLogger.warn(LTag.APS, "[ContextDeserializer] Unknown type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[ContextDeserializer] Parse failed: ${e.message}", e)
            null
        }
    }
}
