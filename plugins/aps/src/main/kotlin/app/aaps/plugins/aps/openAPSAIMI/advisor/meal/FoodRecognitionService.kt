
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
    // ...
    // ...
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
    // ...
    // ...
    private fun callGemini(apiKey: String, base64Image: String): String {
        // ...
                         put(JSONObject().apply {
                             put("text", "You are an expert T1D nutritionist. Analyze the food image. Provide:\n" +
                                     "1. Name\n" +
                                     "2. Carbohydrates (g)\n" +
                                     "3. Protein (g)\n" +
                                     "4. Fat (g)\n" +
                                     "5. FPU Equivalent (g): Estimate equivalent carbs from protein/fat (Warsaw method: (Fat*9 + Protein*4) kcal / 10).\n" +
                                     "Output JSON ONLY: { \"food_name\": string, \"carbs\": number, \"protein\": number, \"fat\": number, \"fpu\": number, \"reasoning\": string }. Be concise.")
                         })
        // ...
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
    
    // ...

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
}
