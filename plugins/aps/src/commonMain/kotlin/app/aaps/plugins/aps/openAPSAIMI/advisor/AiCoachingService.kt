package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.aimiDeviceLanguageName
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorFamilyId
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmHttpRetry
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpRequest
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * `dd/MM` in the phone's own time zone, the stamp the history lines in the prompt have always shown.
 *
 * This replaces `SimpleDateFormat("dd/MM", Locale.US)`, which does not exist outside the JVM. Both
 * fields are all digits and zero padded, so `Locale.US` only ever pinned the calendar, and this
 * formatter is calendar independent by construction - the same reasoning as `aimiCsvTimestamp`.
 */
private val advisorHistoryDayMonth = LocalDateTime.Format {
    day(); char('/'); monthNumber()
}

/**
 * One decimal, dot separator, ties away from zero - what `String.format(Locale.US, "%.1f", v)` gave.
 *
 * These numbers go into the prompt an assistant reads, so the text has to stay the same. Ties away
 * from zero is what `%.1f` does, and a tie at one decimal is not reachable by a `Double` anyway.
 */
private fun promptFmt1(value: Double): String =
    NumberFormat.withDecimalsHalfUp(1).format(value, NumberFormatPlatform.SEPARATOR_DOT)

/** Three decimals, dot separator, ties away from zero - what `String.format(Locale.US, "%.3f", v)` gave. */
private fun promptFmt3(value: Double): String =
    NumberFormat.withDecimalsHalfUp(3).format(value, NumberFormatPlatform.SEPARATOR_DOT)

/**
 * =============================================================================
 * AI COACHING SERVICE
 * =============================================================================
 *
 * Interacts with OpenAI API to generate natural language coaching advice.
 * Sends its requests through [AimiHttp], the shared HTTP seam.
 * =============================================================================
 */
