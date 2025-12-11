package app.aaps.plugins.aps.openAPSAIMI.advisor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * =============================================================================
 * AI COACHING SERVICE
 * =============================================================================
 * 
 * Interacts with OpenAI API to generate natural language coaching advice.
 * Uses robust HttpURLConnection (zero dependency).
 * =============================================================================
 */
class AiCoachingService {

    enum class Provider { OPENAI, GEMINI }

    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        private const val OPENAI_MODEL = "gpt-4o"
        
        // Gemini 2.5 Flash (Dec 2025 standard)
        private const val GEMINI_MODEL = "gemini-2.5-flash"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
    }

    /**
     * Fetch advice asynchronously.
     */
    suspend fun fetchAdvice(
        androidContext: Context,
        context: AdvisorContext, 
        report: AdvisorReport, 
        apiKey: String,
        provider: Provider
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Clé API manquante. Veuillez configurer votre clé ${provider.name}."

        try {
            val prompt = buildPrompt(androidContext, context, report)
            
            if (provider == Provider.GEMINI) {
                return@withContext callGemini(apiKey, prompt)
            } else {
                return@withContext callOpenAI(apiKey, prompt)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "Erreur de connexion (${provider.name}) : ${e.localizedMessage}"
        }
    }

    private fun callOpenAI(apiKey: String, prompt: String): String {
        val jsonBody = buildOpenAiJson(prompt)
        val url = URL(OPENAI_URL)
        val connection = url.openConnection() as HttpURLConnection
        
        connection.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
        }

        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(jsonBody.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) response.append(line)
            reader.close()
            return parseOpenAiResponse(response.toString())
        } else {
             // Try read error stream
            val reader = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream))
            val err = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) err.append(line)
            return "Erreur OpenAI ($responseCode): $err"
        }
    }

    private fun callGemini(apiKey: String, prompt: String): String {
        // Gemini URL requires key in query param usually, or header 'x-goog-api-key'
        val urlStr = "$GEMINI_URL?key=$apiKey"
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        
        val jsonBody = JSONObject()
        val parts = JSONArray()
        val part = JSONObject()
        // STRICT PARITY: Sending exact same prompt as OpenAI (which includes Persona)
        part.put("text", prompt)
        parts.put(part)
        
        val content = JSONObject()
        content.put("parts", parts)
        content.put("role", "user")
        
        val contents = JSONArray()
        contents.put(content)
        
        val root = JSONObject()
        root.put("contents", contents)
        
        // Generation Config
        val config = JSONObject()
        config.put("temperature", 0.7)
        config.put("maxOutputTokens", 4096) // Significantly increased to prevent ANY truncation
        root.put("generationConfig", config)

        connection.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000 // Increased read timeout
        }

        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(root.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream, java.nio.charset.StandardCharsets.UTF_8))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) response.append(line)
            reader.close()
            return parseGeminiResponse(response.toString())
        } else {
            val reader = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream, java.nio.charset.StandardCharsets.UTF_8))
            val err = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) err.append(line)
            return "Erreur Gemini ($responseCode): $err"
        }
    }

    private fun buildPrompt(androidContext: Context, ctx: AdvisorContext, report: AdvisorReport): String {
        val sb = StringBuilder()
        val deviceLang = java.util.Locale.getDefault().displayLanguage
        
        // Persona
        sb.append("You are AIMI, an expert 'Certified Diabetes Educator' specializing in Automated Insulin Delivery (AID).\n")
        sb.append("Your Goal: Analyze the patient's 7-day glucose & insulin data to identify patterns and suggest specific algorithm tuning.\n")
        sb.append("Tone: Professional, encouraging, precise, and safety-first.\n\n")

        // 1. Context: Metrics
        sb.append("--- PATIENT METRICS (7 Days) ---\n")
        sb.append("Score: ${report.overallScore}/10 | GMI: ${ctx.metrics.gmi}%\n")
        sb.append("TIR (70-180): ${(ctx.metrics.tir70_180 * 100).roundToInt()}%\n")
        sb.append("Hypo (<70): ${(ctx.metrics.timeBelow70 * 100).roundToInt()}% | Severe (<54): ${(ctx.metrics.timeBelow54 * 100).roundToInt()}%\n")
        sb.append("Hyper (>180): ${(ctx.metrics.timeAbove180 * 100).roundToInt()}%\n")
        sb.append("Mean Glucose: ${ctx.metrics.meanBg.roundToInt()} mg/dL\n")
        sb.append("Total Daily Dose (TDD): ${ctx.metrics.tdd.roundToInt()} U\n")
        sb.append("Basal/Bolus Split: ${(ctx.metrics.basalPercent * 100).roundToInt()}% Basal | ${(100 - (ctx.metrics.basalPercent * 100).roundToInt())}% Bolus\n\n")

        // 1.5 Context: Active Profile & Preferences
        sb.append("--- ACTIVE PROFILE & SETTINGS ---\n")
        sb.append("Max SMB: ${ctx.prefs.maxSmb} U\n")
        sb.append("ISF: ${ctx.profile.isf} mg/dL/U\n")
        sb.append("IC Ratio: ${ctx.profile.icRatio} g/U\n")
        sb.append("Basal (Night): ${ctx.profile.nightBasal} U/h\n")
        sb.append("Target BG: ${ctx.profile.targetBg} mg/dL\n\n")

        // 2. PKPD Context
        if (ctx.pkpdPrefs.pkpdEnabled) {
             sb.append("--- PARAMETRES PKPD (Adaptatif) ---\n")
             sb.append("DIA: ${ctx.pkpdPrefs.initialDiaH}h\n")
             sb.append("Peak Time: ${ctx.pkpdPrefs.initialPeakMin}min\n")
             sb.append("IsfFusionMax: x${ctx.pkpdPrefs.isfFusionMaxFactor}\n\n")
        }

        // 3. System Observations (Recommendations + PKPD)
        sb.append("--- SYSTEM OBSERVATIONS ---\n")
        if (report.recommendations.isNotEmpty()) {
            report.recommendations.forEach { 
                val title = try { androidContext.getString(it.titleResId) } catch(e:Exception) { "Issue" }
                sb.append("- [Priority ${it.priority}] $title\n") 
            }
        }
        if (report.pkpdSuggestions.isNotEmpty()) {
            report.pkpdSuggestions.forEach { sb.append("- [Software Suggestion] ${it.explanation}\n") }
        }
        if (report.recommendations.isEmpty() && report.pkpdSuggestions.isEmpty()) {
            sb.append("- No specific algorithmic issues detected.\n")
        }
        sb.append("\n")

        // 4. Instructions
        sb.append("--- COACHING TASK ---\n")
        sb.append("Respond in '$deviceLang'. Structure your answer exactly as follows:\n")
        sb.append("1. 🔍 **Diagnostics**: Summarize the main glycemic patterns (e.g., 'Post-prandial spikes', 'Nighttime hypos', 'Basal heavy').\n")
        sb.append("2. 📉 **Root Cause**: Hypothesize the 'Why' (e.g., 'DIA too short', 'ISF too aggressive', 'Carb ratio needs checking').\n")
        sb.append("3. 🛠️ **Action Plan**: Propose 2-3 concrete, actionable steps. If Hypo > 4%, prioritize safety (reduce aggressiveness). If suggestions above exist, evaluate them.\n")
        sb.append("\nConstraint: Keep it under 150 words. Use emojis.")

        return sb.toString()
    }

    private fun buildOpenAiJson(prompt: String): JSONObject {
        val root = JSONObject()
        root.put("model", OPENAI_MODEL)
        val messages = JSONArray()
        // Unified: Prompt contains the full persona and instructions.
        val usr = JSONObject().put("role", "user").put("content", prompt)
        messages.put(usr)
        root.put("messages", messages)
        root.put("temperature", 0.7)
        
        return root
    }

    private fun parseOpenAiResponse(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        } catch (e: Exception) {
            "Erreur lecture OpenAI."
        }
    }

    private fun parseGeminiResponse(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val candidate = root.getJSONArray("candidates").getJSONObject(0)
            val parts = candidate.getJSONObject("content").getJSONArray("parts")
            parts.getJSONObject(0).getString("text").trim()
        } catch (e: Exception) {
             // Fallback for safety blocked
             if (jsonStr.contains("finishReason")) "Contenu bloqué par sécurité Gemini." else "Erreur lecture Gemini."
        }
    }
}
