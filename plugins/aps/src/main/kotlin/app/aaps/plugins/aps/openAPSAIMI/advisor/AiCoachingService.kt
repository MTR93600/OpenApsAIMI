package app.aaps.plugins.aps.openAPSAIMI.advisor

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
        // Updated to v1 and gemini-1.5-flash
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1/models/gemini-1.5-flash:generateContent"
        
        private const val OPENAI_MODEL = "gpt-4o"
    }

    /**
     * Fetch advice asynchronously.
     */
    suspend fun fetchAdvice(
        context: AdvisorContext, 
        report: AdvisorReport, 
        apiKey: String,
        provider: Provider
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Clé API manquante. Veuillez configurer votre clé ${provider.name}."

        try {
            val prompt = buildPrompt(context, report)
            
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
        part.put("text", prompt)
        parts.put(part)
        
        val content = JSONObject()
        content.put("parts", parts)
        content.put("role", "user") // or 'user' by default
        
        val contents = JSONArray()
        contents.put(content)
        
        // System instruction (Gemini 1.5 supports system_instruction, checking if json structure allows)
        // Simple prompt approach: put system instruction IN the text if needed, 
        // but 'system_instruction' field exists in 1.5. 
        // Let's stick to simple user prompt concat for robustness.
        
        // Final JSON
        val root = JSONObject()
        root.put("contents", contents)
        
        // Generation Config
        val config = JSONObject()
        config.put("temperature", 0.7)
        config.put("maxOutputTokens", 500)
        root.put("generationConfig", config)

        connection.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
        }

        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(root.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) response.append(line)
            reader.close()
            return parseGeminiResponse(response.toString())
        } else {
            val reader = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream))
            val err = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) err.append(line)
            return "Erreur Gemini ($responseCode): $err"
        }
    }

    private fun buildPrompt(ctx: AdvisorContext, report: AdvisorReport): String {
        val sb = StringBuilder()
        val deviceLang = java.util.Locale.getDefault().displayLanguage
        
        // Persona & Tone
        sb.append("Tu es AIMI, un assistant intelligent et empathique expert en gestion du diabète (OpenAPS/Loop).\n")
        sb.append("Ton but : aider l'utilisateur à comprendre ses résultats ('$deviceLang') et proposer des ajustements concrets.\n")
        sb.append("Ton ton : Encourangeant, professionnel mais accessible, comme un coach personnel.\n\n")

        // 1. Context: Metrics
        sb.append("--- ANALYSE 7 JOURS ---\n")
        sb.append("Score Global: ${report.overallScore}/10\n")
        sb.append("TIR (70-180): ${(ctx.metrics.tir70_180 * 100).roundToInt()}%\n")
        sb.append("Hypo (<70): ${(ctx.metrics.timeBelow70 * 100).roundToInt()}%\n")
        sb.append("Hyper (>180): ${(ctx.metrics.timeAbove180 * 100).roundToInt()}%\n")
        sb.append("Moyenne: ${ctx.metrics.meanBg.roundToInt()} mg/dL\n")
        sb.append("GMI: ${ctx.metrics.gmi}%\n\n")

        // 2. PKPD Context
        if (ctx.pkpdPrefs.pkpdEnabled) {
             sb.append("--- PARAMETRES PKPD (Adaptatif) ---\n")
             sb.append("DIA: ${ctx.pkpdPrefs.initialDiaH}h\n")
             sb.append("Pic: ${ctx.pkpdPrefs.initialPeakMin}min\n")
             sb.append("Fusion ISF Max: x${ctx.pkpdPrefs.isfFusionMaxFactor}\n\n")
        }

        // 3. System Observations
        if (report.pkpdSuggestions.isNotEmpty() || report.recommendations.isNotEmpty()) {
            sb.append("--- SUGGESTIONS DU SYSTEME ---\n")
            report.pkpdSuggestions.forEach { sb.append("- (PKPD) ${it.explanation}\n") }
        }

        // 4. Instructions
        sb.append("\n--- TACHE ---\n")
        sb.append("1. Fais une analyse synthétique : Bravo pour les points positifs, attention aux points négatifs.\n")
        sb.append("2. Si le TIR < 80% ou Hypos > 4%, propose 1 ou 2 actions prioritaires (ex: ajuster Basale, ISF, ou paramètres AIMI).\n")
        sb.append("3. Si les suggestions systèmes ci-dessus te semblent pertinentes, explique-les simplement.\n")
        sb.append("4. Réponds impérativement en '$deviceLang'.\n")
        sb.append("5. Sois court (max 100 mots) et structuré avec des emojis.\n")

        return sb.toString()
    }

    private fun buildOpenAiJson(prompt: String): JSONObject {
        val root = JSONObject()
        root.put("model", OPENAI_MODEL)
        val messages = JSONArray()
        val sys = JSONObject().put("role", "system").put("content", "You are AIMI, a helpful diabetes coach.")
        val usr = JSONObject().put("role", "user").put("content", prompt)
        messages.put(sys).put(usr)
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
