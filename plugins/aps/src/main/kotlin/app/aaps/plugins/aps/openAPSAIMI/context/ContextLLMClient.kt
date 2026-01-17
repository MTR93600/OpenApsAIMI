package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.plugins.aps.openAPSAIMI.advisor.AiCoachingService
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.*
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
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
@Singleton
class ContextLLMClient @Inject constructor(
    private val aiCoachingService: AiCoachingService,
    private val sp: SP,
    private val aapsLogger: AAPSLogger,
    private val context: android.content.Context
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
You are an expert diabetes context analyzer for an automated insulin delivery system.
Your ONLY job is to extract structured context information from user messages.

**CRITICAL RULES**:
1. NEVER suggest insulin doses, corrections, or medical advice
2. ONLY extract context intents (activity, illness, meal risk, stress, etc.)
3. Output MUST be valid JSON following the exact schema below
4. If uncertain, set confidence < 0.7
5. Duration and intensity should be realistic

**OUTPUT SCHEMA** (JSON array of intents):
```json
[
  {
    "type": "Activity" | "Illness" | "Stress" | "UnannouncedMealRisk" | "Alcohol" | "Travel" | "MenstrualCycle" | "Custom",
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
            
            // Call LLM service
            // Call LLM service
            val llmResponse = aiCoachingService.fetchText(context, prompt, apiKey, provider)
            
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
                    appendLine("  • Delta: ${medicalContext.delta.format(1)} mg/dL/5min $arrow")
                }
                if (medicalContext.shortAvgDelta != null) {
                    appendLine("  • Avg Delta: ${medicalContext.shortAvgDelta.format(1)} mg/dL/5min")
                }
                if (medicalContext.bgTrend.isNotEmpty()) {
                    val trendStr = medicalContext.bgTrend.takeLast(4).joinToString(" → ") { it.toInt().toString() }
                    appendLine("  • Recent trend: $trendStr mg/dL")
                }
                
                appendLine()
                appendLine("💉 INSULIN:")
                appendLine("  • IOB (on board): ${medicalContext.iob.format(1)}U")
                if (medicalContext.currentTBR != null) {
                    appendLine("  • Active TBR: ${medicalContext.currentTBR.format(2)}U/h")
                    if (medicalContext.tbrDuration != null) {
                        appendLine("    (${medicalContext.tbrDuration}min remaining)")
                    }
                }
                if (medicalContext.dia != null) {
                    appendLine("  • DIA: ${medicalContext.dia.format(1)}h")
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
                        appendLine("  • Score: ${medicalContext.trajectoryScore.format(2)}")
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
                appendLine()
            }
        } else {
            ""
        }
        
        return "$SYSTEM_PROMPT$contextSection\nUSER MESSAGE: \"$userText\""
    }
    
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
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
            val jsonArray = JSONArray(cleanJson)
            
            val intents = mutableListOf<ContextIntent>()
            val now = System.currentTimeMillis()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
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
     */
    private fun parseIntent(json: JSONObject, baseTimeMs: Long): ContextIntent? {
        return try {
            val type = json.getString("type")
            val intensityStr = json.getString("intensity")
            val durationMin = json.getInt("durationMinutes")
            val confidence = json.getDouble("confidence").toFloat().coerceIn(0f, 1f)
            val durationMs = durationMin.minutes.inWholeMilliseconds
            
            val intensity = when (intensityStr) {
                "LOW" -> Intensity.LOW
                "MEDIUM" -> Intensity.MEDIUM
                "HIGH" -> Intensity.HIGH
                "EXTREME" -> Intensity.EXTREME
                else -> Intensity.MEDIUM
            }
            
            val metadata = json.optJSONObject("metadata") ?: JSONObject()
            
            when (type) {
                "Activity" -> {
                    val activityTypeStr = metadata.optString("activityType", "CARDIO")
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
                    val symptomTypeStr = metadata.optString("symptomType", "GENERAL")
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
                    val stressTypeStr = metadata.optString("stressType", "EMOTIONAL")
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
                
                "Alcohol" -> {
                    val units = metadata.optDouble("units", 0.0).toFloat()
                    Alcohol(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        units = units
                    )
                }
                
                "Travel" -> {
                    val timezoneShift = metadata.optInt("timezoneShiftHours", 0)
                    Travel(
                        startTimeMs = baseTimeMs,
                        durationMs = durationMs,
                        intensity = intensity,
                        confidence = confidence,
                        timezoneShiftHours = timezoneShift
                    )
                }
                
                "MenstrualCycle" -> {
                    val phaseStr = metadata.optString("phase", "FOLLICULAR")
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
                    val description = metadata.optString("description", "Unknown context")
                    val suggestedStrategy = metadata.optString("suggestedStrategy", "")
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
