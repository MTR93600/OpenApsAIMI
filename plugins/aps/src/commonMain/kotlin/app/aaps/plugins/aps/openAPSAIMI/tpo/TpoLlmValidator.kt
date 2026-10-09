package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.data.json.OrgJsonCompat.optDoubleCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.KeyValueStore
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.StringKey
import app.aaps.plugins.aps.openAPSAIMI.advisor.AiCoachingService
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextApplySupport
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal class TpoLlmValidator(
    private val sp: KeyValueStore,
    private val aiCoachingService: AiCoachingService,
    private val aapsLogger: AAPSLogger,
) {
    companion object {
        private const val TIMEOUT_HINT_MS = 3000L
    }

    suspend fun validate(
        proposal: TpoProposal,
        plan: TpoApplyPlan,
        input: TpoTickInput,
        ledger: TpoEpisodeLedger,
    ): TpoLlmResult = withContext(Dispatchers.IO) {
        val started = aimiWallClockMs()
        if (!sp.getBoolean(BooleanKey.OApsAIMIContextLLMEnabled.key, false)) {
            return@withContext TpoLlmResult(
                verdict = TpoLlmVerdict.CONFIRM,
                confidence = 1.0,
                rationale = "LLM master disabled — algo only",
                latencyMs = aimiWallClockMs() - started,
            )
        }
        val provider = resolveProvider()
        val apiKey = resolveApiKey(provider)
        if (apiKey.isBlank()) {
            return@withContext TpoLlmResult(
                verdict = TpoLlmVerdict.UNCERTAIN,
                confidence = 0.0,
                rationale = "Missing API key",
                latencyMs = aimiWallClockMs() - started,
            )
        }
        val prompt = buildPrompt(proposal, plan, input, ledger)
        val raw = runCatching {
            aiCoachingService.fetchText(prompt, apiKey, provider)
        }.getOrElse { error ->
            aapsLogger.error(LTag.APS, "TPO LLM error: ${error.message}")
            return@withContext TpoLlmResult(
                verdict = TpoLlmVerdict.UNCERTAIN,
                confidence = 0.0,
                rationale = error.message ?: "LLM error",
                latencyMs = aimiWallClockMs() - started,
            )
        }
        if (raw.startsWith("Erreur") || raw.startsWith("Clé API")) {
            return@withContext TpoLlmResult(
                verdict = TpoLlmVerdict.UNCERTAIN,
                confidence = 0.0,
                rationale = raw.take(240),
                latencyMs = aimiWallClockMs() - started,
            )
        }
        parseResponse(raw, aimiWallClockMs() - started)
    }

    fun shouldApply(result: TpoLlmResult, llmConfirmEnabled: Boolean): Boolean {
        if (!llmConfirmEnabled) return true
        return result.verdict == TpoLlmVerdict.CONFIRM && result.confidence >= 0.70
    }

    private fun buildPrompt(
        proposal: TpoProposal,
        plan: TpoApplyPlan,
        input: TpoTickInput,
        ledger: TpoEpisodeLedger,
    ): String {
        val preview = plan.changes.joinToString("\n") { change ->
            TuningContextApplySupport.formatChangeLine(change)
        }
        val timeline = ledger.recentTimeline().joinToString("\n") { episode ->
            "${episode.type.name} seq=${episode.sequenceIndex} bg=${aimiFmt0(episode.bgExtremeMgdl)} @${episode.peakAtMs}"
        }
        val payload = buildJsonObject {
            put("proposed_pack", proposal.packId.name)
            put("tier", proposal.tier.name)
            put("algo_confidence", proposal.algoConfidence)
            put("reason_codes", JsonArray(proposal.reasonCodes.map { JsonPrimitive(it) }))
            put("bg_mgdl", input.bgMgdl)
            put("delta_5m", input.deltaMgdl5m)
            put("cob_g", input.cobGrams)
            put("meal_prob", input.mealProb)
            put("dawn_endogenous_drive", input.dawnEndogenousDrive)
            put("sleep_debt_score", input.sleepDebtScore)
            put("correction_fragility_score", input.eventMemory.correctionFragilityScore)
            put("post_hyper_exhaustion_score", input.eventMemory.postHyperExhaustionScore)
            put("recent_hypo_floor_mgdl", input.minBgLookback75m)
            put("episode_timeline", timeline)
            put("delta_preview", preview)
        }
        return """
You are AIMI TPO Validator. NEVER suggest insulin doses.
Decide if a temporary 2h preference protection overlay should apply.

${LlmWorldConservativePreamble.FOR_JSON_CONTRACT}

INPUT:
$payload

OUTPUT JSON ONLY:
{
  "verdict": "CONFIRM" | "VETO" | "UNCERTAIN",
  "confidence": 0.0-1.0,
  "rationale": "max 240 chars",
  "competing_hypothesis": "meal_rise" | "dawn" | "exercise" | "none"
}

Rules:
- VETO POST_HYPO if meal_rise likely (high COB/meal_prob).
- VETO POOR_SLEEP if dawn_endogenous_drive dominates.
- VETO EXHAUSTED if no hyper->hypo sequence in episode_timeline.
- If competing hypotheses remain tied after context reconstruction, return UNCERTAIN.
- Timeout budget ~${TIMEOUT_HINT_MS}ms — be concise.
""".trimIndent()
    }

    private fun parseResponse(raw: String, latencyMs: Long): TpoLlmResult {
        val jsonText = raw.substringAfter("{", "{").let { "{" + it.substringBeforeLast("}") + "}" }
        return runCatching {
            val json = Json.parseToJsonElement(jsonText).jsonObject
            val verdict = runCatching {
                TpoLlmVerdict.valueOf(json.optStringCompat("verdict").ifEmpty { "UNCERTAIN" }.uppercase())
            }.getOrDefault(TpoLlmVerdict.UNCERTAIN)
            val confidence = json.optDoubleCompat("confidence", 0.0)
            val competing = json.optStringCompat("competing_hypothesis").ifEmpty { "none" }
            val finalVerdict = if (competing != "none" && confidence >= 0.75) {
                TpoLlmVerdict.VETO
            } else {
                verdict
            }
            TpoLlmResult(
                verdict = finalVerdict,
                confidence = confidence,
                rationale = json.optStringCompat("rationale").take(240),
                competingHypothesis = competing,
                latencyMs = latencyMs,
            )
        }.getOrElse {
            TpoLlmResult(
                verdict = TpoLlmVerdict.UNCERTAIN,
                confidence = 0.0,
                rationale = "Invalid LLM JSON",
                latencyMs = latencyMs,
            )
        }
    }

    private fun resolveProvider(): AiCoachingService.Provider {
        val providerStr = sp.getString(StringKey.AimiAdvisorProvider.key, "OPENAI")
        return when (providerStr) {
            "GEMINI" -> AiCoachingService.Provider.GEMINI
            "DEEPSEEK" -> AiCoachingService.Provider.DEEPSEEK
            "CLAUDE" -> AiCoachingService.Provider.CLAUDE
            else -> AiCoachingService.Provider.OPENAI
        }
    }

    private fun resolveApiKey(provider: AiCoachingService.Provider): String =
        when (provider) {
            AiCoachingService.Provider.OPENAI -> sp.getString(StringKey.AimiAdvisorOpenAIKey.key, "")
            AiCoachingService.Provider.GEMINI -> sp.getString(StringKey.AimiAdvisorGeminiKey.key, "")
            AiCoachingService.Provider.DEEPSEEK -> sp.getString(StringKey.AimiAdvisorDeepSeekKey.key, "")
            AiCoachingService.Provider.CLAUDE -> sp.getString(StringKey.AimiAdvisorClaudeKey.key, "")
        }
}
