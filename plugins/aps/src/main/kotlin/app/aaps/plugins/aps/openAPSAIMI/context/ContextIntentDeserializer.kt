package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.*
import org.json.JSONObject

/**
 * Deserializer for ContextIntent from Nightscout JSON.
 * Parses compact JSON format from syncContextToNS().
 */
object ContextIntentDeserializer {
    
    fun deserialize(json: String, aapsLogger: AAPSLogger): ContextIntent? {
        return try {
            val obj = JSONObject(json)
            val type = obj.getString("type")
            
            when (type) {
                "Activity" -> Activity(
                    startTimeMs = obj.getLong("start"),
                    durationMs = obj.getLong("dur"),
                    intensity = Intensity.valueOf(obj.getString("int")),
                    confidence = obj.getDouble("conf").toFloat(),
                    activityType = Activity.ActivityType.valueOf(obj.getString("act"))
                )
                
                "Stress" -> Stress(
                    startTimeMs = obj.getLong("start"),
                    durationMs = obj.getLong("dur"),
                    intensity = Intensity.valueOf(obj.getString("int")),
                    confidence = obj.getDouble("conf").toFloat(),
                    stressType = Stress.StressType.valueOf(obj.getString("stress"))
                )
                
                "Illness" -> Illness(
                    startTimeMs = obj.getLong("start"),
                    durationMs = obj.getLong("dur"),
                    intensity = Intensity.valueOf(obj.getString("int")),
                    confidence = obj.getDouble("conf").toFloat(),
                    symptomType = Illness.SymptomType.valueOf(obj.getString("symptom"))
                )
                
                "UnannouncedMeal" -> UnannouncedMealRisk(
                    startTimeMs = obj.getLong("start"),
                    durationMs = obj.getLong("dur"),
                    intensity = Intensity.MEDIUM,
                    confidence = obj.getDouble("conf").toFloat()
                )
                
                "Alcohol" -> Alcohol(
                    startTimeMs = obj.getLong("start"),
                    durationMs = obj.getLong("dur"),
                    intensity = Intensity.MEDIUM,
                    confidence = obj.getDouble("conf").toFloat(),
                    units = obj.getDouble("units").toFloat()
                )
                
                "Travel" -> Travel(
                    startTimeMs = obj.getLong("start"),
                    durationMs = obj.getLong("dur"),
                    intensity = Intensity.MEDIUM,
                    confidence = obj.getDouble("conf").toFloat(),
                    timezoneShiftHours = obj.getInt("tz")
                )
                
                "MenstrualCycle" -> MenstrualCycle(
                    startTimeMs = obj.getLong("start"),
                    durationMs = obj.getLong("dur"),
                    intensity = Intensity.valueOf(obj.getString("int")),
                    confidence = obj.getDouble("conf").toFloat(),
                    phase = MenstrualCycle.CyclePhase.valueOf(obj.getString("phase"))
                )
                
                "Custom" -> Custom(
                    startTimeMs = obj.getLong("start"),
                    durationMs = obj.getLong("dur"),
                    intensity = Intensity.valueOf(obj.getString("int")),
                    confidence = obj.getDouble("conf").toFloat(),
                    description = obj.getString("desc"),
                    suggestedStrategy = obj.optString("strat", "")
                )
                
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
