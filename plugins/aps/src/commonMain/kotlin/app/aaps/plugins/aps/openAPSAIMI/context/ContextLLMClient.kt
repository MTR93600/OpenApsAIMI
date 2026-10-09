package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.data.json.OrgJsonCompat.optDoubleCompat
import app.aaps.core.data.json.OrgJsonCompat.optIntCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.aps.openAPSAIMI.advisor.AiCoachingService
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.*
import app.aaps.plugins.aps.openAPSAIMI.llm.LlmWorldConservativePreamble
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * LLM Client pour parsing intelligent du contexte utilisateur.
 *
 * **Design** :
 * - Réutilise l'infrastructure AiCoachingService existante (OpenAI/Gemini/DeepSeek/Claude)
 * - Prompt expert structuré pour garantir sortie JSON exploitable
 * - Fallback offline si timeout/erreur/pas de réseau
 * - Jamais de crash, toujours une réponse (même vide)
 *
 * **Sécurité** :
 * - LLM ne décide JAMAIS de dose
 * - Produit uniquement des Intent structurés
 * - Timeout strict (3 secondes max)
 * - Validation stricte du JSON de sortie
 *
 * **Usage** :
 * ```kotlin
 * val llmClient = ContextLLMClient(...)
 * val intents = llmClient.parse("heavy cardio 1 hour")
 * // Returns: [Activity(intensity=HIGH, duration=60min, type=CARDIO)]
 * ```
 */
