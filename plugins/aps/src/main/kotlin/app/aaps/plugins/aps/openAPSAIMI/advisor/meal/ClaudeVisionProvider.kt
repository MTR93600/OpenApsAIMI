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
 * Anthropic Claude 3.5 Sonnet Vision Provider
 * Uses Claude's Messages API with vision capabilities
 */
class ClaudeVisionProvider : AIVisionProvider {
    override val displayName = "Claude (3.5 Sonnet)"
    override val providerId = "CLAUDE"
    
    override suspend fun estimateFromImage(bitmap: Bitmap, apiKey: String): EstimationResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)
        val responseJson = callClaudeAPI(apiKey, base64Image)
        return@withContext parseResponse(responseJson)
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    private fun callClaudeAPI(apiKey: String, base64Image: String): String {
        val url = URL("https://api.anthropic.com/v1/messages")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.doOutput = true
        
        val jsonBody = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")  // Latest Claude 3.5 Sonnet
            put("max_tokens", 800)
            put("temperature", 0.3)
            put("system", FoodAnalysisPrompt.SYSTEM_PROMPT)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image")
                            put("source", JSONObject().apply {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Estimate macros and FPU for this food.")
                        })
                    })
                })
            })
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
            val root = JSONObject(jsonStr)
            val content = root.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
            
            val cleanedJson = FoodAnalysisPrompt.cleanJsonResponse(content)
            return FoodAnalysisPrompt.parseJsonToResult(cleanedJson)
        } catch (e: Exception) {
            throw Exception("Claude response parsing failed: ${e.message}")
        }
    }
}