@SingleIn(AppScope::class)
class AiCoachingService @Inject constructor(
    private val rh: TextResolver,
    private val aapsLogger: AAPSLogger,
    private val geminiModelResolver: GeminiModelResolver,
    private val aimiHttp: AimiHttp,
) {

    /**
     * Joins the lines of [text] with nothing between them.
     *
     * The readers this replaces built their string with a `readLine()` loop and appended each line
     * without its line ending, so every line break in a body was dropped. The error text the user
     * finally reads is built from that string, so the joining has to stay exactly as it was.
     */
    private fun joinLines(text: String?): String =
        text?.split("\r\n", "\n", "\r")?.joinToString("") ?: ""

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
        context: AdvisorContext,
        report: AdvisorReport,
        apiKey: String,
        provider: Provider,
        history: List<AdvisorHistoryRepository.AdvisorActionLog> = emptyList(),
        includeRichOref: Boolean = true,
        causalInsights: List<AimiBehaviorCausalInsight> = emptyList(),
    ): String = withContext(aapsIoDispatcher) {
        if (apiKey.isBlank()) return@withContext rh.gs(ApsStrings.aimi_coach_svc_missing_key, provider.name)

        try {
            val prompt = buildPrompt(context, report, history, includeRichOref, causalInsights)

            return@withContext when (provider) {
                Provider.GEMINI -> callGemini(apiKey, prompt)
                Provider.DEEPSEEK -> callDeepSeek(apiKey, prompt)
                Provider.CLAUDE -> callClaude(apiKey, prompt)
                else -> callOpenAI(apiKey, prompt)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext rh.gs(ApsStrings.aimi_coach_svc_connection_error, provider.name, e.message)
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
        prompt: String,
        apiKey: String,
        provider: Provider
    ): String = withContext(aapsIoDispatcher) {
        if (apiKey.isBlank()) return@withContext rh.gs(ApsStrings.aimi_coach_svc_missing_key_simple)
        if (prompt.isBlank()) return@withContext rh.gs(ApsStrings.aimi_coach_svc_empty_prompt)

        try {
            return@withContext when (provider) {
                Provider.GEMINI -> callGemini(apiKey, prompt)
                Provider.DEEPSEEK -> callDeepSeek(apiKey, prompt)
                Provider.CLAUDE -> callClaude(apiKey, prompt)
                else -> callOpenAI(apiKey, prompt)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext rh.gs(ApsStrings.aimi_coach_svc_generic_error, e.message)
        }
    }

    // ... (keep private methods)


    private fun callOpenAI(apiKey: String, prompt: String): String = LlmHttpRetry.withTransientRetry(aapsLogger) {
        // GPT-5.x requires max_completion_tokens (it rejects the legacy max_tokens).
        val jsonBody = buildOpenAiJson(prompt, OPENAI_MODEL, "max_completion_tokens", 4096)
        val httpResponse = aimiHttp.execute(
            AimiHttpRequest(
                url = OPENAI_URL,
                method = "POST",
                connectTimeoutMs = 15000,
                readTimeoutMs = 30000,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $apiKey"
                ),
                body = jsonBody.toString()
            )
        )

        val responseCode = httpResponse.code
        if (responseCode == 200) {
            parseOpenAiResponse(joinLines(httpResponse.body))
        } else {
            // Whatever the server said about the refusal
            val err = joinLines(httpResponse.body)
            if (LlmHttpRetry.isTransientStatus(responseCode)) throw Exception("OpenAI Error ($responseCode): $err")
            rh.gs(ApsStrings.aimi_coach_svc_error_openai, responseCode, err)
        }
    }

    private fun callGemini(apiKey: String, prompt: String): String {
        // 1. Try Preferred Model (efficient flash tier; durable *-latest alias tracks current GA)
        val primaryModel = geminiModelResolver.resolveGenerateContentModel(apiKey, "gemini-flash-latest")

        try {
            // Transient overload (503/UNAVAILABLE) is retried with bounded backoff on the same model.
            return LlmHttpRetry.withTransientRetry(aapsLogger) { executeGeminiRequest(apiKey, prompt, primaryModel) }
        } catch (e: Exception) {
            // 2. Quota (429) OR still-overloaded after retries → fallback to the resilient flash alias (also retried).
            if (LlmHttpRetry.isQuota(e) || LlmHttpRetry.isTransient(e)) {
                val fallbackModel = "gemini-flash-latest" // Durable flash alias (current GA)
                aapsLogger.warn(LTag.AIMI, "[AIMI_GEMINI] ⚠️ $primaryModel failed (${e.message?.take(80)}). Fallback to $fallbackModel")
                return LlmHttpRetry.withTransientRetry(aapsLogger) { executeGeminiRequest(apiKey, prompt, fallbackModel) }
            }
            throw e // Re-throw other errors
        }
    }

    private fun executeGeminiRequest(
        apiKey: String,
        prompt: String,
        modelId: String
    ): String {
        val urlStr = geminiModelResolver.getGenerateContentUrl(modelId, apiKey)

        // Same keys, same values and the same order the org.json builder wrote: the content block
        // names its parts before its role, and the generation config comes after the contents.
        val root = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                            put("role", "user")
                        }
                    )
                }
            )
            put(
                "generationConfig",
                buildJsonObject {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 4096)
                }
            )
        }

        val httpResponse = aimiHttp.execute(
            AimiHttpRequest(
                url = urlStr,
                method = "POST",
                connectTimeoutMs = 15000,
                readTimeoutMs = 60000,
                headers = mapOf("Content-Type" to "application/json"),
                body = root.toString()
            )
        )

        val responseCode = httpResponse.code
        if (responseCode == 200) {
            return parseGeminiResponse(joinLines(httpResponse.body))
        } else {
             val err = joinLines(httpResponse.body)
             throw Exception("Gemini Error ($responseCode): $err")
        }
    }

    private fun buildPrompt(
        ctx: AdvisorContext,
        report: AdvisorReport,
        history: List<AdvisorHistoryRepository.AdvisorActionLog>,
        includeRichOref: Boolean,
        causalInsights: List<AimiBehaviorCausalInsight>,
    ): String {
        val sb = StringBuilder()
        val deviceLang = aimiDeviceLanguageName()

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
                val date = advisorHistoryDayMonth.format(
                    Instant.fromEpochMilliseconds(it.timestamp).toLocalDateTime(TimeZone.currentSystemDefault())
                )
                sb.append("- [$date] ${it.description} (${it.oldValue} -> ${it.newValue})\n")
            }
            sb.append("CRITICAL: If a structured parameter was recently changed (last 3-5 days), AVOID suggesting further contradictory changes to it unless safety is at risk. Allow time for the change to work.\n\n")
        } else {
            sb.append("No recent changes recorded. You may suggest bold adjustments if necessary.\n\n")
        }

        // 1. Context: Metrics
        sb.append("--- PATIENT METRICS (Advisor period) ---\n")
        sb.append("Score: ${report.overallScore}/10 | GMI-style index: ${promptFmt1(ctx.metrics.gmi)}\n")
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
        sb.append("Unified reactivity factor: ${promptFmt3(ctx.prefs.unifiedReactivityFactor)}\n")
        if (ctx.prefs.autodriveEnabled) {
            sb.append(
                "AutoDrive: enabled | autodrive max basal pref: ${ctx.prefs.autodriveMaxBasal} U/h | MPC insulin/kg/5min step: ${promptFmt3(ctx.prefs.mpcInsulinUPerKgPerStep)}\n",
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
                            AimiBehaviorFamilyId.Protection -> "Protection vs correction"
                            AimiBehaviorFamilyId.MealCapture -> "Meal capture and fast rises"
                            AimiBehaviorFamilyId.Stability -> "Stability and damping"
                            AimiBehaviorFamilyId.Physio -> "Physio influence"
                            AimiBehaviorFamilyId.Autonomy -> "Autonomy"
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

    // Builds the shared OpenAI-compatible body (model + messages + token limit). The token-limit key is
    // named by the caller because it differs by provider: GPT-5.x rejects `max_tokens` and requires
    // `max_completion_tokens`, whereas DeepSeek (older OpenAI-compatible spec) expects `max_tokens`.
    private fun buildOpenAiJson(prompt: String, model: String, tokenLimitKey: String, tokenLimit: Int): JsonObject =
        buildJsonObject {
            put("model", model)
            // Unified: Prompt contains the full persona and instructions.
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", prompt)
                        }
                    )
                }
            )
            put(tokenLimitKey, tokenLimit)
        }

    private fun parseOpenAiResponse(jsonStr: String): String {
        return try {
            val root = Json.parseToJsonElement(jsonStr).jsonObject
            root.getValue("choices").jsonArray[0].jsonObject
                .getValue("message").jsonObject
                .getValue("content").jsonPrimitive.content.trim()
        } catch (e: Exception) {
            rh.gs(ApsStrings.aimi_coach_svc_read_error_openai)
        }
    }

    private fun parseGeminiResponse(jsonStr: String): String {
        return try {
            val root = Json.parseToJsonElement(jsonStr).jsonObject
            val candidate = root.getValue("candidates").jsonArray[0].jsonObject
            val parts = candidate.getValue("content").jsonObject.getValue("parts").jsonArray
            parts[0].jsonObject.getValue("text").jsonPrimitive.content.trim()
        } catch (e: Exception) {
             // Fallback for safety blocked
             if (jsonStr.contains("finishReason")) rh.gs(ApsStrings.aimi_coach_svc_gemini_blocked) else rh.gs(ApsStrings.aimi_coach_svc_read_error_gemini)
        }
    }

    private fun callDeepSeek(apiKey: String, prompt: String): String = LlmHttpRetry.withTransientRetry(aapsLogger) {
        // DeepSeek uses the OpenAI-compatible format, with the legacy max_tokens parameter.
        val jsonBody = buildOpenAiJson(prompt, DEEPSEEK_MODEL, "max_tokens", 4096)

        val httpResponse = aimiHttp.execute(
            AimiHttpRequest(
                url = DEEPSEEK_URL,
                method = "POST",
                connectTimeoutMs = 15000,
                readTimeoutMs = 30000,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $apiKey"
                ),
                body = jsonBody.toString()
            )
        )

        val responseCode = httpResponse.code
        if (responseCode == 200) {
            parseOpenAiResponse(joinLines(httpResponse.body)) // Same format
        } else {
            val err = joinLines(httpResponse.body)
            if (LlmHttpRetry.isTransientStatus(responseCode)) throw Exception("DeepSeek Error ($responseCode): $err")
            rh.gs(ApsStrings.aimi_coach_svc_error_deepseek, responseCode, err)
        }
    }

    private fun callClaude(apiKey: String, prompt: String): String = LlmHttpRetry.withTransientRetry(aapsLogger) {
        // Claude expects a messages array with role/content.
        val jsonBody = buildJsonObject {
            put("model", CLAUDE_MODEL)
            put("max_tokens", 4096)
            put("temperature", 0.7)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", prompt)
                        }
                    )
                }
            )
        }

        val httpResponse = aimiHttp.execute(
            AimiHttpRequest(
                url = CLAUDE_URL,
                method = "POST",
                connectTimeoutMs = 15000,
                readTimeoutMs = 60000,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01"
                ),
                body = jsonBody.toString()
            )
        )

        val responseCode = httpResponse.code
        if (responseCode == 200) {
            parseClaudeResponse(joinLines(httpResponse.body))
        } else {
            val err = joinLines(httpResponse.body)
            // 503/529/500… → throw so it is retried with backoff; other errors surface as-is.
            if (LlmHttpRetry.isTransientStatus(responseCode)) throw Exception("Claude Error ($responseCode): $err")
            rh.gs(ApsStrings.aimi_coach_svc_error_claude, responseCode, err)
        }
    }

    private fun parseClaudeResponse(jsonStr: String): String {
        return try {
            val root = Json.parseToJsonElement(jsonStr).jsonObject
            val content = root.getValue("content").jsonArray
            content[0].jsonObject.getValue("text").jsonPrimitive.content.trim()
        } catch (e: Exception) {
            rh.gs(ApsStrings.aimi_coach_svc_read_error_claude)
        }
    }
}
