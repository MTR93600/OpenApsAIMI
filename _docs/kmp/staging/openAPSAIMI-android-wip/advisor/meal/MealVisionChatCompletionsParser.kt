package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import app.aaps.core.data.json.OrgJsonCompat.optJsonArrayCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat

/**
 * Parses OpenAI-compatible `chat/completions` JSON (OpenAI, DeepSeek, …): refusal, empty content,
 * then delegates model text to [MealVisionJsonParser].
 */
object MealVisionChatCompletionsParser {

    fun parseOpenAiStyleResponse(responseJson: String, providerLabel: String): EstimationResult {
        return try {
            val root = Json.parseToJsonElement(responseJson).jsonObject
            val choices = root.optJsonArrayCompat("choices")
                ?: return FoodAnalysisPrompt.emptyErrorResult(
                    "$providerLabel Error",
                    "Missing choices in response",
                )
            if (choices.size == 0) {
                return FoodAnalysisPrompt.emptyErrorResult(
                    "$providerLabel Error",
                    "Empty choices in response",
                )
            }
            val message = choices[0].jsonObject.optJsonObjectCompat("message")
                ?: return FoodAnalysisPrompt.emptyErrorResult(
                    "$providerLabel Error",
                    "Missing message in response",
                )
            val refusal = message.optStringCompat("refusal").trim()
            if (refusal.isNotEmpty()) {
                return FoodAnalysisPrompt.emptyErrorResult(
                    "$providerLabel Refusal",
                    refusal,
                )
            }
            val content = message.optStringCompat("content").trim()
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
