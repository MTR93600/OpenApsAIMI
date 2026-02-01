package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import android.graphics.Bitmap
import org.json.JSONObject

/**
 * Common interface for AI vision providers
 * All providers must implement this to estimate food macros from image
 */
interface AIVisionProvider {
    /**
     * Estimate food macros from image bitmap
     * @param bitmap The food image
     * @param apiKey The API key for this provider
     * @return EstimationResult with food data
     * @throws Exception on API errors
     */
    suspend fun estimateFromImage(bitmap: Bitmap, apiKey: String): EstimationResult
    
    /**
     * Provider display name (e.g., "OpenAI GPT-4o")
     */
    val displayName: String
    
    /**
     * Provider identifier (e.g., "OPENAI")
     */
    val providerId: String
}

/**
 * Common estimation result across all providers
 */
/**
 * Common estimation result across all providers
 */
data class EstimationResult(
    val description: String,
    val carbsGrams: Double,      // Best estimate
    val carbsMin: Double = 0.0,
    val carbsMax: Double = 0.0,
    val proteinGrams: Double,
    val fatGrams: Double,
    val fpuEquivalent: Double,   // Calculated in Kotlin
    val glycemicIndex: String = "MEDIUM", // LOW, MEDIUM, HIGH
    val absorptionSpeed: String = "MIXED", // FAST, MIXED, SLOW
    val confidence: String = "MEDIUM",     // LOW, MEDIUM, HIGH
    val reasoning: String
)

object FoodAnalysisPrompt {
    const val SYSTEM_PROMPT = """
You are an expert T1D nutritionist and diabetes-safe estimator.
Estimate meal macros from the image. Focus on realistic portion sizing.

CRITICAL RULES:
- Output MUST be valid JSON only (no markdown, no extra text).
- If uncertain, provide ranges and confidence.
- Do NOT produce step-by-step chain-of-thought. Provide only a short rationale focusing on portion assumptions.

TASK:
1) Identify all visible food items.
2) Estimate portion sizes using visual cues (plate, cutlery).
3) Estimate macros for EACH item and sum totals.
4) Classify absorption speed: FAST (mostly refined carbs), SLOW (high fat/protein/fiber), MIXED otherwise.
5) Provide GI category: LOW / MEDIUM / HIGH.

OUTPUT JSON SCHEMA (exact keys):
{
  "food_name": "string",
  "carbs_g": { "estimate": number, "min": number, "max": number },
  "protein_g": { "estimate": number, "min": number, "max": number },
  "fat_g": { "estimate": number, "min": number, "max": number },
  "absorption_speed": "FAST" | "MIXED" | "SLOW",
  "glycemic_index": "LOW" | "MEDIUM" | "HIGH",
  "confidence": "LOW" | "MEDIUM" | "HIGH",
  "rationale": "short text focusing on portion assumptions"
}
"""

    fun cleanJsonResponse(rawJson: String): String {
        return rawJson
            .replace("```json", "")
            .replace("```", "")
            .trim()
            .let { cleaned ->
                if (!cleaned.startsWith("{")) {
                     val start = cleaned.indexOf('{')
                     val end = cleaned.lastIndexOf('}')
                     if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
                } else cleaned
            }
    }

    // --- Robust Parsing Helpers ---
    
    // Explicit FPU Calculation (Warsaw Method)
    private fun computeFpu(fatG: Double, proteinG: Double): Double {
        return (fatG * 9.0 + proteinG * 4.0) / 10.0
    }
    
    // Clamp to valid physiological ranges (0 to 500g)
    private fun clampMacro(x: Double): Double = when {
        x.isNaN() || x.isInfinite() -> 0.0
        x < 0.0 -> 0.0
        x > 500.0 -> 500.0 // sanity check
        else -> x
    }

    // Flexible extraction (handles "45" string or 45 number)
    private fun JSONObject.optDoubleFlexible(key: String, default: Double = 0.0): Double {
        val v = opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull() ?: default
            else -> default
        }
    }

    // Extract {estimate, min, max} object or fallback to simple number
    private fun JSONObject.optRange(key: String): Triple<Double, Double, Double> {
        val obj = optJSONObject(key)
        if (obj != null) {
            val est = clampMacro(obj.optDoubleFlexible("estimate", 0.0))
            val min = clampMacro(obj.optDoubleFlexible("min", est))
            val max = clampMacro(obj.optDoubleFlexible("max", est))
            return Triple(est, min.coerceAtMost(est), max.coerceAtLeast(est))
        }
        // Fallback: simple number
        val est = clampMacro(optDoubleFlexible(key.removeSuffix("_g"), 0.0))
        return Triple(est, est, est)
    }

    fun parseJsonToResult(cleanedJson: String): EstimationResult {
        val root = JSONObject(cleanedJson)
        
        // Extract Macros with Ranges
        val (carbsEst, carbsMin, carbsMax) = root.optRange("carbs_g")
        val (protEst, _, _) = root.optRange("protein_g")
        val (fatEst, _, _) = root.optRange("fat_g")
        
        // Independent Calculation of FPU
        val fpuCalc = computeFpu(fatEst, protEst)

        return EstimationResult(
            description = root.optString("food_name", "Unknown food"),
            carbsGrams = carbsEst,
            carbsMin = carbsMin,
            carbsMax = carbsMax,
            proteinGrams = protEst,
            fatGrams = fatEst,
            fpuEquivalent = fpuCalc, // Source of Truth = Kotlin Calc
            glycemicIndex = root.optString("glycemic_index", "MEDIUM"),
            absorptionSpeed = root.optString("absorption_speed", "MIXED"),
            confidence = root.optString("confidence", "MEDIUM"),
            reasoning = root.optString("rationale", root.optString("reasoning", "No rationale"))
        )
    }
}
