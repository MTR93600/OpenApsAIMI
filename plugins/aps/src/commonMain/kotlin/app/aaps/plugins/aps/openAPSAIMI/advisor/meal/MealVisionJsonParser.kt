package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

/**
 * Single entry for turning raw model text into [EstimationResult], with consistent
 * cleanup, JSON parse, and post-parse sanitization (via [FoodAnalysisPrompt.parseJsonToResult]).
 */
object MealVisionJsonParser {

    fun parseModelContentToEstimation(rawContent: String): EstimationResult {
        return try {
            val cleaned = FoodAnalysisPrompt.cleanJsonResponse(rawContent)
            FoodAnalysisPrompt.parseJsonToResult(cleaned)
        } catch (e: Exception) {
            FoodAnalysisPrompt.emptyErrorResult(
                "Parse Error",
                e.message ?: "Invalid or incomplete JSON from vision model",
            )
        }
    }
}
