package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpRequest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class OpenAIVisionProvider(
    private val aimiHttp: AimiHttp
) : AIVisionProvider {
    override val displayName = "OpenAI (GPT-4o Vision)"
    override val providerId = "OPENAI"

    override suspend fun estimateFromImage(image: AimiImage, userDescription: String, apiKey: String): EstimationResult =
        withContext(aapsIoDispatcher) {
            try {
                val base64Image = image.base64()
                val responseJson = callOpenAIAPI(apiKey, base64Image, userDescription)
                MealVisionChatCompletionsParser.parseOpenAiStyleResponse(responseJson, "OpenAI")
            } catch (e: Exception) {
                FoodAnalysisPrompt.emptyErrorResult("OpenAI Error", e.message ?: "Unknown error")
            }
        }

    private fun callOpenAIAPI(apiKey: String, base64Image: String, userDescription: String): String {
        val userPrompt = MealVisionUserPrompt.buildAnalysisUserPrompt(userDescription)

        val jsonBody = buildJsonObject {
            put("model", "gpt-4o")
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", FoodAnalysisPrompt.SYSTEM_PROMPT)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", userPrompt)
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 2048)
            put("temperature", 0.0) // Deterministic for nutrition
        }

        val response = aimiHttp.execute(
            AimiHttpRequest(
                url = "https://api.openai.com/v1/chat/completions",
                method = "POST",
                connectTimeoutMs = 30000,
                readTimeoutMs = 45000,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $apiKey"
                ),
                body = jsonBody.toString()
            )
        )
        if (response.code == 200) {
            return response.body ?: throw Exception("Empty response body")
        } else {
            throw Exception("HTTP ${response.code}: ${response.body ?: "Empty error"}")
        }
    }
}
