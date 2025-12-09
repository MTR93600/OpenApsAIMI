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
        val sb = StringBuilder()
        
        // 1. Context: Metrics
        sb.append("CONTEXTE PATIENT (7 derniers jours) :\n")
        sb.append("- TIR 70-180: ${(ctx.metrics.tir70_180 * 100).roundToInt()}%\n")
        sb.append("- Hypo (<70): ${(ctx.metrics.timeBelow70 * 100).roundToInt()}%\n")
        sb.append("- Hyper (>180): ${(ctx.metrics.timeAbove180 * 100).roundToInt()}%\n")
        sb.append("- Moyenne BG: ${ctx.metrics.meanBg.roundToInt()} mg/dL (GMI: ${ctx.metrics.gmi}%)\n")
        sb.append("- TDD Moyen: ${ctx.metrics.tdd.roundToInt()} U (Basale: ${(ctx.metrics.basalPercent * 100).roundToInt()}%, Bolus: ${(100 - (ctx.metrics.basalPercent * 100).roundToInt())}%)\n")
        sb.append("- Score AIMI: ${report.overallScore}/10\n\n")

        // 2. Context: Profile Settings
        sb.append("PARAMÈTRES DU PROFIL ACTUEL :\n")
        sb.append("- Basale Nuit: ${ctx.profile.nightBasal} U/h\n")
        sb.append("- Ratio Glucides (IC): ${ctx.profile.icRatio} g/U\n")
        sb.append("- Sensibilité (ISF): ${ctx.profile.isf} mg/dL/U\n")
        sb.append("- Cible (Target): ${ctx.profile.targetBg} mg/dL\n\n")

        // 3. Context: Algorithm Preferences
        sb.append("PRÉFÉRENCES AIMI :\n")
        sb.append("- Max SMB: ${ctx.prefs.maxSmb} U\n")
        sb.append("- Facteur Déjeuner: ${ctx.prefs.lunchFactor}x\n")
        sb.append("- Autodrive Max: ${ctx.prefs.autodriveMaxBasal} U\n\n")

        // 4. Observations (Rules Engine)
        sb.append("OBSERVATIONS DU SYSTÈME :\n")
        if (report.recommendations.isEmpty()) {
            sb.append("- Aucune alerte système majeure.\n")
        } else {
            report.recommendations.forEach { rec ->
                 // Use resource ID mapping simulation or just generic description for context
                 val type = rec.domain.name
                 val prio = rec.priority.name
                 sb.append("- $type ($prio): Voir actions suggérées.\n")
            }
        }
        sb.append("\n")

        // 5. Instruction / Persona
        sb.append("CONSIDNE :\n")
        sb.append("Tu es un Expert Médical spécialiste des boucles fermées (OpenAPS).\n")
        sb.append("Ton objectif est d'optimiser le réglage du profil pour améliorer le TIR et réduire les Hypos/Hypers.\n")
        sb.append("Analyse la corrélation entre les paramètres (ISF, IC, Basale) et les résultats.\n")
        sb.append("SI les résultats sont sous-optimaux (TIR < 80% ou Hypos > 3%), TU DOIS RECOMMANDER UNE MODIFICATION PRÉCISE D'UN PARAMÈTRE.\n")
        sb.append("Analyses spécifiques :\n")
        sb.append("- Si hypos fréquentes : vérifie Basale Nuit et ISF.\n")
        sb.append("- Si hypers repas : vérifie Ratio Glucides (IC) et repas 'Moyen'/'Fort'.\n")
        sb.append("- Si SMB inefficace : vérifie 'MaxSMB' et 'Unified Reactivity'.\n")
        sb.append("\nIMPORTANT : Si tu recommandes une modification complexe, ajoute toujours : 'Pour plus de détails, consulte la documentation AIMI'.\n")
        
        val deviceLang = java.util.Locale.getDefault().displayLanguage
        sb.append("Réponds en $deviceLang. Format : 'Analyse courte' puis 'Recommandation ' (liste à puces concrète).")

        return sb.toString()
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
