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
data class EstimationResult(
    val description: String,
    val carbsGrams: Double,
    val proteinGrams: Double,
    val fatGrams: Double,
    val fpuEquivalent: Double,
    val reasoning: String
)

/**
 * Common prompt for all providers (T1D nutritionist expert)
 */
object FoodAnalysisPrompt {
    const val SYSTEM_PROMPT = """You are an expert T1D nutritionist. Analyze the food image and provide:
1. Food name
2. Carbohydrates (g)
3. Protein (g)
4. Fat (g)
5. FPU Equivalent (g): Estimate equivalent carbs from protein/fat using Warsaw method: (Fat×9 + Protein×4) kcal / 10

Output ONLY valid JSON in this exact format:
{
  "food_name": "string",
  "carbs": number,
  "protein": number,
  "fat": number,
  "fpu": number,
  "reasoning": "string"
}

Be concise. Use realistic portion estimates. Do NOT include markdown code blocks."""

    /**
     * Clean JSON response (remove markdown, extra whitespace, fix common issues)
     */
    fun cleanJsonResponse(rawJson: String): String {
        return rawJson
            .replace("```json", "")
            .replace("```", "")
            .replace("\n", " ")
            .replace("\r", " ")
            .trim()
            .let { cleaned ->
                // Fix common JSON issues
                if (!cleaned.startsWith("{")) {
                    // Find first { and last }
                    val start = cleaned.indexOf('{')
                    val end = cleaned.lastIndexOf('}')
                    if (start >= 0 && end > start) {
                        cleaned.substring(start, end + 1)
                    } else {
                        cleaned
                    }
                } else {
                    cleaned
                }
            }
    }
    
    /**
     * Parse cleaned JSON to EstimationResult
     */
    fun parseJsonToResult(cleanedJson: String): EstimationResult {
        val result = JSONObject(cleanedJson)
        
        return EstimationResult(
            description = result.optString("food_name", "Unknown food"),
            carbsGrams = result.optDouble("carbs", 0.0),
            proteinGrams = result.optDouble("protein", 0.0),
            fatGrams = result.optDouble("fat", 0.0),
            fpuEquivalent = result.optDouble("fpu", 0.0),
            reasoning = result.optString("reasoning", "No reasoning provided")
        )
    }
}
