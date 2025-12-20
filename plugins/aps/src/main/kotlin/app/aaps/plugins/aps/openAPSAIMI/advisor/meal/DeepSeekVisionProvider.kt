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

/**
 * DeepSeek Chat Vision Provider
 * Uses deepseek-chat model (OpenAI-compatible API)
 */
class DeepSeekVisionProvider : AIVisionProvider {
    override val displayName = "DeepSeek (Chat)"
    override val providerId = "DEEPSEEK"
    
    override suspend fun estimateFromImage(bitmap: Bitmap, apiKey: String): EstimationResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)
        val responseJson = callDeepSeekAPI(apiKey, base64Image)
        return@withContext parseResponse(responseJson)
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    private fun callDeepSeekAPI(apiKey: String, base64Image: String): String {
        val url = URL("https://api.deepseek.com/v1/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true
        
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
                            put("text", "Estimate macros and FPU for this food.")
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
            put("max_tokens", 800)
            put("temperature", 0.3)
        }
        
        connection.outputStream.use { it.write(jsonBody.toString().toByteArray()) }
        
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            throw Exception("HTTP $responseCode: $errorBody")
        }
    }
    
    private fun parseResponse(jsonStr: String): EstimationResult {
        try {
            // DeepSeek uses OpenAI-compatible format
            val root = JSONObject(jsonStr)
            val content = root.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            
            val cleanedJson = FoodAnalysisPrompt.cleanJsonResponse(content)
            return FoodAnalysisPrompt.parseJsonToResult(cleanedJson)
        } catch (e: Exception) {
            throw Exception("DeepSeek response parsing failed: ${e.message}")
        }
    }
}
