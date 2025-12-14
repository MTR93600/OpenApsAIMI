package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class FoodRecognitionService(private val context: Context, private val preferences: Preferences) {

    data class EstimationResult(
        val description: String,
        val carbsGrams: Double,
        val proteinGrams: Double,
        val fatGrams: Double,
        val fpuEquivalent: Double,
        val reasoning: String
    )

    data class VisionApiConfig(
        val modelName: String = "gpt-4o",
        val maxTokens: Int = 1000
    )

    suspend fun estimateCarbsFromImage(bitmap: Bitmap, config: VisionApiConfig = VisionApiConfig()): EstimationResult = withContext(Dispatchers.IO) {
        val provider = preferences.get(StringKey.AimiAdvisorProvider) // "OPENAI" or "GEMINI"
        
        if (provider == "GEMINI") {
            return@withContext estimateWithGemini(bitmap, config)
        } else {
            return@withContext estimateWithOpenAI(bitmap, config)
        }
    }

    private suspend fun estimateWithOpenAI(bitmap: Bitmap, config: VisionApiConfig): EstimationResult {
        val apiKey = preferences.get(StringKey.AimiAdvisorOpenAIKey)
        if (apiKey.isBlank()) return mockFallback("OpenAI Key missing")

        try {
            val base64Image = bitmapToBase64(bitmap)
            val responseJson = callOpenAI(apiKey, base64Image)
            return parseOpenAIResponse(responseJson)
        } catch (e: Exception) {
            return errorResult("OpenAI Error: ${e.message}")
        }
    }

    private suspend fun estimateWithGemini(bitmap: Bitmap, config: VisionApiConfig): EstimationResult {
        val apiKey = preferences.get(StringKey.AimiAdvisorGeminiKey)
        if (apiKey.isBlank()) return mockFallback("Gemini Key missing")

        try {
            val base64Image = bitmapToBase64(bitmap)
            val responseJson = callGemini(apiKey, base64Image)
            return parseGeminiResponse(responseJson)
        } catch (e: Exception) {
            return errorResult("Gemini Error: ${e.message}")
        }
    }

    private fun mockFallback(reason: String): EstimationResult {
        return EstimationResult(
            description = "Simulation Mode ($reason)",
            carbsGrams = 0.0,
            proteinGrams = 0.0,
            fatGrams = 0.0,
            fpuEquivalent = 0.0,
            reasoning = "Please configure keys in AIMI Preferences."
        )
    }

    private fun errorResult(msg: String): EstimationResult {
        return EstimationResult(description = "Error", carbsGrams = 0.0, proteinGrams = 0.0, fatGrams = 0.0, fpuEquivalent = 0.0, reasoning = msg)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    private fun callOpenAI(apiKey: String, base64Image: String): String {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.doOutput = true

        val jsonBody = JSONObject().apply {
            put("model", "gpt-4o")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert T1D nutritionist. Analyze the food image. Provide:\n" +
                            "1. Name\n" +
                            "2. Carbohydrates (g)\n" +
                            "3. Protein (g)\n" +
                            "4. Fat (g)\n" +
                            "5. FPU Equivalent (g): Estimate equivalent carbs from protein/fat (Warsaw method: (Fat*9 + Protein*4) kcal / 10).\n" +
                            "Output JSON ONLY: { \"food_name\": string, \"carbs\": number, \"protein\": number, \"fat\": number, \"fpu\": number, \"reasoning\": string }. Be concise.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", "Estimate macros and equivalent carbs.")
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
            put("max_tokens", 500)
        }

        connection.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw Exception("HTTP $responseCode: ${connection.errorStream.bufferedReader().use { it.readText() }}")
        }
    }
    
    // Gemini 2.5 Flash (Vision capable) - Updated Dec 2025
    private fun callGemini(apiKey: String, base64Image: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        val jsonBody = JSONObject().apply {
             put("contents", org.json.JSONArray().apply {
                 put(JSONObject().apply {
                     put("parts", org.json.JSONArray().apply {
                         put(JSONObject().apply {
                             put("text", "You are an expert T1D nutritionist. Analyze the food image. Provide:\n" +
                                     "1. Name\n" +
                                     "2. Carbohydrates (g)\n" +
                                     "3. Protein (g)\n" +
                                     "4. Fat (g)\n" +
                                     "5. FPU Equivalent (g): Estimate equivalent carbs from protein/fat (Warsaw method: (Fat*9 + Protein*4) kcal / 10).\n" +
                                     "Output JSON ONLY: { \"food_name\": string, \"carbs\": number, \"protein\": number, \"fat\": number, \"fpu\": number, \"reasoning\": string }. Be concise.")
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
                 put("maxOutputTokens", 500)
                 put("responseMimeType", "application/json") // Gemini 1.5 supports JSON mode
             })
        }

        connection.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            throw Exception("HTTP $responseCode: ${connection.errorStream.bufferedReader().use { it.readText() }}")
        }
    }

    private fun parseOpenAIResponse(jsonStr: String): EstimationResult {
        val root = JSONObject(jsonStr)
        val content = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        return parseStartContent(content)
    }

    private fun parseGeminiResponse(jsonStr: String): EstimationResult {
        val root = JSONObject(jsonStr)
        val content = root.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        return parseStartContent(content)
    }

    private fun parseStartContent(content: String): EstimationResult {
        val cleanedJson = content.replace("```json", "").replace("```", "").trim()
        val result = JSONObject(cleanedJson)

        return EstimationResult(
            description = result.getString("food_name"),
            carbsGrams = result.optDouble("carbs", 0.0),
            proteinGrams = result.optDouble("protein", 0.0),
            fatGrams = result.optDouble("fat", 0.0),
            fpuEquivalent = result.optDouble("fpu", 0.0),
            reasoning = result.getString("reasoning")
        )
    }
}
