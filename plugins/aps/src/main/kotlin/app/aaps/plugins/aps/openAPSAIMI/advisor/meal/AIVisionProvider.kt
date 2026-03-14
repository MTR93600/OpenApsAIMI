package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import android.graphics.Bitmap
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.round

/**
 * Common interface for AI vision providers
 */
interface AIVisionProvider {
    suspend fun estimateFromImage(bitmap: Bitmap, userDescription: String, apiKey: String): EstimationResult
    val displayName: String
    val providerId: String
}

/**
 * Refined Nutrition Models for V2
 */
data class VisibleFoodItem(val name: String, val amountInfo: String)

data class MacroRange(val estimate: Double, val min: Double, val max: Double)

data class EstimationResult(
    val description: String,
    val visibleItems: List<VisibleFoodItem>,
    val uncertainItems: List<String>,
    val carbs: MacroRange,
    val protein: MacroRange,
    val fat: MacroRange,
    val fpuEquivalent: Double,
    val glycemicIndex: String,
    val absorptionSpeed: String,
    val confidence: String,
    val portionConfidence: String,
    val hiddenCarbRisk: String,
    val needsManualConfirmation: Boolean,
    val insulinRelevantNotes: List<String>,
    val reasoning: String,
    val recommendedCarbsForDose: Double,
    val recommendedCarbsReason: String
)

object FoodAnalysisPrompt {
    const val SYSTEM_PROMPT = """
You are a Clinical Nutritionist and Diabetic Carb-Counting expert.
Analyze the meal image and return STRICT JSON ONLY.

## NUTRITION PROTOCOL
1. Identify visible items and volume cues.
2. Estimate mass (g) for Carbs, Protein, and Fat.
3. Assess Glycemic Impact and confidence levels.
4. If uncertain about volume or ingredients, lean conservative on 'estimate'.
5. Protein/Fat: Do NOT hallucinate hidden oils; be realistic/conservative.

## JSON SCHEMA
{
  "food_name": "string",
  "visible_items": [{"name": "string", "amount": "string"}],
  "uncertain_items": ["string"],
  "carbs_g": { "estimate": number, "min": number, "max": number },
  "protein_g": { "estimate": number, "min": number, "max": number },
  "fat_g": { "estimate": number, "min": number, "max": number },
  "absorption_speed": "FAST" | "MIXED" | "SLOW",
  "glycemic_index": "LOW" | "MEDIUM" | "HIGH",
  "confidence": "LOW" | "MEDIUM" | "HIGH",
  "portion_confidence": "LOW" | "MEDIUM" | "HIGH",
  "hidden_carb_risk": "LOW" | "MEDIUM" | "HIGH",
  "needs_manual_confirmation": boolean,
  "insulin_relevant_notes": ["concise notes on glazes, hidden sugars, or high fiber"],
  "rationale": "concise nutrition summary"
}
"""

    fun cleanJsonResponse(raw: String): String {
        return raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { if (!it.startsWith("{")) it.substringAfter("{").let { s -> "{$s" }.substringBeforeLast("}").let { s -> "$s}" } else it }
    }

    private fun roundToHalf(value: Double): Double = round(value * 2.0) / 2.0

    private fun clamp(v: Double): Double = v.coerceIn(0.0, 500.0)

    private fun normalizeLevel(input: String?): String = input?.uppercase()?.let { 
        if (it in listOf("LOW", "MEDIUM", "HIGH")) it else "MEDIUM" 
    } ?: "MEDIUM"

    private fun normalizeSpeed(input: String?): String = input?.uppercase()?.let { 
        if (it in listOf("FAST", "MIXED", "SLOW")) it else "MIXED" 
    } ?: "MIXED"

    private fun JSONObject.optMacroRange(key: String): MacroRange {
        val obj = optJSONObject(key)
        return if (obj != null) {
            val est = clamp(obj.optDouble("estimate", 0.0))
            val min = clamp(obj.optDouble("min", est))
            val max = clamp(obj.optDouble("max", est))
            MacroRange(est, min.coerceAtMost(est), max.coerceAtLeast(est))
        } else {
            val v = clamp(optDouble(key.removeSuffix("_g"), 0.0))
            MacroRange(v, v, v)
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until length()) list.add(optString(i))
        return list.filter { it.isNotBlank() }
    }

