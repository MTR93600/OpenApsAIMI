package app.aaps.plugins.aps.openAPSAIMI.advisor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmHttpRetry
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import java.util.Locale

/**
 * =============================================================================
 * AI COACHING SERVICE
 * =============================================================================
 * 
 * Interacts with OpenAI API to generate natural language coaching advice.
 * Uses robust HttpURLConnection (zero dependency).
 * =============================================================================
 */
@SingleIn(AppScope::class)
class AiCoachingService @Inject constructor(
    private val rh: ResourceHelper,
) {

    enum class Provider { OPENAI, GEMINI, DEEPSEEK, CLAUDE }

    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"

        private const val OPENAI_MODEL = "gpt-5.4-mini" // Efficient current GA tier for coaching (gpt-4o-mini is legacy)
        

        
        // DeepSeek Chat (OpenAI-compatible)
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val DEEPSEEK_MODEL = "deepseek-chat"
        
        // Claude Haiku (Fast & Cheap) — current GA fast tier (claude-3-haiku-20240307 was retired).
        private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
        private const val CLAUDE_MODEL = "claude-haiku-4-5"
    }

    /**
     * Fetch advice asynchronously.
     */
    internal suspend fun fetchAdvice(
        androidContext: Context,
        context: AdvisorContext, 
        report: AdvisorReport, 
        apiKey: String,
        provider: Provider,
        history: List<app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.AdvisorActionLog> = emptyList(),
        includeRichOref: Boolean = true,
        causalInsights: List<AimiBehaviorCausalInsight> = emptyList(),
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Clé API manquante. Veuillez configurer votre clé ${provider.name}."

        try {
            val prompt = buildPrompt(androidContext, context, report, history, includeRichOref, causalInsights)
            
            return@withContext when (provider) {
                Provider.GEMINI -> callGemini(androidContext, apiKey, prompt)
                Provider.DEEPSEEK -> callDeepSeek(apiKey, prompt)
                Provider.CLAUDE -> callClaude(apiKey, prompt)
                else -> callOpenAI(apiKey, prompt)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "Erreur de connexion (${provider.name}) : ${e.localizedMessage}"
        }
    }
    
    /**
     * Simple text generation for Context Module.
     * 
     * @param prompt Complete prompt (system + user message)
     * @param apiKey API key for the provider
     * @param provider Which LLM provider to use
     * @return Generated text or error message
     */
    suspend fun fetchText(
        context: Context,
        prompt: String,
        apiKey: String,
        provider: Provider
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "Clé API manquante."
        if (prompt.isBlank()) return@withContext "Prompt vide."
        
        try {
            return@withContext when (provider) {
                Provider.GEMINI -> callGemini(context, apiKey, prompt)
                Provider.DEEPSEEK -> callDeepSeek(apiKey, prompt)
                Provider.CLAUDE -> callClaude(apiKey, prompt)
                else -> callOpenAI(apiKey, prompt)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext "Erreur: ${e.localizedMessage}"
        }
    }

    // ... (keep private methods)


    private fun callOpenAI(apiKey: String, prompt: String): String = LlmHttpRetry.withTransientRetry {
        val jsonBody = buildOpenAiJson(prompt)
        jsonBody.put("max_completion_tokens", 4096) // GPT-5.x requires this (rejects legacy max_tokens)
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
            parseOpenAiResponse(response.toString())
        } else {
            // Try read error stream
            val reader = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream))
            val err = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) err.append(line)
            if (LlmHttpRetry.isTransientStatus(responseCode)) throw java.io.IOException("OpenAI Error ($responseCode): $err")
            "Erreur OpenAI ($responseCode): $err"
        }
    }

    private fun callGemini(context: Context, apiKey: String, prompt: String): String {
        val resolver = app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver(context)
        
        // 1. Try Preferred Model (efficient flash tier; durable *-latest alias tracks current GA)
        val primaryModel = resolver.resolveGenerateContentModel(apiKey, "gemini-flash-latest")
        
        try {
            // Transient overload (503/UNAVAILABLE) is retried with bounded backoff on the same model.
            return LlmHttpRetry.withTransientRetry { executeGeminiRequest(resolver, apiKey, prompt, primaryModel) }
        } catch (e: Exception) {
            // 2. Quota (429) OR still-overloaded after retries → fallback to the resilient flash alias (also retried).
            if (LlmHttpRetry.isQuota(e) || LlmHttpRetry.isTransient(e)) {
                val fallbackModel = "gemini-flash-latest" // Durable flash alias (current GA)
                android.util.Log.w("AIMI_GEMINI", "⚠️ $primaryModel failed (${e.message?.take(80)}). Fallback to $fallbackModel")
                return LlmHttpRetry.withTransientRetry { executeGeminiRequest(resolver, apiKey, prompt, fallbackModel) }
            }
            throw e // Re-throw other errors
        }
    }

    private fun executeGeminiRequest(
        resolver: app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver,
        apiKey: String, 
        prompt: String, 
        modelId: String
    ): String {
        val urlStr = resolver.getGenerateContentUrl(modelId, apiKey)
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        
        val jsonBody = JSONObject()
        val parts = JSONArray()
        val part = JSONObject()
        part.put("text", prompt)
        parts.put(part)
        
        val content = JSONObject()
        content.put("parts", parts)
        content.put("role", "user")
        
        val contents = JSONArray()
        contents.put(content)
        
        val root = JSONObject()
        root.put("contents", contents)
        
        val config = JSONObject()
        config.put("temperature", 0.7)
        config.put("maxOutputTokens", 4096)
        root.put("generationConfig", config)

        connection.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
        }

        OutputStreamWriter(connection.outputStream).use { it.write(root.toString()) }

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
             throw Exception("Gemini Error ($responseCode): $err")
        }
    }

    private fun buildPrompt(
        androidContext: Context, 
        ctx: AdvisorContext, 
        report: AdvisorReport,
        history: List<app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository.AdvisorActionLog>,
        includeRichOref: Boolean,
        causalInsights: List<AimiBehaviorCausalInsight>,
    ): String {
        val sb = StringBuilder()
        val deviceLang = java.util.Locale.getDefault().displayLanguage
        
        // Persona
        sb.append("You are AIMI, an expert 'Certified Diabetes Educator' specializing in Automated Insulin Delivery (AID).\n")
        sb.append("Your Goal: Analyze the patient's recent glucose & insulin data to identify patterns and suggest specific algorithm tuning.\n")
        sb.append("Tone: Professional, encouraging, precise, and safety-first.\n\n")
        sb.append(LlmWorldConservativePreamble.FOR_NARRATIVE)
        sb.append("\n\n")

        // 0.5 STABILITY CONTEXT (History)
        sb.append("--- HISTORY & STABILITY CONTEXT ---\n")
        if (history.isNotEmpty()) {
            sb.append("Recent changes made by the user:\n")
            history.take(5).forEach { 
                val date = java.text.SimpleDateFormat("dd/MM", java.util.Locale.US).format(java.util.Date(it.timestamp))
                sb.append("- [$date] ${it.description} (${it.oldValue} -> ${it.newValue})\n")
            }
            sb.append("CRITICAL: If a structured parameter was recently changed (last 3-5 days), AVOID suggesting further contradictory changes to it unless safety is at risk. Allow time for the change to work.\n\n")
        } else {
            sb.append("No recent changes recorded. You may suggest bold adjustments if necessary.\n\n")
        }

        // 1. Context: Metrics
        sb.append("--- PATIENT METRICS (Advisor period) ---\n")
        sb.append("Score: ${report.overallScore}/10 | GMI-style index: ${String.format(Locale.US, "%.1f", ctx.metrics.gmi)}\n")
        sb.append("TIR (70-180): ${(ctx.metrics.tir70_180 * 100).roundToInt()}%\n")
        sb.append("Hypo (<70): ${(ctx.metrics.timeBelow70 * 100).roundToInt()}% | Severe (<54): ${(ctx.metrics.timeBelow54 * 100).roundToInt()}%\n")
        sb.append("Hyper (>180): ${(ctx.metrics.timeAbove180 * 100).roundToInt()}%\n")
        sb.append("Mean Glucose: ${ctx.metrics.meanBg.roundToInt()} mg/dL\n")
        sb.append("Total Daily Dose (TDD): ${ctx.metrics.tdd.roundToInt()} U\n")
        sb.append("Basal/Bolus Split: ${(ctx.metrics.basalPercent * 100).roundToInt()}% Basal | ${(100 - (ctx.metrics.basalPercent * 100).roundToInt())}% Bolus\n\n")

        report.orefAnalysis?.let { oref ->
            sb.append(oref.toPromptSection())
            sb.append("CRITICAL: Treat this block as factual telemetry + heuristics only; do not invent LGBM percentages.\n\n")
            if (includeRichOref) {
                sb.append(oref.toCoachUserInsightsSection())
                sb.append("\n")
            }
        }

        // 1.5 Context: Active Profile & Preferences
        sb.append("--- ACTIVE PROFILE & SETTINGS ---\n")
        sb.append("Max SMB: ${ctx.prefs.maxSmb} U\n")
        sb.append("ISF: ${ctx.profile.isf} mg/dL/U\n")
        sb.append("IC Ratio: ${ctx.profile.icRatio} g/U\n")
        sb.append("Basal (Night): ${ctx.profile.nightBasal} U/h\n")
        sb.append("Total Basal (Profile): ${ctx.profile.totalBasal} U/day\n")
        sb.append("DIA (Profile): ${ctx.profile.dia} h\n")
        sb.append("Target BG: ${ctx.profile.targetBg} mg/dL\n")
        sb.append("Unified reactivity factor: ${String.format(Locale.US, "%.3f", ctx.prefs.unifiedReactivityFactor)}\n")
        if (ctx.prefs.autodriveEnabled) {
            sb.append(
                "AutoDrive: enabled | autodrive max basal pref: ${ctx.prefs.autodriveMaxBasal} U/h | MPC insulin/kg/5min step: ${String.format(Locale.US, "%.3f", ctx.prefs.mpcInsulinUPerKgPerStep)}\n",
            )
        } else {
            sb.append("AutoDrive: off (per preference)\n")
        }
        sb.append("\n")

        if (causalInsights.isNotEmpty()) {
            sb.append("--- FAMILY-LEVEL CAUSAL MAP ---\n")
            sb.append(
                formatAimiBehaviorCausalInsightsForCoach(
                    insights = causalInsights,
                    familyTitle = { familyId ->
                        when (familyId) {
                            app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId.Protection -> "Protection vs correction"
                            app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId.MealCapture -> "Meal capture and fast rises"
                            app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId.Stability -> "Stability and damping"
                            app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId.Physio -> "Physio influence"
                            app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId.Autonomy -> "Autonomy"
                        }
                    },
                ),
            )
            sb.append("\n\n")
        }

        // 2. PKPD Context
        if (ctx.pkpdPrefs.pkpdEnabled) {
             sb.append("--- PKPD (adaptive) ---\n")
             sb.append("DIA: ${ctx.pkpdPrefs.initialDiaH} h (bounds ${ctx.pkpdPrefs.boundsDiaMinH}–${ctx.pkpdPrefs.boundsDiaMaxH} h)\n")
             sb.append("Peak time: ${ctx.pkpdPrefs.initialPeakMin} min (bounds ${ctx.pkpdPrefs.boundsPeakMinMin}–${ctx.pkpdPrefs.boundsPeakMinMax} min)\n")
             // smbTailDamping is the effective runtime value (raw ≤ 0.55 is rewritten to 0.85 neutral at runtime).
             sb.append("ISF fusion max: x${ctx.pkpdPrefs.isfFusionMaxFactor} | SMB tail damping (effective runtime): ${ctx.pkpdPrefs.smbTailDamping}\n\n")
        }

        // 3. System Observations (Recommendations + PKPD)
        sb.append("--- SYSTEM OBSERVATIONS ---\n")
        if (report.recommendations.isNotEmpty()) {
            report.recommendations.forEach {
                val title = try { rh.gs(it.title) } catch (e: Exception) { "Issue" }
                val desc = formatRecommendationDescription(it)
                sb.append("- [Priority ${it.priority}] $title: $desc\n")
            }
        } else {
            sb.append("- No specific algorithmic issues detected.\n")
        }
        sb.append("\n")

        // 4. Instructions
        sb.append("--- COACHING TASK ---\n")
        sb.append("Respond in '$deviceLang'. Structure your answer exactly as follows:\n")
        sb.append("1. 🔍 **Diagnostics**: Main glycemic patterns (night vs day, post-meal, basal-heavy split).\n")
        sb.append("2. 📉 **Root Cause**: Link to levers — ISF/IC/basal segments, profile DIA, PKPD DIA/peak/damping, unified reactivity, AutoDrive MPC — using ONLY facts from metrics, OREF block, PKPD snapshot, and SYSTEM OBSERVATIONS. Do not invent model percentages.\n")
        sb.append("3. 🛠️ **Action Plan**: 2–4 prudent, clinician-supervised steps. When OREF priority is HYPO, prioritize reducing aggressiveness (ISF/basal/MPC) before chasing hyper fixes. When HYPER dominates and hypos are rare, mention IC verification and PKPD DIA/peak before large basal moves.\n")
        sb.append("4. **Tuning direction (no doses)**: For each relevant domain (ISF, IC, basal, PKPD DIA, peak, damping, MPC), state at most ONE cautious direction. Directions must match context (e.g. higher vs lower ISF, increase vs decrease damping/MPC headroom)—never assume everything should only go \"up\".\n")
        sb.append("\nConstraints: Under ~220 words; safety-first; emojis optional; end with a short reminder to confirm with a clinician.\n")
        sb.append("Do not output numeric dose targets, unit amounts, or specific profile values to apply — directions and reasoning only.\n")

        return sb.toString()
    }

    private fun formatRecommendationDescription(rec: AimiRecommendation): String {
        if (rec.description == TextRef.Literal("")) {
            val act = rec.action
            return when (act) {
                is AimiAction.PreferenceUpdate -> act.reason
                else -> rec.descriptionArgs.joinToString(" ").ifBlank { "(plugin suggestion — see apply action if shown)" }
            }
        }
        return try {
            if (rec.descriptionArgs.isNotEmpty()) {
                rh.gs(rec.description, *rec.descriptionArgs.toTypedArray())
            } else {
                rh.gs(rec.description)
            }
        } catch (e: Exception) {
            ""
        }
    }

    // Builds the shared OpenAI-compatible body (model + messages). The token-limit parameter is set by the
    // caller because it differs by provider: GPT-5.x rejects `max_tokens` and requires `max_completion_tokens`,
    // whereas DeepSeek (older OpenAI-compatible spec) expects `max_tokens`.
    private fun buildOpenAiJson(prompt: String): JSONObject {
        val root = JSONObject()
        root.put("model", OPENAI_MODEL)
        val messages = JSONArray()
        // Unified: Prompt contains the full persona and instructions.
        val usr = JSONObject().put("role", "user").put("content", prompt)
        messages.put(usr)
        root.put("messages", messages)
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
    
    private fun callDeepSeek(apiKey: String, prompt: String): String = LlmHttpRetry.withTransientRetry {
        val jsonBody = buildOpenAiJson(prompt) // DeepSeek uses OpenAI-compatible format
        jsonBody.put("model", DEEPSEEK_MODEL) // Override model
        jsonBody.put("max_tokens", 4096) // DeepSeek uses the legacy max_tokens parameter

        val url = URL(DEEPSEEK_URL)
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
            parseOpenAiResponse(response.toString()) // Same format
        } else {
            val reader = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream))
            val err = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) err.append(line)
            if (LlmHttpRetry.isTransientStatus(responseCode)) throw java.io.IOException("DeepSeek Error ($responseCode): $err")
            "Erreur DeepSeek ($responseCode): $err"
        }
    }
    
    private fun callClaude(apiKey: String, prompt: String): String = LlmHttpRetry.withTransientRetry {
        val url = URL(CLAUDE_URL)
        val connection = url.openConnection() as HttpURLConnection

        val jsonBody = JSONObject()
        jsonBody.put("model", CLAUDE_MODEL)
        jsonBody.put("max_tokens", 4096)
        jsonBody.put("temperature", 0.7)

        // Claude expects messages array with role/content
        val messages = JSONArray()
        val userMessage = JSONObject()
        userMessage.put("role", "user")
        userMessage.put("content", prompt)
        messages.put(userMessage)
        jsonBody.put("messages", messages)

        connection.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
        }

        val writer = OutputStreamWriter(connection.outputStream)
        writer.write(jsonBody.toString())
        writer.flush()
        writer.close()

        val responseCode = connection.responseCode
        if (responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream, java.nio.charset.StandardCharsets.UTF_8))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) response.append(line)
            reader.close()
            parseClaudeResponse(response.toString())
        } else {
            val reader = BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream, java.nio.charset.StandardCharsets.UTF_8))
            val err = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) err.append(line)
            // 503/529/500… → throw so it is retried with backoff; other errors surface as-is.
            if (LlmHttpRetry.isTransientStatus(responseCode)) throw java.io.IOException("Claude Error ($responseCode): $err")
            "Erreur Claude ($responseCode): $err"
        }
    }
    
    private fun parseClaudeResponse(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val content = root.getJSONArray("content")
            content.getJSONObject(0).getString("text").trim()
        } catch (e: Exception) {
            "Erreur lecture Claude."
        }
    }
}
