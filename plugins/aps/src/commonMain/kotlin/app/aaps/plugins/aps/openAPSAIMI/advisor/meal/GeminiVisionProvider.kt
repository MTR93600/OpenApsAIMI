package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmHttpRetry
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpRequest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class GeminiVisionProvider(
    private val geminiResolver: GeminiModelResolver,
    private val aapsLogger: AAPSLogger,
    private val aimiHttp: AimiHttp
) : AIVisionProvider {
    override val displayName = "Gemini (Flash)"
    override val providerId = "GEMINI"

    override suspend fun estimateFromImage(image: AimiImage, userDescription: String, apiKey: String): EstimationResult =
        withContext(aapsIoDispatcher) {
            try {
                val base64Image = image.base64()
                val responseJson = callGeminiAPI(apiKey, base64Image, userDescription)
                parseResponse(responseJson)
            } catch (e: Exception) {
                FoodAnalysisPrompt.emptyErrorResult("Gemini Error", e.message ?: "Unknown error")
            }
        }

    private fun callGeminiAPI(apiKey: String, base64Image: String, userDescription: String): String {
        // Vision needs a multimodal model; flash tier is multimodal. Durable *-latest alias.
        val primaryModel = geminiResolver.resolveGenerateContentModel(apiKey, "gemini-flash-latest")

        try {
            return LlmHttpRetry.withTransientRetry(aapsLogger) { executeRequest(apiKey, base64Image, primaryModel, userDescription) }
        } catch (e: Exception) {
            if (LlmHttpRetry.isQuota(e) || LlmHttpRetry.isTransient(e)) {
                val fallbackModel = "gemini-flash-latest"
                return LlmHttpRetry.withTransientRetry(aapsLogger) { executeRequest(apiKey, base64Image, fallbackModel, userDescription) }
            }
            throw e
        }
    }

    private fun executeRequest(apiKey: String, base64Image: String, modelId: String, userDescription: String): String {
        val urlStr = geminiResolver.getGenerateContentUrl(modelId, apiKey)
        val userPrompt = MealVisionUserPrompt.buildAnalysisUserPrompt(userDescription)

        val jsonBody = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "${FoodAnalysisPrompt.SYSTEM_PROMPT}\n\n$userPrompt")
                        })
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", 4096)
                put("temperature", 0.0)
                put("responseMimeType", "application/json")
            })
        }

        val response = aimiHttp.execute(
            AimiHttpRequest(
                url = urlStr,
                method = "POST",
                connectTimeoutMs = 30000,
                readTimeoutMs = 60000,
                headers = mapOf("Content-Type" to "application/json"),
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
        val root = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr).let {
            it as? kotlinx.serialization.json.JsonObject
        } ?: return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Invalid JSON response")
        val candidates = root["candidates"] as? kotlinx.serialization.json.JsonArray
            ?: return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Missing candidates in response")
        if (candidates.isEmpty()) {
            return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Empty candidates array")
        }
        val candidate = candidates[0] as? kotlinx.serialization.json.JsonObject
            ?: return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Invalid candidate")
        val finish = (candidate["finishReason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        if (finish.equals("SAFETY", ignoreCase = true) || finish.equals("BLOCKLIST", ignoreCase = true)) {
            return FoodAnalysisPrompt.emptyErrorResult("Gemini Safety", "Response blocked ($finish)")
        }
        val content = candidate["content"] as? kotlinx.serialization.json.JsonObject
            ?: return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Missing content object")
        val parts = content["parts"] as? kotlinx.serialization.json.JsonArray
            ?: return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Missing content parts")
        if (parts.isEmpty()) {
            return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Empty content parts")
        }
        var text = ""
        for (part in parts) {
            val t = ((part as? kotlinx.serialization.json.JsonObject)?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            if (t.isNotBlank()) {
                text = t
                break
            }
        }
        if (text.isBlank()) {
            return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Empty model text")
        }
        return MealVisionJsonParser.parseModelContentToEstimation(text)
    }
}
