package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import android.content.Context
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ============================================================================
 * AIMI AI Decision Auditor - AI Service
 * ============================================================================
 * 
 * Handles communication with AI providers (OpenAI, Gemini, DeepSeek, Claude)
 * to get audit verdicts. Reuses existing infrastructure from AiCoachingService.
 */
@Singleton
class AuditorAIService @Inject constructor(
    private val preferences: Preferences,
    private val context: Context
) {
    
    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent"
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
        
        // Timeout for API calls (2 minutes max)
        private const val DEFAULT_TIMEOUT_MS = 120_000L
    }
    
    enum class Provider(val id: String, val displayName: String) {
        OPENAI("openai", "ChatGPT (GPT-4o)"),
        GEMINI("gemini", "Gemini (2.0 Flash)"),
        DEEPSEEK("deepseek", "DeepSeek (Chat)"),
        CLAUDE("claude", "Claude (3.5 Sonnet)")
    }
    
    /**
     * Get audit verdict from AI provider
     * 
     * @param input Complete auditor input
     * @param provider AI provider to use
     * @param timeoutMs Timeout in milliseconds
     * @return Auditor verdict or null if failed/timeout
     */
    suspend fun getVerdict(
        input: AuditorInput,
        provider: Provider,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): AuditorVerdict? = withContext(Dispatchers.IO) {
        
        // Get API key
        val apiKey = getApiKey(provider)
        if (apiKey.isBlank()) {
            return@withContext null
        }
        
        // Build prompt
        val prompt = AuditorPromptBuilder.buildPrompt(input)
        
        // Call AI with timeout
        val responseJson = withTimeoutOrNull(timeoutMs) {
            try {
                when (provider) {
                    Provider.OPENAI -> callOpenAI(apiKey, prompt)
                    Provider.GEMINI -> callGemini(apiKey, prompt)
                    Provider.DEEPSEEK -> callDeepSeek(apiKey, prompt)
                    Provider.CLAUDE -> callClaude(apiKey, prompt)
                }
            } catch (e: Exception) {
                null
            }
        }
        
        // Parse response
        if (responseJson != null) {
            try {
                parseVerdict(responseJson, provider)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Get API key for provider from preferences
     */
    private fun getApiKey(provider: Provider): String {
        return when (provider) {
            Provider.OPENAI -> preferences.get(StringKey.AimiAdvisorOpenAIKey)
            Provider.GEMINI -> preferences.get(StringKey.AimiAdvisorGeminiKey)
            Provider.DEEPSEEK -> preferences.get(StringKey.AimiAdvisorDeepSeekKey)
            Provider.CLAUDE -> preferences.get(StringKey.AimiAdvisorClaudeKey)
        }
    }
    
    /**
     * Call OpenAI API
     */
    private fun callOpenAI(apiKey: String, prompt: String): String {
        val url = URL(OPENAI_URL)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        
        val requestBody = JSONObject().apply {
            put("model", "gpt-4o")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.3)
            put("response_format", JSONObject().put("type", "json_object"))
        }
        
        OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }
        
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP $responseCode")
        }
        
        return BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
    }
    
    /**
     * Call Gemini API
     */
    private fun callGemini(apiKey: String, prompt: String): String {
        val urlStr = "$GEMINI_URL?key=$apiKey"
        val url = URL(urlStr)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("responseMimeType", "application/json")
            })
        }
        
        OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }
        
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP $responseCode")
        }
        
        return BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
    }
    
    /**
     * Call DeepSeek API
     */
    private fun callDeepSeek(apiKey: String, prompt: String): String {
        val url = URL(DEEPSEEK_URL)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        
        val requestBody = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.3)
            put("response_format", JSONObject().put("type", "json_object"))
        }
        
        OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }
        
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP $responseCode")
        }
        
        return BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
    }
    
    /**
     * Call Claude API
     */
    private fun callClaude(apiKey: String, prompt: String): String {
        val url = URL(CLAUDE_URL)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        
        val requestBody = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 2048)
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }
        
        OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }
        
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP $responseCode")
        }
        
        return BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
    }
    
    /**
     * Parse verdict from API response
     */
    private fun parseVerdict(responseJson: String, provider: Provider): AuditorVerdict {
        val root = JSONObject(responseJson)
        
        val contentJson = when (provider) {
            Provider.OPENAI, Provider.DEEPSEEK -> {
                // OpenAI/DeepSeek format: choices[0].message.content
                root.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            }
            Provider.GEMINI -> {
                // Gemini format: candidates[0].content.parts[0].text
                root.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            }
            Provider.CLAUDE -> {
                // Claude format: content[0].text
                root.getJSONArray("content")
                    .getJSONObject(0)
                    .getString("text")
            }
        }
        
        // Extract JSON from markdown code block if present
        val jsonStr = if (contentJson.contains("```json")) {
            contentJson.substringAfter("```json")
                .substringBefore("```")
                .trim()
        } else if (contentJson.contains("```")) {
            contentJson.substringAfter("```")
                .substringBefore("```")
                .trim()
        } else {
            contentJson.trim()
        }
        
        // Parse verdict JSON
        val verdictJson = JSONObject(jsonStr)
        return AuditorVerdict.fromJSON(verdictJson)
    }
}
