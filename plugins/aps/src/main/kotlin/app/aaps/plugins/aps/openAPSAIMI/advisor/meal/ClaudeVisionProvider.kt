package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import app.aaps.plugins.aps.openAPSAIMI.llm.claude.ClaudeModelResolver
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class ClaudeVisionProvider : AIVisionProvider {
    override val displayName = "Claude (model chosen in settings)"
    override val providerId = "CLAUDE"
    
    override suspend fun estimateFromImage(bitmap: Bitmap, userDescription: String, apiKey: String): EstimationResult = withContext(Dispatchers.IO) {
        try {
            val base64Image = bitmapToBase64(bitmap)
            val responseJson = callClaudeAPI(apiKey, base64Image, userDescription)
            return@withContext parseResponse(responseJson)
        } catch (e: Exception) {
            return@withContext FoodAnalysisPrompt.emptyErrorResult("Claude Error", e.message ?: "Unknown error")
        }
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
    
    private fun callClaudeAPI(apiKey: String, base64Image: String, userDescription: String): String {
        val url = URL("https://api.anthropic.com/v1/messages")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-api-key", apiKey)
        connection.setRequestProperty("anthropic-version", "2023-06-01")
        connection.doOutput = true
        connection.connectTimeout = 30000
        connection.readTimeout = 90000
        
        val userPrompt = MealVisionUserPrompt.buildAnalysisUserPrompt(userDescription)

        val jsonBody = JSONObject().apply {
            put("model", ClaudeModelResolver.current())
            put("max_tokens", 8192) // thinking tokens count in this limit on newer Claude models
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
                            put("text", userPrompt)
                        })
                    })
                })
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
        val content = ClaudeModelResolver.extractText(root)
        return MealVisionJsonParser.parseModelContentToEstimation(content)
    }
}
