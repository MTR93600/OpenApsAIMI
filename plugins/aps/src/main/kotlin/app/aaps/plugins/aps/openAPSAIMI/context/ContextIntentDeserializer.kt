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
                "Activity" -> {
                    val start = obj.optLong("start", 0L)
                    Activity(
                        startTimeMs = if (start > 0) start else System.currentTimeMillis(),
                        durationMs = obj.getLong("dur"),
                        intensity = Intensity.valueOf(obj.getString("int")),
                        confidence = obj.getDouble("conf").toFloat(),
                        activityType = Activity.ActivityType.valueOf(obj.getString("act"))
                    )
                }
                
                "Stress" -> {
                    val start = obj.optLong("start", 0L)
                    Stress(
                        startTimeMs = if (start > 0) start else System.currentTimeMillis(),
                        durationMs = obj.getLong("dur"),
                        intensity = Intensity.valueOf(obj.getString("int")),
                        confidence = obj.getDouble("conf").toFloat(),
                        stressType = Stress.StressType.valueOf(obj.getString("stress"))
                    )
                }
                
                "Illness" -> {
                    val start = obj.optLong("start", 0L)
                    Illness(
                        startTimeMs = if (start > 0) start else System.currentTimeMillis(),
                        durationMs = obj.getLong("dur"),
                        intensity = Intensity.valueOf(obj.getString("int")),
                        confidence = obj.getDouble("conf").toFloat(),
                        symptomType = Illness.SymptomType.valueOf(obj.getString("symptom"))
                    )
                }
                
                "UnannouncedMeal", "UnannouncedMealRisk" -> {
                    val start = obj.optLong("start", 0L)
                    UnannouncedMealRisk(
                        startTimeMs = if (start > 0) start else System.currentTimeMillis(),
                        durationMs = obj.getLong("dur"),
                        intensity = Intensity.MEDIUM,
                        confidence = obj.getDouble("conf").toFloat()
                    )
                }
                
                "Alcohol" -> {
                    val start = obj.optLong("start", 0L)
                    Alcohol(
                        startTimeMs = if (start > 0) start else System.currentTimeMillis(),
                        durationMs = obj.getLong("dur"),
                        intensity = Intensity.MEDIUM,
                        confidence = obj.getDouble("conf").toFloat(),
                        units = obj.getDouble("units").toFloat()
                    )
                }
                
                "Travel" -> {
                    val start = obj.optLong("start", 0L)
                    Travel(
                        startTimeMs = if (start > 0) start else System.currentTimeMillis(),
                        durationMs = obj.getLong("dur"),
                        intensity = Intensity.MEDIUM,
                        confidence = obj.getDouble("conf").toFloat(),
                        timezoneShiftHours = obj.getInt("tz")
                    )
                }
                
                "MenstrualCycle" -> {
                    val start = obj.optLong("start", 0L)
                    MenstrualCycle(
                        startTimeMs = if (start > 0) start else System.currentTimeMillis(),
                        durationMs = obj.getLong("dur"),
                        intensity = Intensity.valueOf(obj.getString("int")),
                        confidence = obj.getDouble("conf").toFloat(),
                        phase = MenstrualCycle.CyclePhase.valueOf(obj.getString("phase"))
                    )
                }
                
                "Custom" -> {
                    val start = obj.optLong("start", 0L)
                    Custom(
                        startTimeMs = if (start > 0) start else System.currentTimeMillis(),
                        durationMs = obj.getLong("dur"),
                        intensity = Intensity.valueOf(obj.getString("int")),
                        confidence = obj.getDouble("conf").toFloat(),
                        description = obj.getString("desc"),
                        suggestedStrategy = obj.optString("strat", "")
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
