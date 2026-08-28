package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import app.aaps.core.data.json.OrgJsonCompat.hasCompat
import app.aaps.core.data.json.OrgJsonCompat.optBooleanCompat
import app.aaps.core.data.json.OrgJsonCompat.optDoubleCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonArrayCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.math.round

object FoodAnalysisPrompt {
    val SYSTEM_PROMPT = """
You are a Clinical Nutritionist and Diabetic Carb-Counting expert.
Analyze the meal image and return STRICT JSON ONLY.

${LlmWorldConservativePreamble.FOR_JSON_CONTRACT}

## NUTRITION PROTOCOL
1. Identify visible items and volume cues.
2. Estimate mass (g) for Carbs, Protein, and Fat.
3. Assess Glycemic Impact and confidence levels.
4. If uncertain about volume or ingredients, lean conservative on 'estimate'.
5. Protein/Fat: Do NOT hallucinate hidden oils; be realistic/conservative.
6. Separate clearly visible items from uncertain_items; never move uncertain food into visible_items.
7. If the image is unusable, still return valid JSON with needs_manual_confirmation=true and conservative estimates.

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
        var s = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        s = s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            s.substring(start, end + 1)
        } else {
            s
        }
    }

    private fun roundToHalf(value: Double): Double = round(value * 2.0) / 2.0

    private fun clamp(v: Double): Double = v.coerceIn(0.0, 500.0)

    private fun normalizeLevel(input: String?): String = input?.uppercase()?.let {
        if (it in listOf("LOW", "MEDIUM", "HIGH")) it else "MEDIUM"
    } ?: "MEDIUM"

    private fun normalizeSpeed(input: String?): String = input?.uppercase()?.let {
        if (it in listOf("FAST", "MIXED", "SLOW")) it else "MIXED"
    } ?: "MIXED"

    private fun JsonObject.optStringOr(key: String, fallback: String): String {
        if (!hasCompat(key)) return fallback
        val element = this[key]
        if (element is JsonNull) return fallback
        return optStringCompat(key)
    }

    private fun JsonObject.optMacroRange(key: String): MacroRange {
        val obj = optJsonObjectCompat(key)
        return if (obj != null) {
            val est = clamp(obj.optDoubleCompat("estimate", 0.0))
            val min = clamp(obj.optDoubleCompat("min", est))
            val max = clamp(obj.optDoubleCompat("max", est))
            MacroRange(est, min.coerceAtMost(est), max.coerceAtLeast(est))
        } else {
            val v = clamp(optDoubleCompat(key.removeSuffix("_g"), 0.0))
            MacroRange(v, v, v)
        }
    }

    private fun JsonArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = mutableListOf<String>()
        for (element in this) {
            val text = when (element) {
                is JsonNull -> ""
                is JsonPrimitive -> element.content
                else -> element.toString()
            }
            list.add(text)
        }
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
        val root = Json.parseToJsonElement(json).jsonObject

        val carbs = root.optMacroRange("carbs_g")
        val protein = root.optMacroRange("protein_g")
        val fat = root.optMacroRange("fat_g")

        val confidence = normalizeLevel(root.optStringCompat("confidence"))
        val risk = normalizeLevel(root.optStringCompat("hidden_carb_risk"))
        val manualConf = root.optBooleanCompat("needs_manual_confirmation")

        val fpu = computeFpu(fat.estimate, protein.estimate)
        val (recCarbs, recReason) = computeRecommendedCarbs(carbs, confidence, risk, manualConf)

        val visibleJson = root.optJsonArrayCompat("visible_items")
        val visibleItems = mutableListOf<VisibleFoodItem>()
        if (visibleJson != null) {
            for (element in visibleJson) {
                val item = element as? JsonObject ?: continue
                visibleItems.add(VisibleFoodItem(item.optStringCompat("name"), item.optStringCompat("amount")))
            }
        }

        val parsed = EstimationResult(
            description = root.optStringOr("food_name", "Unknown Food"),
            visibleItems = visibleItems,
            uncertainItems = root.optJsonArrayCompat("uncertain_items").toStringList(),
            carbs = carbs,
            protein = protein,
            fat = fat,
            fpuEquivalent = fpu,
            glycemicIndex = normalizeLevel(root.optStringCompat("glycemic_index")),
            absorptionSpeed = normalizeSpeed(root.optStringCompat("absorption_speed")),
            confidence = confidence,
            portionConfidence = normalizeLevel(root.optStringCompat("portion_confidence")),
            hiddenCarbRisk = risk,
            needsManualConfirmation = manualConf,
            insulinRelevantNotes = root.optJsonArrayCompat("insulin_relevant_notes").toStringList(),
            reasoning = root.optStringOr("rationale", "No rationale provided."),
            recommendedCarbsForDose = roundToHalf(recCarbs),
            recommendedCarbsReason = recReason
        )
        return MealAdvisorResponseSanitizer.secureEstimationResult(parsed)
    }

    fun emptyErrorResult(desc: String, reason: String): EstimationResult {
        val zero = MacroRange(0.0, 0.0, 0.0)
        val err = EstimationResult(
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
        return MealAdvisorResponseSanitizer.secureEstimationResult(err)
    }
}
