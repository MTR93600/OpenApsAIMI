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
     * @param userDescription Optional text description from user
     * @param apiKey The API key for this provider
     * @return EstimationResult with food data
     * @throws Exception on API errors
     */
    suspend fun estimateFromImage(bitmap: Bitmap, userDescription: String, apiKey: String): EstimationResult
    
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
You are **Diaby**, AIMI's Advanced Vision Nutritionist and diabetic carb-counting expert.
Your goal is to provide **precise, safety-focused** macronutrient estimation from food images to guide insulin dosing.

## IDENTITY & METHODOLOGY
- **Persona**: Clinical, precise, and safety-conscious.
- **Method**: 
  1. **Identify**: Detect all visible food items.
  2. **Volumetrics**: Estimate volume based on visual cues (plate size, cutlery reference, depth).
  3. **Density**: Convert volume to mass (g) using food density knowledge.
  4. **Macros**: Calculate Carbs, Protein, Fat using standard nutritional databases.
  5. **Glycemic Impact**: Assess GI and absorption speed (fiber/fat content).

## CRITICAL RULES
1. **Safety First**: If uncertain (e.g., sauce content, hidden ingredients), provide a **Range** (min/max) and lean towards safety in the 'estimate'.
2. **Hidden Sugars**: Flag sauces, glazes, or dressings that usually contain hidden carbs.
3. **Chain of Thought**: You MUST reason step-by-step in the `rationale` field before finalizing numbers.
   - *Example:* "Burger bun appears to be brioche (higher fat/sugar). Patty size approx 150g raw weight..."
4. **JSON Only**: Output strict JSON. No markdown fencing if possible, no preamble.

## OUTPUT JSON SCHEMA (Strict)
{
  "food_name": "Short descriptive title (e.g. 'Grilled Salmon with Quinoa')",
  "carbs_g": { "estimate": number, "min": number, "max": number },
  "protein_g": { "estimate": number, "min": number, "max": number },
  "fat_g": { "estimate": number, "min": number, "max": number },
  "absorption_speed": "FAST" | "MIXED" | "SLOW",
  "glycemic_index": "LOW" | "MEDIUM" | "HIGH",
  "confidence": "LOW" | "MEDIUM" | "HIGH",
  "rationale": "STEP-BY-STEP REASONING: 1. Item identification... 2. Volumetric estimation... 3. Macro calculation..."
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