    private fun computeFpu(fat: Double, protein: Double): Double {
        return roundToHalf((fat * 9.0 + protein * 4.0) / 10.0)
    }

    private fun computeRecommendedCarbs(carbs: MacroRange, confidence: String, hiddenRisk: String, manualConf: Boolean): Pair<Double, String> {
        val conf = confidence.uppercase()
        val risk = hiddenRisk.uppercase()
        
        return when {
            (conf == "LOW" && manualConf) || (conf == "LOW" && risk == "LOW") -> 
                carbs.min to "Confidence LOW: using minimum to avoid over-bolusing."
            conf == "LOW" && risk == "HIGH" -> 
                carbs.estimate to "Confidence LOW but risk HIGH: using baseline estimate."
            conf == "MEDIUM" && risk == "HIGH" -> 
                roundToHalf((carbs.estimate + carbs.max) / 2.0) to "Medium confidence & High Risk: leaning towards max."
            else -> 
                carbs.estimate to "Stable estimate applied."
        }
    }

    fun parseJsonToResult(json: String): EstimationResult {
        val root = JSONObject(json)
        
        val carbs = root.optMacroRange("carbs_g")
        val protein = root.optMacroRange("protein_g")
        val fat = root.optMacroRange("fat_g")
        
        val confidence = normalizeLevel(root.optString("confidence"))
        val risk = normalizeLevel(root.optString("hidden_carb_risk"))
        val manualConf = root.optBoolean("needs_manual_confirmation", false)
        
        val fpu = computeFpu(fat.estimate, protein.estimate)
        val (recCarbs, recReason) = computeRecommendedCarbs(carbs, confidence, risk, manualConf)

        val visibleJson = root.optJSONArray("visible_items")
        val visibleItems = mutableListOf<VisibleFoodItem>()
        if (visibleJson != null) {
            for (i in 0 until visibleJson.length()) {
                val item = visibleJson.optJSONObject(i) ?: continue
                visibleItems.add(VisibleFoodItem(item.optString("name"), item.optString("amount")))
            }
        }

        return EstimationResult(
            description = root.optString("food_name", "Unknown Food"),
            visibleItems = visibleItems,
            uncertainItems = root.optJSONArray("uncertain_items").toStringList(),
            carbs = carbs,
            protein = protein,
            fat = fat,
            fpuEquivalent = fpu,
            glycemicIndex = normalizeLevel(root.optString("glycemic_index")),
            absorptionSpeed = normalizeSpeed(root.optString("absorption_speed")),
            confidence = confidence,
            portionConfidence = normalizeLevel(root.optString("portion_confidence")),
            hiddenCarbRisk = risk,
            needsManualConfirmation = manualConf,
            insulinRelevantNotes = root.optJSONArray("insulin_relevant_notes").toStringList(),
            reasoning = root.optString("rationale", "No rationale provided."),
            recommendedCarbsForDose = roundToHalf(recCarbs),
            recommendedCarbsReason = recReason
        )
    }

    fun emptyErrorResult(desc: String, reason: String): EstimationResult {
        val zero = MacroRange(0.0, 0.0, 0.0)
        return EstimationResult(
            description = desc,
            visibleItems = emptyList(),
            uncertainItems = emptyList(),
            carbs = zero,
            protein = zero,
            fat = zero,
            fpuEquivalent = 0.0,
            glycemicIndex = "MEDIUM",
            absorptionSpeed = "MIXED",
            confidence = "LOW",
            portionConfidence = "LOW",
            hiddenCarbRisk = "LOW",
            needsManualConfirmation = true,
            insulinRelevantNotes = emptyList(),
            reasoning = reason,
            recommendedCarbsForDose = 0.0,
            recommendedCarbsReason = "Error recovery"
        )
    }
}
