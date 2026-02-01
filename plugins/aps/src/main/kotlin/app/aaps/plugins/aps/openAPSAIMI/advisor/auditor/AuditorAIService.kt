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
    private val context: Context,
    private val auditorStatusLiveData: app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusLiveData
) {
    
    companion object {
        private const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"

        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
        
        // Timeout for API calls (45s max per security audit to avoid blocking loop)
        private const val DEFAULT_TIMEOUT_MS = 45_000L
    }
    
    enum class Provider(val id: String, val displayName: String) {
        OPENAI("openai", "ChatGPT (GPT-5.2)"),
        GEMINI("gemini", "Gemini (3.0 Pro)"),
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
            AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_APIKEY)
            auditorStatusLiveData.notifyUpdate()
            return@withContext null
        }
        
        // Build prompt
        val prompt = AuditorPromptBuilder.buildPrompt(input)
        
        // --- ROCKET SAUVAGE: RETRY LOGIC (3 attempts) ---
        var lastException: Exception? = null
        val maxRetries = 3
        
        for (attempt in 1..maxRetries) {
            try {
                // Call AI with timeout
                val responseJson = withTimeoutOrNull(timeoutMs) {
                    when (provider) {
                        Provider.OPENAI -> callOpenAI(apiKey, prompt)
                        Provider.GEMINI -> callGemini(apiKey, prompt)
                        Provider.DEEPSEEK -> callDeepSeek(apiKey, prompt)
                        Provider.CLAUDE -> callClaude(apiKey, prompt)
                    }
                }
                
                if (responseJson != null) {
                    // Success!
                    try {
                        return@withContext parseVerdict(responseJson, provider)
                    } catch (e: Exception) {
                        // JSON parsing failed - unlikely to succeed on retry unless response was partial
                        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_PARSE)
                        auditorStatusLiveData.notifyUpdate()
                        return@withContext null
                    }
                } else {
                    // Time out in coroutine
                    throw java.net.SocketTimeoutException("Coroutine timeout after ${timeoutMs}ms")
                }

            } catch (e: Exception) {
                lastException = e
                // Only retry on network/timeout/server errors
                val isRetryable = e is java.net.SocketTimeoutException || 
                                  e is java.io.IOException || 
                                  e is java.net.UnknownHostException
                                  
                if (attempt < maxRetries && isRetryable) {
                    val backoff = attempt * 2000L // 2s, 4s
                    // Log retry
                    println("⚠️ Auditor ${provider} attempt $attempt failed: ${e.message}. Retrying in ${backoff}ms...")
                    Thread.sleep(backoff) // Blocking inside IO dispatcher is acceptable here
                } else {
                    // Final failure or non-retryable
                    break
                }
            }
        }
        
        // Identify final error
        when (lastException) {
            is java.net.UnknownHostException -> AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_NETWORK)
            is java.net.SocketTimeoutException -> AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_TIMEOUT)
            is java.io.IOException -> AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_NETWORK)
            else -> AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_EXCEPTION)
        }
        auditorStatusLiveData.notifyUpdate()
        return@withContext null
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
            connectTimeout = 15_000 // Reduced per audit
            readTimeout = DEFAULT_TIMEOUT_MS.toInt()   // Reduced per audit
        }
        
        val requestBody = JSONObject().apply {
            put("model", "gpt-5.2")  // O-series reasoning model
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            // GPT-5 uses max_completion_tokens instead of max_tokens
            put("max_completion_tokens", 2048)
            put("response_format", JSONObject().put("type", "json_object"))
        }
        
        OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }
        
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP $responseCode")
        }
        
        // Robust stream reading (same as Vision Providers fix)
        val response = StringBuilder()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)  // 8KB chunks
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                response.append(buffer, 0, charsRead)
            }
        }
        return response.toString()
    }
    
    private val geminiResolver = app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver(context)

    /**
     * Call Gemini API
     */
    private fun callGemini(apiKey: String, prompt: String): String {
        // 1. Try Preferred Model
        val primaryModel = geminiResolver.resolveGenerateContentModel(apiKey, "gemini-3-pro-preview")
        
        try {
            return executeGeminiRequest(apiKey, prompt, primaryModel)
        } catch (e: Exception) {
            // 2. Fallback on Quota Exceeded (429)
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted")) {
                val fallbackModel = "gemini-2.5-flash"
                android.util.Log.w("AIMI_GEMINI", "Auditor Quota Exceeded. Fallback to $fallbackModel")
                return executeGeminiRequest(apiKey, prompt, fallbackModel)
            }
            throw e
        }
    }

    private fun executeGeminiRequest(apiKey: String, prompt: String, modelId: String): String {
        val urlStr = geminiResolver.getGenerateContentUrl(modelId, apiKey)
        val url = URL(urlStr)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 45_000
        }
        
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 8192)
                put("responseMimeType", "application/json")
            })
        }
        
        OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }
        
        val responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_OK) {
             val response = StringBuilder()
             connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                 val buffer = CharArray(8192)
                 var charsRead: Int
                 while (reader.read(buffer).also { charsRead = it } != -1) {
                     response.append(buffer, 0, charsRead)
                 }
             }
             return response.toString()
        } else {
             val errorStream = connection.errorStream ?: connection.inputStream
             val errorResponse = errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
             throw Exception("HTTP $responseCode: $errorResponse")
        }
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
            connectTimeout = 15_000 // Reduced per audit
            readTimeout = DEFAULT_TIMEOUT_MS.toInt()   // Reduced per audit
        }
        
        val requestBody = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_tokens", 2048)  // FIX: Was missing - same as other providers
            put("temperature", 0.3)
            put("response_format", JSONObject().put("type", "json_object"))
        }
        
        OutputStreamWriter(connection.outputStream).use { it.write(requestBody.toString()) }
        
        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw Exception("HTTP $responseCode")
        }
        
        // Robust stream reading (same as Vision Providers fix)
        val response = StringBuilder()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)  // 8KB chunks
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                response.append(buffer, 0, charsRead)
            }
        }
        return response.toString()
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
            connectTimeout = 15_000 // Reduced per audit
            readTimeout = DEFAULT_TIMEOUT_MS.toInt()   // Reduced per audit
        }
        
        val requestBody = JSONObject().apply {
            put("model", "claude-sonnet-4-5-20250929")  // Claude Sonnet 4.5 (Sept 2025)
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
        
        // Robust stream reading (same as Vision Providers fix)
        val response = StringBuilder()
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)  // 8KB chunks
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                response.append(buffer, 0, charsRead)
            }
        }
        return response.toString()
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
