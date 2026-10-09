package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpRequest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ClaudeVisionProvider(
    private val aimiHttp: AimiHttp
) : AIVisionProvider {
    override val displayName = "Claude (3.5 Sonnet)"
    override val providerId = "CLAUDE"

    override suspend fun estimateFromImage(image: AimiImage, userDescription: String, apiKey: String): EstimationResult =
        withContext(aapsIoDispatcher) {
            try {
                val base64Image = image.base64()
                val responseJson = callClaudeAPI(apiKey, base64Image, userDescription)
                parseResponse(responseJson)
            } catch (e: Exception) {
                FoodAnalysisPrompt.emptyErrorResult("Claude Error", e.message ?: "Unknown error")
            }
        }

    private fun callClaudeAPI(apiKey: String, base64Image: String, userDescription: String): String {
        val userPrompt = MealVisionUserPrompt.buildAnalysisUserPrompt(userDescription)

        val jsonBody = buildJsonObject {
            put("model", "claude-3-5-sonnet-20240620")
            put("max_tokens", 2048)
            put("temperature", 0.0)
            put("system", FoodAnalysisPrompt.SYSTEM_PROMPT)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", userPrompt)
                        })
                    })
                })
            })
        }

        val response = aimiHttp.execute(
            AimiHttpRequest(
                url = "https://api.anthropic.com/v1/messages",
                method = "POST",
                connectTimeoutMs = 30000,
                readTimeoutMs = 45000,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
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

    private fun parseResponse(jsonStr: String): EstimationResult {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).jsonObject
        val content = root["content"]?.jsonArray
            ?.get(0)?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: return FoodAnalysisPrompt.emptyErrorResult("Claude Error", "Missing content text")
        return MealVisionJsonParser.parseModelContentToEstimation(content)
    }
}
