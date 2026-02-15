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
 * Google Gemini 3.0 Pro Vision Provider  
 * Uses gemini-3.0-pro-preview model with enhanced JSON mode
 */
class GeminiVisionProvider(private val context: android.content.Context) : AIVisionProvider {
    override val displayName = "Gemini (2.0 Flash)"
    override val providerId = "GEMINI"
    
    private val geminiResolver = app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver(context)

    override suspend fun estimateFromImage(bitmap: Bitmap, userDescription: String, apiKey: String): EstimationResult = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64(bitmap)
        val responseJson = callGeminiAPI(apiKey, base64Image, userDescription)
        return@withContext parseResponse(responseJson)
    }
    
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    private fun callGeminiAPI(apiKey: String, base64Image: String, userDescription: String): String {
        // ... (resolver logic) ...
        val primaryModel = geminiResolver.resolveGenerateContentModel(apiKey, "gemini-2.0-flash-exp")
        
        try {
            return executeRequest(apiKey, base64Image, primaryModel, userDescription)
        } catch (e: Exception) {
            // ... (fallback logic) ...
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted")) {
                val fallbackModel = "gemini-1.5-flash-latest"
                android.util.Log.w("AIMI_GEMINI", "Scale-down Fallback: $fallbackModel")
                return executeRequest(apiKey, base64Image, fallbackModel, userDescription)
            }
            throw e
        }
    }

    private fun executeRequest(apiKey: String, base64Image: String, modelId: String, userDescription: String): String {
        val urlStr = geminiResolver.getGenerateContentUrl(modelId, apiKey)
        // ... (connection setup) ...
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        
        // CRITICAL FIX #1: Increase timeout to prevent premature connection closure
        connection.connectTimeout = 30000  // 30 seconds
        connection.readTimeout = 60000     // 60 seconds (Increased for detailed analysis)
        
        // Construct detailed system instructions + user description
        val promptText = if (userDescription.isNotBlank()) {
            "${FoodAnalysisPrompt.SYSTEM_PROMPT}\n\nUSER DESCRIPTION: \"$userDescription\""
        } else {
            FoodAnalysisPrompt.SYSTEM_PROMPT
        }

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", promptText)
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
                // CRITICAL FIX #2: Increase token limit from 2048 to 8192 to prevent JSON truncation
                put("maxOutputTokens", 8192)
                put("temperature", 0.3)
                put("responseMimeType", "application/json")  // Force JSON output
            })
        }
        
        connection.outputStream.use { it.write(jsonBody.toString().toByteArray()) }
        
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            // CRITICAL FIX #3: Robust stream reading with buffer size control
            val response = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)  // 8KB chunks
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    response.append(buffer, 0, charsRead)
                }
            }
            return response.toString()
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
            
            // CRITICAL FIX #4: Validate JSON completion before parsing
            // Check if response is truncated (unterminated string/object)
            if (!isValidJsonStructure(content)) {
                throw Exception("Response truncated - JSON incomplete. Try again or check API quota.")
            }
            
            // Gemini with responseMimeType should return clean JSON, but clean anyway
            val cleanedJson = FoodAnalysisPrompt.cleanJsonResponse(content)
            return FoodAnalysisPrompt.parseJsonToResult(cleanedJson)
        } catch (e: org.json.JSONException) {
            // Provide more specific error for JSON parsing failures
            throw Exception("Gemini response parsing failed: ${e.message}. Response may be truncated - increase maxOutputTokens if issue persists.")
        } catch (e: Exception) {
            throw Exception("Gemini response parsing failed: ${e.message}")
        }
    }
    
    /**
     * Validate JSON structure is complete (not truncated)
     * Checks for balanced braces and proper string termination
     */
    private fun isValidJsonStructure(json: String): Boolean {
        var braceCount = 0
        var inString = false
        var escaped = false
        
        for (i in json.indices) {
            val char = json[i]
            
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = !inString
                !inString && char == '{' -> braceCount++
                !inString && char == '}' -> braceCount--
            }
        }
        
        // Valid JSON: balanced braces, no unterminated string
        return braceCount == 0 && !inString
    }
}
