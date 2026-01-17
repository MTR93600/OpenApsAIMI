package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.StringKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🤖 AIMI LLM Physiological Analyzer - MTR Implementation
 * 
 * OPTIONAL component that uses LLM (GPT/Gemini/Claude/DeepSeek) to generate
 * narrative explanations of physiological state.
 * 
 * CRITICAL CONSTRAINTS:
 * - LLM NEVER modifies insulin parameters directly
 * - LLM output is NARRATIVE ONLY (explanation for user)
 * - Timeout: 10 seconds max
 * - If unavailable/failed → system continues normally with deterministic only
 * - API key required (stored in preferences)
 * 
 * Supported Providers:
 * - GPT-4 (OpenAI)
 * - Gemini 2.0 (Google)
 * - Claude 3.5 (Anthropic)
 * - DeepSeek
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMILLMPhysioAnalyzerMTR @Inject constructor(
    private val sp: SP,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "LLMPhysioAnalyzer"
        private const val TIMEOUT_MS = 10_000L
        
        // API endpoints
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.0-pro-preview:generateContent"
        private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
        private const val DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions"
    }
    
    /**
     * Analyzes physiological state using LLM
     * Returns narrative explanation (or empty string if failed)
     * 
     * @param features Current features
     * @param baseline 7-day baseline
     * @param context Deterministic analysis result
     * @return Narrative string (empty if failed/unavailable)
     */
    fun analyze(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        context: PhysioContextMTR
    ): String {
        
        val provider = sp.getString(StringKey.AimiPhysioLLMProvider.key, "gpt4")
        val apiKey = getAPIKey(provider)
        
        if (apiKey.isBlank()) {
            aapsLogger.warn(LTag.APS, "[$TAG] No API key configured for $provider")
            return ""
        }
        
        return try {
            runBlocking {
                withTimeout(TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        when (provider) {
                            "gpt4" -> analyzeWithGPT(features, baseline, context, apiKey)
                            "gemini" -> analyzeWithGemini(features, baseline, context, apiKey)
                            "claude" -> analyzeWithClaude(features, baseline, context, apiKey)
                            "deepseek" -> analyzeWithDeepSeek(features, baseline, context, apiKey)
                            else -> {
                                aapsLogger.warn(LTag.APS, "[$TAG] Unknown provider: $provider")
                                ""
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] LLM analysis failed", e)
            "" // Return empty - not critical
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // GPT-4 INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun analyzeWithGPT(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        context: PhysioContextMTR,
        apiKey: String
    ): String {
        
        val prompt = buildPrompt(features, baseline, context)
        
        val requestBody = JSONObject().apply {
            put("model", "gpt-4")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert diabetes physiologist analyzing sleep, HRV, and activity data. Provide brief, actionable insights in 2-3 sentences.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_completion_tokens", 150)
            put("temperature", 0.3)
        }
        
        val response = makeAPICall(OPENAI_API_URL, requestBody.toString(), mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        ))
        
        return parseGPTResponse(response)
    }
    
    private fun parseGPTResponse(response: String): String {
        return try {
            val json = JSONObject(response)
            json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Failed to parse GPT response", e)
            ""
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // GEMINI 2.0 INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun analyzeWithGemini(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        context: PhysioContextMTR,
        apiKey: String
    ): String {
        
        val prompt = buildPrompt(features, baseline, context)
        
        val requestBody = JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 150)
                put("temperature", 0.3)
            })
        }
        
        val url = "$GEMINI_API_URL?key=$apiKey"
        val response = makeAPICall(url, requestBody.toString(), mapOf(
            "Content-Type" to "application/json"
        ))
        
        return parseGeminiResponse(response)
    }
    
    private fun parseGeminiResponse(response: String): String {
        return try {
            val json = JSONObject(response)
            json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Failed to parse Gemini response", e)
            ""
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // CLAUDE 3.5 INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun analyzeWithClaude(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        context: PhysioContextMTR,
        apiKey: String
    ): String {
        
        val prompt = buildPrompt(features, baseline, context)
        
        val requestBody = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 150)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }
        
        val response = makeAPICall(CLAUDE_API_URL, requestBody.toString(), mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to "2023-06-01",
            "Content-Type" to "application/json"
        ))
        
        return parseClaudeResponse(response)
    }
    
    private fun parseClaudeResponse(response: String): String {
        return try {
            val json = JSONObject(response)
            json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Failed to parse Claude response", e)
            ""
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // DEEPSEEK INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun analyzeWithDeepSeek(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        context: PhysioContextMTR,
        apiKey: String
    ): String {
        
        val prompt = buildPrompt(features, baseline, context)
        
        val requestBody = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are an expert diabetes physiologist. Provide brief insights.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_tokens", 150)
            put("temperature", 0.3)
        }
        
        val response = makeAPICall(DEEPSEEK_API_URL, requestBody.toString(), mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json"
        ))
        
        return parseGPTResponse(response) // Same format as GPT
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PROMPT CONSTRUCTION
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun buildPrompt(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        context: PhysioContextMTR
    ): String {
        return """
        Analyze physiological state for Type 1 Diabetes patient:
        
        CURRENT METRICS:
        - Sleep: ${features.sleepDurationHours.format(1)}h (efficiency ${(features.sleepEfficiency * 100).toInt()}%)
        - HRV: ${features.hrvMeanRMSSD.format(1)}ms RMSSD
        - Resting HR: ${features.rhrMorning} bpm
        - Activity: ${features.stepsDailyAverage} steps/day
        
        7-DAY BASELINE:
        - Sleep P50: ${baseline.sleepDuration.p50.format(1)}h
        - HRV P50: ${baseline.hrvRMSSD.p50.format(1)}ms
        - RHR P50: ${baseline.morningRHR.p50.toInt()} bpm
        
        DETECTED STATE: ${context.state}
        Anomalies: ${buildList {
            if (context.poorSleepDetected) add("Poor sleep")
            if (context.hrvDepressed) add("Low HRV")
            if (context.rhrElevated) add("Elevated RHR")
        }.joinToString(", ")}
        
        Provide a brief (2-3 sentences) physiological interpretation and insulin sensitivity implications.
        Do NOT recommend specific insulin doses or changes - focus on physiological state only.
        """.trimIndent()
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // HTTP CLIENT
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun makeAPICall(url: String, body: String, headers: Map<String, String>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT_MS.toInt()
            connection.readTimeout = TIMEOUT_MS.toInt()
            
            headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }
            
            connection.outputStream.use { it.write(body.toByteArray()) }
            
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                throw Exception("HTTP $responseCode: ${connection.responseMessage}")
            }
            
            return connection.inputStream.bufferedReader().use { it.readText() }
            
        } finally {
            connection.disconnect()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun getAPIKey(provider: String): String {
        // API keys stored in preferences (user-configured)
        return when (provider) {
            "gpt4" -> sp.getString("aimi_openai_api_key", "")
            "gemini" -> sp.getString("aimi_gemini_api_key", "")
            "claude" -> sp.getString("aimi_claude_api_key", "")
            "deepseek" -> sp.getString("aimi_deepseek_api_key", "")
            else -> ""
        }
    }
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
}