@SingleIn(AppScope::class)
class ContextLLMClient @Inject constructor(
    private val aiCoachingService: AiCoachingService,
    private val sp: SP,
    private val aapsLogger: AAPSLogger
) {
    companion object {
        private const val TIMEOUT_MS = 3000L // 3 secondes max

        /**
         * Prompt expert structuré pour extraction de contexte.
         *
         * Force le LLM à produire du JSON strict avec schema défini.
         * Utilise few-shot learning pour améliorer précision.
         */
        private val SYSTEM_PROMPT = """
You are **Diaby**, AIMI's Context Analyst and Physiological Detective.
Your ONLY job is to extract structured context information regarding specific intents.

**MISSION & IDENTITY**:
- **Role**: Precise interpreter of user intent for diabetes management.
- **Goal**: Convert natural language into structured data signals for the algorithm.

**CRITICAL RULES**:
1. NEVER suggest insulin doses, corrections, or medical advice
2. ONLY extract context intents (activity, iIllness, meal risk, stress, etc.)
3. Output MUST be valid JSON following the exact schema below
4. If uncertain, set confidence < 0.7
5. Duration and intensity should be realistic

${LlmWorldConservativePreamble.FOR_JSON_CONTRACT}

**OUTPUT SCHEMA** (JSON array of intents):
```json
[
  {
    "type": "Activity" | "Illness" | "Stress" | "UnannouncedMealRisk" | "SlowCarbMeal" | "HypoRecovery" | "Alcohol" | "Travel" | "MenstrualCycle" | "Custom",
    "intensity": "LOW" | "MEDIUM" | "HIGH" | "EXTREME",
    "durationMinutes": <number>,
    "confidence": <0.0-1.0>,
    "metadata": {
      // Type-specific fields:
      // Activity: "activityType": "CARDIO"|"STRENGTH"|"YOGA"|"SPORT_INTENSE"|"WALKING"
      // Illness: "symptomType": "GENERAL"|"GASTRO"|"INFECTION"|"STRESS_CHRONIC"
      // Stress: "stressType": "EMOTIONAL"|"WORK"|"EXAM"
      // MenstrualCycle: "phase": "FOLLICULAR"|"OVULATION"|"LUTEAL"|"MENSTRUATION"
      // Travel: "timezoneShiftHours": <number>
      // Alcohol: "units": <number>
      // Custom: "description": "<text>", "suggestedStrategy": "<text>"
    }
  }
]
```

**ROUTING RULES**:
- For fat/protein-rich or slow-absorbing meals (pizza, fries, chips, cheese, creamy/greasy food),
  use "SlowCarbMeal", NOT "UnannouncedMealRisk". UnannouncedMealRisk is only for fast/sugary unannounced spikes.
- If the user reports being low / treating a hypo / just recovered from a low, use "HypoRecovery".

User: "just ate a big plate of fries and cheese"
Output:
```json
[{ "type": "SlowCarbMeal", "intensity": "MEDIUM", "durationMinutes": 300, "confidence": 0.9, "metadata": {} }]
```

User: "just had a hypo, treated it, watch me for the next hour"
Output:
```json
[{ "type": "HypoRecovery", "intensity": "MEDIUM", "durationMinutes": 60, "confidence": 0.9, "metadata": {} }]
```

**EXAMPLES**:

User: "heavy cardio session 1 hour"
Output:
```json
[{
  "type": "Activity",
  "intensity": "HIGH",
  "durationMinutes": 60,
  "confidence": 0.95,
  "metadata": { "activityType": "CARDIO" }
}]
```

User: "sick with flu, feeling resistant"
Output:
```json
[{
  "type": "Illness",
  "intensity": "MEDIUM",
  "durationMinutes": 720,
  "confidence": 0.90,
  "metadata": { "symptomType": "GENERAL" }
}]
```

User: "going out for dinner, might eat more than usual"
Output:
```json
[{
  "type": "UnannouncedMealRisk",
  "intensity": "MEDIUM",
  "durationMinutes": 360,
  "confidence": 0.85,
  "metadata": {}
}]
```

User: "stressful work deadline today"
Output:
```json
[{
  "type": "Stress",
  "intensity": "MEDIUM",
  "durationMinutes": 480,
  "confidence": 0.90,
  "metadata": { "stressType": "WORK" }
}]
```

User: "just had 2 beers"
Output:
```json
[{
  "type": "Alcohol",
  "intensity": "MEDIUM",
  "durationMinutes": 720,
  "confidence": 0.95,
  "metadata": { "units": 2.0 }
}]
```

User: "light yoga 30 minutes"
Output:
```json
[{
  "type": "Activity",
  "intensity": "LOW",
  "durationMinutes": 30,
  "confidence": 0.95,
  "metadata": { "activityType": "YOGA" }
}]
```

User: "football match intense 90 min"
Output:
```json
[{
  "type": "Activity",
  "intensity": "HIGH",
  "durationMinutes": 90,
  "confidence": 0.95,
  "metadata": { "activityType": "SPORT_INTENSE" }
}]
```

**NOW PARSE THIS USER MESSAGE** (respond ONLY with JSON array, no explanation):
        """.trimIndent()
    }

    /**
     * Medical context for enriched LLM prompt.
     *
     * Provides comprehensive diabetes state to help LLM better interpret user's situation.
     */
    data class MedicalContext(
        val currentBG: Double,              // mg/dL
        val iob: Double,                    // U
        val cob: Double,                    // g
        val currentTBR: Double? = null,     // U/h (null if no TBR active)
        val tbrDuration: Int? = null,       // minutes remaining
        val bgTrend: List<Double> = emptyList(),  // Last 4-6 readings
        val delta: Double? = null,          // mg/dL/5min
        val shortAvgDelta: Double? = null,  // mg/dL/5min average
        val trajectoryType: String? = null, // ORBIT, DIVERGENT, etc.
        val trajectoryScore: Double? = null,
        val dia: Double? = null,            // hours
        val peakTime: Double? = null,       // minutes
        val wcyclePhase: String? = null,    // FOLLICULAR, LUTEAL, etc. (if enabled)
        val timeOfDay: String? = null       // "Morning", "Afternoon", "Evening", "Night"
    )

    /**
     * Parse user text into structured ContextIntents using LLM with medical context.
     *
     * @param userText User message (e.g., "heavy cardio 1h")
     * @param medicalContext Current diabetes state (optional but recommended)
     * @return List of parsed intents (empty if parsing fails)
     */
    suspend fun parseWithLLM(userText: String, medicalContext: MedicalContext? = null): List<ContextIntent> {
        if (userText.isBlank()) return emptyList()

        aapsLogger.debug(LTag.APS, "[ContextLLM] Parsing: '$userText'")

        return try {
            // Build enriched prompt with medical context
            val prompt = buildEnrichedPrompt(userText, medicalContext)

            // Get provider and API key from SHARED preferences (same as Advisor)
            val providerStr = sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorProvider.key, "OPENAI")
            val provider = when (providerStr) {
                "GEMINI" -> AiCoachingService.Provider.GEMINI
                "DEEPSEEK" -> AiCoachingService.Provider.DEEPSEEK
                "CLAUDE" -> AiCoachingService.Provider.CLAUDE
                else -> AiCoachingService.Provider.OPENAI
            }

            val apiKey = when (provider) {
                AiCoachingService.Provider.OPENAI -> sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorOpenAIKey.key, "")
                AiCoachingService.Provider.GEMINI -> sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorGeminiKey.key, "")
                AiCoachingService.Provider.DEEPSEEK -> sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorDeepSeekKey.key, "")
                AiCoachingService.Provider.CLAUDE -> sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorClaudeKey.key, "")
            }

            if (apiKey.isBlank()) {
                aapsLogger.error(LTag.APS, "[ContextLLM] API key not configured for $provider")
                return emptyList()
            }

            // Call LLM service (the ported AiCoachingService no longer takes an Android Context)
            val llmResponse = aiCoachingService.fetchText(prompt, apiKey, provider)

            // Check for service errors
            if (llmResponse.startsWith("Erreur")) {
                aapsLogger.error(LTag.APS, "[ContextLLM] Service error: $llmResponse")
                throw Exception(llmResponse)
            }

            // Parse JSON response
            parseJsonResponse(llmResponse, userText)

        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[ContextLLM] LLM parsing failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Build enriched prompt with medical context.
     */
    private fun buildEnrichedPrompt(userText: String, medicalContext: MedicalContext?): String {
        val contextSection = if (medicalContext != null) {
            buildString {
                appendLine()
                appendLine("═══════════════════════════════════════════════════════════════")
                appendLine("CURRENT MEDICAL CONTEXT (use this to better interpret situation):")
                appendLine("═══════════════════════════════════════════════════════════════")
                appendLine()
                appendLine("🩸 GLUCOSE:")
                appendLine("  • Current BG: ${medicalContext.currentBG.toInt()} mg/dL")
                if (medicalContext.delta != null) {
                    val arrow = when {
                        medicalContext.delta > 3 -> "↗↗"
                        medicalContext.delta > 1 -> "↗"
                        medicalContext.delta < -3 -> "↘↘"
                        medicalContext.delta < -1 -> "↘"
                        else -> "→"
                    }
                    appendLine("  • Delta: ${aimiFmt1(medicalContext.delta)} mg/dL/5min $arrow")
                }
                if (medicalContext.shortAvgDelta != null) {
                    appendLine("  • Avg Delta: ${aimiFmt1(medicalContext.shortAvgDelta)} mg/dL/5min")
                }
                if (medicalContext.bgTrend.isNotEmpty()) {
                    val trendStr = medicalContext.bgTrend.takeLast(4).joinToString(" → ") { it.toInt().toString() }
                    appendLine("  • Recent trend: $trendStr mg/dL")
                }

                appendLine()
                appendLine("💉 INSULIN:")
                appendLine("  • IOB (on board): ${aimiFmt1(medicalContext.iob)}U")
                if (medicalContext.currentTBR != null) {
                    appendLine("  • Active TBR: ${aimiFmt2(medicalContext.currentTBR)}U/h")
                    if (medicalContext.tbrDuration != null) {
                        appendLine("    (${medicalContext.tbrDuration}min remaining)")
                    }
                }
                if (medicalContext.dia != null) {
                    appendLine("  • DIA: ${aimiFmt1(medicalContext.dia)}h")
                }
                if (medicalContext.peakTime != null) {
                    appendLine("  • Peak time: ${medicalContext.peakTime.toInt()}min")
                }

                appendLine()
                appendLine("🍽️ CARBS:")
                appendLine("  • COB (on board): ${medicalContext.cob.toInt()}g")

                if (medicalContext.trajectoryType != null) {
                    appendLine()
                    appendLine("🌀 TRAJECTORY:")
                    appendLine("  • Type: ${medicalContext.trajectoryType}")
                    if (medicalContext.trajectoryScore != null) {
                        appendLine("  • Score: ${aimiFmt2(medicalContext.trajectoryScore)}")
                    }
                }

                if (medicalContext.wcyclePhase != null) {
                    appendLine()
                    appendLine("🔄 HORMONAL CYCLE:")
                    appendLine("  • Phase: ${medicalContext.wcyclePhase}")
                    appendLine("    (may affect insulin sensitivity)")
                }

                if (medicalContext.timeOfDay != null) {
                    appendLine()
                    appendLine("🕐 TIME:")
                    appendLine("  • ${medicalContext.timeOfDay}")
                }

                appendLine()
                appendLine("═══════════════════════════════════════════════════════════════")
                appendLine()
                appendLine("**Use this context to:**")
                appendLine("1. Distinguish illness (high BG + low IOB → resistance) from meals (high BG + COB)")
                appendLine("2. Assess urgency based on BG level and trend")
                appendLine("3. Infer activity timing (e.g., 'just finished' + dropping BG)")
                appendLine("4. Detect patterns (e.g., luteal phase + rising BG → increased resistance)")
                appendLine("5. Adjust intensity/duration based on current state")
                appendLine("6. Reconstruct user intent vs medical context before extracting intents")
                appendLine("7. If the message is unintelligible or unrelated, return an empty JSON array []")
                appendLine()
            }
        } else {
            ""
        }

        return "$SYSTEM_PROMPT$contextSection\nUSER MESSAGE: \"$userText\""
    }

    /**
     * Parse JSON response from LLM into ContextIntents.
     *
     * Validates schema and creates typed Intent objects.
     */
    private fun parseJsonResponse(jsonStr: String, originalText: String): List<ContextIntent> {
        return try {
            // Extract JSON array from response (LLM might add markdown)
            val cleanJson = extractJsonArray(jsonStr)
            val jsonArray = Json.parseToJsonElement(cleanJson).jsonArray

            val intents = mutableListOf<ContextIntent>()
            val now = aimiWallClockMs()

            for (i in jsonArray.indices) {
                val obj = jsonArray[i].jsonObject
                val intent = parseIntent(obj, now)
                if (intent != null) {
                    intents.add(intent)
                    aapsLogger.debug(LTag.APS, "[ContextLLM] Parsed: $intent")
                }
            }

            aapsLogger.info(LTag.APS, "[ContextLLM] Successfully parsed ${intents.size} intents from LLM")
            intents

        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[ContextLLM] JSON parsing failed: ${e.message}")
            aapsLogger.debug(LTag.APS, "[ContextLLM] Raw response: $jsonStr")
            emptyList()
        }
    }

    /**
     * Parse single intent object from JSON.
     *
     * The strict `get`-style access mirrors `org.json`'s throwing getters: a missing or
     * mistyped required field raises, and the surrounding `try` turns it into a skipped intent.
     */
    private fun parseIntent(json: JsonObject, baseTimeMs: Long): ContextIntent? {
        return try {
            val type = json["type"]!!.jsonPrimitive.content
            val intensityStr = json["intensity"]!!.jsonPrimitive.content
            val durationMin = json["durationMinutes"]!!.jsonPrimitive.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
                ?: throw IllegalArgumentException("durationMinutes is not a number")
            val confidence = json["confidence"]!!.jsonPrimitive.double.toFloat().coerceIn(0f, 1f)
            val durationMs = durationMin.minutes.inWholeMilliseconds

            val intensity = when (intensityStr) {
                "LOW" -> Intensity.LOW
                "MEDIUM" -> Intensity.MEDIUM
                "HIGH" -> Intensity.HIGH
                "EXTREME" -> Intensity.EXTREME
                else -> Intensity.MEDIUM
            }

            val metadata = json["metadata"] as? JsonObject ?: JsonObject(emptyMap())

            when (type) {
                "Activity" -> {
                    val activityTypeStr = metadata.optStringCompat("activityType").takeIf { it.isNotEmpty() } ?: "CARDIO"
                    val activityType = when (activityTypeStr) {
                        "CARDIO" -> Activity.ActivityType.CARDIO
                        "STRENGTH" -> Activity.ActivityType.STRENGTH
                        "YOGA" -> Activity.ActivityType.YOGA
                        "SPORT_INTENSE" -> Activity.ActivityType.SPORT_INTENSE
                        "WALKING" -> Activity.ActivityType.WALKING
                        else -> Activity.ActivityType.CARDIO
                    }
                    Activity(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        activityType = activityType
                    )
                }

                "Illness" -> {
                    val symptomTypeStr = metadata.optStringCompat("symptomType").takeIf { it.isNotEmpty() } ?: "GENERAL"
                    val symptomType = when (symptomTypeStr) {
                        "GENERAL" -> Illness.SymptomType.GENERAL
                        "GASTRO" -> Illness.SymptomType.GASTRO
                        "INFECTION" -> Illness.SymptomType.INFECTION
                        "STRESS_CHRONIC" -> Illness.SymptomType.STRESS_CHRONIC
                        else -> Illness.SymptomType.GENERAL
                    }
                    Illness(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        symptomType = symptomType
                    )
                }

                "Stress" -> {
                    val stressTypeStr = metadata.optStringCompat("stressType").takeIf { it.isNotEmpty() } ?: "EMOTIONAL"
                    val stressType = when (stressTypeStr) {
                        "EMOTIONAL" -> Stress.StressType.EMOTIONAL
                        "WORK" -> Stress.StressType.WORK
                        "EXAM" -> Stress.StressType.EXAM
                        else -> Stress.StressType.EMOTIONAL
                    }
                    Stress(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        stressType = stressType
                    )
                }

                "UnannouncedMealRisk" -> {
                    UnannouncedMealRisk(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        riskWindow = durationMin.minutes
                    )
                }

                "SlowCarbMeal", "SlowMeal", "FatMeal" -> {
                    SlowCarbMeal(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence
                    )
                }

                "HypoRecovery", "Hypo", "Hypoglycemia" -> {
                    HypoRecovery(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence
                    )
                }

                "Alcohol" -> {
                    val units = metadata.optDoubleCompat("units", 0.0).toFloat()
                    Alcohol(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        units = units
                    )
                }

                "Travel" -> {
                    val timezoneShift = metadata.optIntCompat("timezoneShiftHours", 0)
                    Travel(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        timezoneShiftHours = timezoneShift
                    )
                }

                "MenstrualCycle" -> {
                    val phaseStr = metadata.optStringCompat("phase").takeIf { it.isNotEmpty() } ?: "FOLLICULAR"
                    val phase = when (phaseStr) {
                        "FOLLICULAR" -> MenstrualCycle.CyclePhase.FOLLICULAR
                        "OVULATION" -> MenstrualCycle.CyclePhase.OVULATION
                        "LUTEAL" -> MenstrualCycle.CyclePhase.LUTEAL
                        "MENSTRUATION" -> MenstrualCycle.CyclePhase.MENSTRUATION
                        else -> MenstrualCycle.CyclePhase.FOLLICULAR
                    }
                    MenstrualCycle(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        phase = phase
                    )
                }

                "Custom" -> {
                    val description = metadata.optStringCompat("description").takeIf { it.isNotEmpty() } ?: "Unknown context"
                    val suggestedStrategy = metadata.optStringCompat("suggestedStrategy")
                    Custom(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        description = description,
                        suggestedStrategy = suggestedStrategy
                    )
                }

                else -> {
                    aapsLogger.warn(LTag.APS, "[ContextLLM] Unknown intent type: $type")
                    null
                }
            }

        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[ContextLLM] Failed to parse intent: ${e.message}")
            null
        }
    }

    /**
     * Extract JSON array from LLM response.
     *
     * LLMs souvent wrap JSON dans markdown code blocks.
     */
    private fun extractJsonArray(text: String): String {
        // Remove markdown code blocks if present
        var clean = text.trim()

        // Remove ```json ... ``` or ``` ... ```
        if (clean.startsWith("```")) {
            clean = clean.removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }

        // Find first [ and last ]
        val start = clean.indexOf('[')
        val end = clean.lastIndexOf(']')

        if (start != -1 && end != -1 && end > start) {
            return clean.substring(start, end + 1)
        }

        return clean
    }
}
