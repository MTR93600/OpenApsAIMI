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

class DeepSeekVisionProvider : AIVisionProvider {
    override val displayName = "DeepSeek (Chat)"
    override val providerId = "DEEPSEEK"
    
    override suspend fun estimateFromImage(bitmap: Bitmap, userDescription: String, apiKey: String): EstimationResult = withContext(Dispatchers.IO) {
        try {
            val base64Image = bitmapToBase64(bitmap)
            val responseJson = callDeepSeekAPI(apiKey, base64Image, userDescription)
            return@withContext parseResponse(responseJson)
        } catch (e: Exception) {
            return@withContext FoodAnalysisPrompt.emptyErrorResult("DeepSeek Error", e.message ?: "Unknown error")
        }
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
    
    private fun callDeepSeekAPI(apiKey: String, base64Image: String, userDescription: String): String {
        val url = URL("https://api.deepseek.com/v1/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 45000

        val userPrompt = if (userDescription.isNotBlank()) {
            "User description: \"$userDescription\". Analyze this meal image and return JSON only according to the required schema."
        } else {
            "Analyze this meal image and return JSON only according to the required schema."
        }
        
        val jsonBody = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", FoodAnalysisPrompt.SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", userPrompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
            put("max_tokens", 2048)
            put("temperature", 0.0)
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
        val content = root.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        
        val cleaned = FoodAnalysisPrompt.cleanJsonResponse(content)
        return FoodAnalysisPrompt.parseJsonToResult(cleaned)
    }
}
