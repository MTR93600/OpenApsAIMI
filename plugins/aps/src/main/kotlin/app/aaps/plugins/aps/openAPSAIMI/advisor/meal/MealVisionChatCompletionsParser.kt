package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import org.json.JSONObject

/**
 * Parses OpenAI-compatible `chat/completions` JSON (OpenAI, DeepSeek, …): refusal, empty content,
 * then delegates model text to [MealVisionJsonParser].
 */
object MealVisionChatCompletionsParser {

    fun parseOpenAiStyleResponse(responseJson: String, providerLabel: String): EstimationResult {
        return try {
            val root = JSONObject(responseJson)
            val choices = root.optJSONArray("choices")
                ?: return FoodAnalysisPrompt.emptyErrorResult(
                    "$providerLabel Error",
                    "Missing choices in response",
                )
            if (choices.length() == 0) {
                return FoodAnalysisPrompt.emptyErrorResult(
                    "$providerLabel Error",
                    "Empty choices in response",
                )
            }
            val message = choices.getJSONObject(0).optJSONObject("message")
                ?: return FoodAnalysisPrompt.emptyErrorResult(
                    "$providerLabel Error",
                    "Missing message in response",
                )
            val refusal = message.optString("refusal", "").trim()
            if (refusal.isNotEmpty()) {
                return FoodAnalysisPrompt.emptyErrorResult(
                    "$providerLabel Refusal",
                    refusal,
                )
            }
            val content = message.optString("content", "").trim()
            if (content.isEmpty()) {
                return FoodAnalysisPrompt.emptyErrorResult(
                    "$providerLabel Error",
                    "Empty model response",
                )
            }
            MealVisionJsonParser.parseModelContentToEstimation(content)
        } catch (e: Exception) {
            FoodAnalysisPrompt.emptyErrorResult(
                "$providerLabel Parse",
                e.message ?: "Invalid completions JSON",
            )
        }
    }
}
