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

    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-4o" // or gpt-3.5-turbo
    }

    /**
     * Fetch advice asynchronously.
     * @param context The advisor context (metrics, profile, etc.)
     * @param report The generated rules-based report (with actions)
     * @param apiKey The user's OpenAI API Key
     */
    suspend fun fetchAdvice(
        context: AdvisorContext, 
        report: AdvisorReport, 
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "API Key manquante. Veuillez configurer votre clé OpenAI."

        try {
            val prompt = buildPrompt(context, report)
            val jsonBody = buildJsonBody(prompt)
            
            val url = URL(OPENAI_URL)
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                doOutput = true
                connectTimeout = 15000 // 15s
                readTimeout = 30000    // 30s
            }

            // Send request
            val writer = OutputStreamWriter(connection.outputStream)
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()

            // Read response
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                return@withContext parseResponse(response.toString())
            } else {
                return@withContext "Erreur API ($responseCode). Veuillez vérifier votre clé ou votre connexion."
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "Erreur de connexion : ${e.localizedMessage}"
        }
    }

    /**
     * Construct the prompt for the LLM.
     */
    private fun buildPrompt(ctx: AdvisorContext, report: AdvisorReport): String {
        // Summarize metrics
        val summary = StringBuilder()
        summary.append("Données patient (7 jours) :\n")
        summary.append("- TIR 70-180: ${(ctx.metrics.tir70_180 * 100).roundToInt()}%\n")
        summary.append("- Hypo (<70): ${(ctx.metrics.timeBelow70 * 100).roundToInt()}%\n")
        summary.append("- Hyper (>180): ${(ctx.metrics.timeAbove180 * 100).roundToInt()}%\n")
        summary.append("- Score global: ${report.overallScore}/10 (${report.overallAssessment})\n\n")

        // Summarize actions proposed by the Rules Engine
        summary.append("Actions techniques proposées par le système :\n")
        if (report.recommendations.isEmpty()) {
            summary.append("- Aucune action urgente. Profil stable.\n")
        } else {
            report.recommendations.forEach { rec ->
                rec.advisorActions.forEach { action ->
                    // Convert action code to readable string representation for the LLM via internal map
                    // (Real implementation would use the localized strings, but here we just need semantics)
                    summary.append("- ${action.actionCode} : ${action.params}\n")
                }
            }
        }

        // Instructions
        summary.append("\n")
        summary.append("Tu es le Coach AIMI, un expert bienveillant en diabète.\n")
        summary.append("Analyse ces données et explique SIMPLEMENT pourquoi ces actions sont proposées.\n")
        summary.append("Sois encourageant. Ne propose PAS de nouvelles actions techniques (bolus, etc.), contente-toi d'expliquer celles listées.\n")
        summary.append("Réponds en Français, format court (max 3 phrases par point clé).")

        return summary.toString()
    }

    /**
     * Build JSON body for OpenAI API.
     */
    private fun buildJsonBody(prompt: String): JSONObject {
        val root = JSONObject()
        root.put("model", MODEL)
        
        val messages = JSONArray()
        
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", "You are a helpful diabetes optimization assistant.")
        messages.put(systemMsg)

        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", prompt)
        messages.put(userMsg)

        root.put("messages", messages)
        root.put("temperature", 0.7)
        root.put("max_tokens", 400)
        
        return root
    }

    /**
     * Parse OpenAI JSON response.
     */
    private fun parseResponse(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val choices = root.getJSONArray("choices")
            if (choices.length() > 0) {
                choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            } else {
                "Pas de réponse du coach."
            }
        } catch (e: Exception) {
            "Erreur de lecture de la réponse AI."
        }
    }
}
