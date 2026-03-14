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

class GeminiVisionProvider(private val context: android.content.Context) : AIVisionProvider {
    override val displayName = "Gemini (3.0 Flash)"
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
        val primaryModel = geminiResolver.resolveGenerateContentModel(apiKey, "gemini-3-flash")
        
        try {
            return executeRequest(apiKey, base64Image, primaryModel, userDescription)
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted")) {
                val fallbackModel = "gemini-1.5-flash-latest"
                return executeRequest(apiKey, base64Image, fallbackModel, userDescription)
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
        
        val userPrompt = if (userDescription.isNotBlank()) {
            "User description: \"$userDescription\". Analyze this meal image and return JSON only according to the required schema."
        } else {
            "Analyze this meal image and return JSON only according to the required schema."
        }

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
        val content = root.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        
        val cleaned = FoodAnalysisPrompt.cleanJsonResponse(content)
        return FoodAnalysisPrompt.parseJsonToResult(cleaned)
    }
}
