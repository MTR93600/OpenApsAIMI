package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmHttpRetry

class GeminiVisionProvider(private val context: android.content.Context) : AIVisionProvider {
    override val displayName = "Gemini (Flash)"
    override val providerId = "GEMINI"
    
    private val geminiResolver = app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver(context)

    override suspend fun estimateFromImage(bitmap: Bitmap, userDescription: String, apiKey: String): EstimationResult = withContext(Dispatchers.IO) {
        try {
            val base64Image = bitmapToBase64(bitmap)
            val responseJson = callGeminiAPI(apiKey, base64Image, userDescription)
            return@withContext parseResponse(responseJson)
        } catch (e: Exception) {
            return@withContext FoodAnalysisPrompt.emptyErrorResult("Gemini Error", e.message ?: "Unknown error")
        }
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
    
    private fun callGeminiAPI(apiKey: String, base64Image: String, userDescription: String): String {
        // Vision needs a multimodal model; flash tier is multimodal. Durable *-latest alias.
        val primaryModel = geminiResolver.resolveGenerateContentModel(apiKey, "gemini-flash-latest")
        
        try {
            return LlmHttpRetry.withTransientRetry { executeRequest(apiKey, base64Image, primaryModel, userDescription) }
        } catch (e: Exception) {
            if (LlmHttpRetry.isQuota(e) || LlmHttpRetry.isTransient(e)) {
                val fallbackModel = "gemini-flash-latest"
                return LlmHttpRetry.withTransientRetry { executeRequest(apiKey, base64Image, fallbackModel, userDescription) }
            }
            throw e
        }
    }

    private fun executeRequest(apiKey: String, base64Image: String, modelId: String, userDescription: String): String {
        val urlStr = geminiResolver.getGenerateContentUrl(modelId, apiKey)
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        
        val userPrompt = MealVisionUserPrompt.buildAnalysisUserPrompt(userDescription)

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", "${FoodAnalysisPrompt.SYSTEM_PROMPT}\n\n$userPrompt")
                        })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 4096)
                put("temperature", 0.0)
                put("responseMimeType", "application/json")
            })
        }
        
        connection.outputStream.use { it.write(jsonBody.toString().toByteArray()) }
        
        val code = connection.responseCode
        if (code == HttpURLConnection.HTTP_OK) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Empty error"
            throw Exception("HTTP $code: $err")
        }
    }
    
    private fun parseResponse(jsonStr: String): EstimationResult {
        val root = JSONObject(jsonStr)
        if (!root.has("candidates")) {
            return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Missing candidates in response")
        }
        val candidates = root.getJSONArray("candidates")
        if (candidates.length() == 0) {
            return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Empty candidates array")
        }
        val candidate = candidates.getJSONObject(0)
        val finish = candidate.optString("finishReason", "")
        if (finish.equals("SAFETY", ignoreCase = true) || finish.equals("BLOCKLIST", ignoreCase = true)) {
            return FoodAnalysisPrompt.emptyErrorResult("Gemini Safety", "Response blocked ($finish)")
        }
        val content = candidate.optJSONObject("content")
            ?: return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Missing content object")
        val parts = content.optJSONArray("parts")
            ?: return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Missing content parts")
        if (parts.length() == 0) {
            return FoodAnalysisPrompt.emptyErrorResult("Gemini Error", "Empty content parts")
        }
        var text = ""
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val t = part.optString("text", "")
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
