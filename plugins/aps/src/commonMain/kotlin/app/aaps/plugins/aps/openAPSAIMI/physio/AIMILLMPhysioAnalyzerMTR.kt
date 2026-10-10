package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmHttpRetry
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble
import app.aaps.plugins.aps.openAPSAIMI.llm.claude.ClaudeModelResolver
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpRequest
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonArr
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonObj
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.atomics.AtomicReference

/**
 * 🤖 AIMI LLM Physiological Analyzer - MTR Implementation
 *
 * OPTIONAL component that uses LLM (GPT/Gemini/Claude/DeepSeek) to generate
 * narrative explanations of physiological state.
 *
 * CRITICAL CONSTRAINTS:
 * - LLM NEVER modifies insulin parameters directly
 * - LLM output is NARRATIVE ONLY (explanation for user)
 * - Timeout: bounded per provider (60 s for Claude/Gemini, 10 s otherwise); [analyze] suspends
 *   instead of blocking a loop thread — the only caller is a daily background worker
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
@SingleIn(AppScope::class)
class AIMILLMPhysioAnalyzerMTR @Inject constructor(
    private val preferences: Preferences,
    private val aapsLogger: AAPSLogger,
    private val geminiResolver: GeminiModelResolver,
    private val aimiHttp: AimiHttp
) {
    private val lastNarrativeRef = AtomicReference("")

    companion object {
        private const val TAG = "LLMPhysioAnalyzer"
        private const val TIMEOUT_MS = 10_000L

        // Claude and Gemini Pro think before they answer. This takes more time, so they get a longer limit.
        // The call runs in the background, so a longer wait does not block the loop.
        private const val THINKING_TIMEOUT_MS = 60_000L

        private fun timeoutFor(provider: String): Long =
            if (provider.uppercase() == "CLAUDE" || provider.uppercase() == "GEMINI") THINKING_TIMEOUT_MS else TIMEOUT_MS

        private val SYSTEM_ROLE_NARRATIVE: String = buildString {
            append(
                "You are an expert diabetes physiologist analyzing sleep, HRV, and activity data. ",
            )
            append("Provide brief, clinically astute insights in 2-3 sentences maximum.\n\n")
            append(LlmWorldConservativePreamble.FOR_NARRATIVE)
        }

        // API endpoints
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        // GEMINI URL dynamic via Resolver
        private const val CLAUDE_API_URL = "https://api.anthropic.com/v1/messages"
        private const val DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions"
    }

    private fun analyzeWithGemini(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        context: PhysioContextMTR,
        apiKey: String
    ): String {
        val prompt = buildPrompt(features, baseline, context)

        // 1. Try Preferred Model (pro tier for reasoning; durable *-latest alias tracks current GA)
        val primaryModel = geminiResolver.resolveGenerateContentModel(apiKey, "gemini-pro-latest")

        try {
            return LlmHttpRetry.withTransientRetry(aapsLogger) { executeGeminiRequest(apiKey, prompt, primaryModel) }
        } catch (e: Exception) {
            // 2. Quota (429) OR still-overloaded (503) after retries → flash fallback (also retried).
            if (LlmHttpRetry.isQuota(e) || LlmHttpRetry.isTransient(e)) {
                val fallbackModel = "gemini-flash-latest"
                aapsLogger.warn(LTag.APS, "[$TAG] Physio: $primaryModel failed (${e.message?.take(80)}). Fallback to $fallbackModel")
                return LlmHttpRetry.withTransientRetry(aapsLogger) { executeGeminiRequest(apiKey, prompt, fallbackModel) }
            }
            throw e
        }
    }

    private fun executeGeminiRequest(apiKey: String, prompt: String, modelId: String): String {
        val url = geminiResolver.getGenerateContentUrl(modelId, apiKey)
        val requestBody = JsonObj().apply {
            put("contents", JsonArr().apply {
                put(JsonObj().apply {
                    put("role", "user")
                    put("parts", JsonArr().apply {
                        put(JsonObj().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
            put("generationConfig", JsonObj().apply {
                put("maxOutputTokens", 2000) // Gemini Pro always thinks; thinking tokens count in this limit
                put("temperature", 0.3)
            })
        }

        val response = makeAPICall(
            url, requestBody.toString(), mapOf(
                "Content-Type" to "application/json"
            ), THINKING_TIMEOUT_MS
        )

        return parseGeminiResponse(response)
    }

    /**
     * Analyzes physiological state using LLM
     * Returns narrative explanation (or empty string if failed)
     *
     * Suspends instead of blocking a loop thread: the only caller is a daily background worker,
     * which can afford to wait. The fresh narrative is returned to the caller directly; a failed
     * or empty call keeps the last good narrative instead of blanking it.
     *
     * @param features Current features
     * @param baseline 7-day baseline
     * @param context Deterministic analysis result
     * @return Narrative string (empty if failed/unavailable)
     */
    suspend fun analyze(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        context: PhysioContextMTR
    ): String {

        val provider = physioProviderFor(preferences.get(StringKey.AimiAdvisorProvider))
        val apiKey = getAPIKey(provider)

        if (apiKey.isBlank()) {
            aapsLogger.warn(LTag.APS, "[$TAG] No API key configured for $provider")
            return ""
        }

        val result = try {
            withTimeout(timeoutFor(provider)) {
                withContext(aapsIoDispatcher) {
                    when (provider) {
                        "gpt4" -> analyzeWithGPT(features, baseline, context, apiKey)
                        "gemini" -> analyzeWithGemini(features, baseline, context, apiKey)
                        "claude" -> analyzeWithClaude(features, baseline, context, apiKey)
                        "deepseek" -> analyzeWithDeepSeek(features, baseline, context, apiKey)
                        // `physioProviderFor` only ever returns the four names above, so this branch is
                        // unreachable. It stays because `when` is used as an expression here.
                        else -> ""
                    }
                }
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] LLM analysis failed", e)
            ""
        }

        // A model that answers nothing, a reply the provider filtered, and a call that threw all end
        // up here as an empty string. None of them is a reason to wipe text the user could still
        // read, so the last good narrative is kept and handed back instead.
        if (result.isBlank()) return lastNarrativeRef.load()

        lastNarrativeRef.store(result)
        return result
    }

    /**
     * Turns the provider the user picked on the AI keys screen into the name used inside this class.
     *
     * There are two settings in the code. `StringKey.AimiPhysioLLMProvider` is the one this class
     * used to read, and it is on no preference screen at all, so it could only ever hold its default
     * and the user could not change it. `StringKey.AimiAdvisorProvider` is the one the AI keys
     * screen writes, and the Auditor, the coach, the meal advisor and food recognition all follow
     * it. The two hold the same four providers under different spellings, so they map one to one and
     * this class now follows the setting the user actually fills in, next to the four keys it reads.
     *
     * An unknown value falls back to OpenAI, which is what the advisor setting's own default and the
     * other readers of it do. The fallback cannot send anything on its own: without an OpenAI key
     * the guard in [analyze] still stops the call.
     */
    internal fun physioProviderFor(advisorProvider: String): String =
        when (advisorProvider.uppercase()) {
            "OPENAI" -> "gpt4"
            "GEMINI" -> "gemini"
            "CLAUDE" -> "claude"
            "DEEPSEEK" -> "deepseek"
            else -> {
                aapsLogger.warn(LTag.APS, "[$TAG] Unknown provider: $advisorProvider - using OpenAI")
                "gpt4"
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

        val requestBody = JsonObj().apply {
            put("model", "gpt-4")
            put("messages", JsonArr().apply {
                put(JsonObj().apply {
                    put("role", "system")
                    put("content", SYSTEM_ROLE_NARRATIVE)
                })
                put(JsonObj().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_completion_tokens", 150)
            put("temperature", 0.3)
        }

        val response = makeAPICall(
            OPENAI_API_URL, requestBody.toString(), mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json"
            )
        )

        return parseGPTResponse(response)
    }

    private fun parseGPTResponse(response: String): String {
        return try {
            val json = Json.parseToJsonElement(response).jsonObject
            json["choices"]!!.jsonArray[0]
                .jsonObject["message"]!!.jsonObject["content"]!!.narrativeText()
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Failed to parse GPT response", e)
            ""
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GEMINI 2.0 INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════

    private fun parseGeminiResponse(response: String): String {
        return try {
            val json = Json.parseToJsonElement(response).jsonObject
            json["candidates"]!!.jsonArray[0]
                .jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray[0]
                .jsonObject["text"]!!.narrativeText()
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Failed to parse Gemini response", e)
            ""
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLAUDE INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════

    private fun analyzeWithClaude(
        features: PhysioFeaturesMTR,
        baseline: PhysioBaselineMTR,
        context: PhysioContextMTR,
        apiKey: String
    ): String {

        val prompt = buildPrompt(features, baseline, context)

        val model = ClaudeModelResolver.current()
        val requestBody = JsonObj().apply {
            put("model", model)
            // Thinking tokens count in this limit on newer Claude models.
            // The prompt keeps the text short, not this limit.
            put("max_tokens", 2000)
            // Low effort: a short text does not need deep thinking. Haiku 4.5 rejects this field.
            if (ClaudeModelResolver.supportsEffort(model)) {
                put("output_config", JsonObj().apply { put("effort", "low") })
            }
            put("messages", JsonArr().apply {
                put(JsonObj().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val response = makeAPICall(
            CLAUDE_API_URL, requestBody.toString(), mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json"
            ), THINKING_TIMEOUT_MS
        )

        return parseClaudeResponse(response)
    }

    private fun parseClaudeResponse(response: String): String {
        return try {
            val json = Json.parseToJsonElement(response).jsonObject
            ClaudeModelResolver.extractText(json)
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

        val requestBody = JsonObj().apply {
            put("model", "deepseek-chat")
            put("messages", JsonArr().apply {
                put(JsonObj().apply {
                    put("role", "system")
                    put("content", SYSTEM_ROLE_NARRATIVE)
                })
                put(JsonObj().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_tokens", 150)
            put("temperature", 0.3)
        }

        val response = makeAPICall(
            DEEPSEEK_API_URL, requestBody.toString(), mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json"
            )
        )

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
        # SYSTEM ROLE:
        You are **Diaby**, AIMI's Physiological Analyst.
        Your role is to interpret complex physiological data (Sleep, HRV, Activity) for a T1D patient.

        ${LlmWorldConservativePreamble.FOR_NARRATIVE}

        # TASK:
        Analyze the following metrics provided below.
        Provide a **brief, clinically astute** interpretation (2-3 sentences max).
        Focus on: Insulin Sensitivity, Stress State, and Recovery status.

        # CURRENT METRICS:
        - Sleep: ${aimiFmt1(features.sleepDurationHours)}h (efficiency ${(features.sleepEfficiency * 100).toInt()}%)
        - HRV: ${aimiFmt1(features.hrvMeanRMSSD)}ms RMSSD
        - Resting HR: ${features.rhrMorning} bpm
        - Activity: ${features.stepsDailyAverage} steps/day

        # 7-DAY BASELINE:
        - Sleep P50: ${aimiFmt1(baseline.sleepDuration.p50)}h
        - HRV P50: ${aimiFmt1(baseline.hrvRMSSD.p50)}ms
        - RHR P50: ${baseline.morningRHR.p50.toInt()} bpm

        # DETECTED STATE: ${context.state}
        - Anomalies: ${buildList {
            if (context.poorSleepDetected) add("Poor sleep")
            if (context.hrvDepressed) add("Low HRV")
            if (context.rhrElevated) add("Elevated RHR")
        }.joinToString(", ")}

        # INSTRUCTIONS:
        - Be direct and professional.
        - Explain *why* the state matters (e.g., "Low HRV indicates sympathetic dominance...").
        - Do NOT suggest specific insulin doses.
        - Conclude with a physiological summary (e.g., "Expect reduced sensitivity today.").
        """.trimIndent()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HTTP CLIENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Sends the request through the [AimiHttp] platform seam instead of `HttpURLConnection`.
     *
     * Non-200 answers raise `Exception("HTTP <code>: <reason>")`, keeping the message shape the
     * callers' retry policy ([LlmHttpRetry]) matches on ("HTTP 503: …", "HTTP 429: …").
     */
    private fun makeAPICall(url: String, body: String, headers: Map<String, String>, timeoutMs: Long = TIMEOUT_MS): String {
        val response = aimiHttp.execute(
            AimiHttpRequest(
                url = url,
                method = "POST",
                connectTimeoutMs = timeoutMs.toInt(),
                readTimeoutMs = timeoutMs.toInt(),
                headers = headers,
                body = body
            )
        )
        if (response.code != 200) {
            throw Exception("HTTP ${response.code}: ${response.reason}")
        }
        return response.body ?: ""
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * The text of one JSON field, or an empty string when the field holds no usable text.
     *
     * Every provider is read through this, because every provider can answer with nothing. A model
     * that has nothing to say, and a reply the provider's own content filter emptied, both come back
     * as JSON `null`. In kotlinx.serialization `JsonNull` is itself a `JsonPrimitive`, and its
     * `content` is the four letters `null`. Reading `.content` therefore produced the narrative
     * "null", and because `"null".isNotBlank()` is true it passed every later check and was stored
     * and shown to the user as that day's insight. `contentOrNull` is null for `JsonNull`, so an
     * empty answer stays empty and [analyze] treats it as no answer.
     */
    private fun JsonElement.narrativeText(): String = jsonPrimitive.contentOrNull?.trim().orEmpty()

    /**
     * The setting that holds the key for one provider, or `null` when the provider is not known.
     *
     * These are the same four settings the user fills in on the AI keys screen, so the key typed
     * there is the key this class sends. Before this, the class read four names that nothing ever
     * wrote, so it never had a key and never ran.
     *
     * Kept apart from [getAPIKey] so a test can read the mapping without a preference store, and
     * so a swap between two providers shows up as a failing test instead of one provider's key
     * being sent to another provider's endpoint.
     */
    internal fun apiKeySettingFor(provider: String): StringKey? = when (provider) {
        "gpt4" -> StringKey.AimiAdvisorOpenAIKey
        "gemini" -> StringKey.AimiAdvisorGeminiKey
        "claude" -> StringKey.AimiAdvisorClaudeKey
        "deepseek" -> StringKey.AimiAdvisorDeepSeekKey
        else -> null
    }

    private fun getAPIKey(provider: String): String {
        // API keys stored in preferences (user-configured)
        val setting = apiKeySettingFor(provider) ?: return ""
        return preferences.get(setting)
    }
}
