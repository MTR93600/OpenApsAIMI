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
 * Google Gemini 2.5 Flash Vision Provider  
 * Uses gemini-2.5-flash model with enhanced JSON mode
 */
class GeminiVisionProvider : AIVisionProvider {
    override val displayName = "Gemini (2.5 Flash)"
    override val providerId = "GEMINI"
    
    override suspend fun estimateFromImage(bitmap: Bitmap, apiKey: String): EstimationResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)
        val responseJson = callGeminiAPI(apiKey, base64Image)
        return@withContext parseResponse(responseJson)
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    private fun callGeminiAPI(apiKey: String, base64Image: String): String {
        // Gemini 2.5 Flash endpoint
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        
        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", FoodAnalysisPrompt.SYSTEM_PROMPT)
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
                put("maxOutputTokens", 800)
                put("temperature", 0.3)
                put("responseMimeType", "application/json")  // Force JSON output
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
            val content = root.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            
            // Gemini with responseMimeType should return clean JSON, but clean anyway
            val cleanedJson = FoodAnalysisPrompt.cleanJsonResponse(content)
            return FoodAnalysisPrompt.parseJsonToResult(cleanedJson)
        } catch (e: Exception) {
            throw Exception("Gemini response parsing failed: ${e.message}")
        }
    }
}
