package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Offline parser pour contexte utilisateur (fallback sans LLM).
 * 
 * **Design** :
 * - Regex-based pattern matching
 * - Presets UI support
 * - Toujours disponible (pas de réseau requis)
 * - Moins précis que LLM mais robuste
 * 
 * **Usage** :
 * ```kotlin
 * val intents = parser.parse("cardio 1h")
 * // Returns: [Activity(intensity=HIGH, duration=60min, type=CARDIO)]
 * ```
 */
@Singleton
class ContextParser @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        // Activity patterns
        private val CARDIO_PATTERNS = listOf(
            Regex("\\b(cardio|running|run|jogging|cycling|bike|swimming|swim|rowing|elliptical)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(course|vélo|natation|nage|footing|courir|rameur|elliptique|sport|training|séance|entrainement)\\b", RegexOption.IGNORE_CASE)
        )
        
        private val STRENGTH_PATTERNS = listOf(
            Regex("\\b(strength|weight|lifting|gym|musculation|muscu)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(poids|haltères|force)\\b", RegexOption.IGNORE_CASE)
        )
        
        private val YOGA_PATTERNS = listOf(
            Regex("\\b(yoga|stretching|pilates)\\b", RegexOption.IGNORE_CASE)
        )
        
        private val SPORT_PATTERNS = listOf(
            Regex("\\b(football|soccer|tennis|basketball|sport)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(foot|basket|match)\\b", RegexOption.IGNORE_CASE)
        )
        
        private val WALKING_PATTERNS = listOf(
            Regex("\\b(walking|walk|marche)\\b", RegexOption.IGNORE_CASE)
        )
        
        // Illness patterns
        private val ILLNESS_PATTERNS = listOf(
            Regex("\\b(sick|ill|fever|flu|cold|infection|malade|fièvre|grippe|rhume|pain|virus|covid)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(resistant|résistant|douleur|virus|covid)\\b", RegexOption.IGNORE_CASE)
        )
        
        private val GASTRO_PATTERNS = listOf(
            Regex("\\b(gastro|stomach|nausea|vomit|diarrhea|estomac|nausée|diarrhée)\\b", RegexOption.IGNORE_CASE)
        )
        
        // Stress patterns
        private val STRESS_PATTERNS = listOf(
            Regex("\\b(stress|anxious|anxiety|worried|nerveux|anxiété)\\b", RegexOption.IGNORE_CASE)
        )
        
        private val WORK_PATTERNS = listOf(
            Regex("\\b(work|deadline|meeting|travail|réunion|boulot)\\b", RegexOption.IGNORE_CASE)
        )
        
        private val EXAM_PATTERNS = listOf(
            Regex("\\b(exam|test|examen)\\b", RegexOption.IGNORE_CASE)
        )
        
        // Meal patterns
        private val MEAL_PATTERNS = listOf(
            Regex("\\b(eating|meal|dinner|lunch|restaurant|repas|manger|dîner|déjeuner)\\b", RegexOption.IGNORE_CASE),
            Regex("\\b(unannounced|non annoncé|surprise)\\b", RegexOption.IGNORE_CASE)
        )
        
        // Alcohol patterns
        private val ALCOHOL_PATTERNS = listOf(
            Regex("\\b(alcohol|drink|beer|wine|alcool|bière|vin)\\b", RegexOption.IGNORE_CASE)
        )
        
        // Travel patterns
        private val TRAVEL_PATTERNS = listOf(
            Regex("\\b(travel|flight|trip|voyage|vol|avion)\\b", RegexOption.IGNORE_CASE)
        )
        
        // Duration patterns
        private val DURATION_HOUR_PATTERN = Regex("(\\d+)\\s*(?:h|hour|hours|heure|heures)", RegexOption.IGNORE_CASE)
        private val DURATION_MIN_PATTERN = Regex("(\\d+)\\s*(?:m|min|minute|minutes?)", RegexOption.IGNORE_CASE)
        
        // Intensity patterns
        private val INTENSITY_HIGH_PATTERNS = listOf(
            Regex("\\b(heavy|intense|hard|high|forte?|intense)\\b", RegexOption.IGNORE_CASE)
        )
        
        private val INTENSITY_LOW_PATTERNS = listOf(
            Regex("\\b(light|easy|low|légère?|facile|doux)\\b", RegexOption.IGNORE_CASE)
        )
        
        // Alcohol units pattern
        private val ALCOHOL_UNITS_PATTERN = Regex("(\\d+)\\s*(?:beer|beers|glass|glasses|verre|verres|bière|bières)", RegexOption.IGNORE_CASE)
    }
    
    /**
     * Parse user text into ContextIntents (offline mode).
     * 
     * @param text User input text
     * @return List of parsed intents (may be empty if no match)
     */
    fun parse(text: String): List<ContextIntent> {
        if (text.isBlank()) return emptyList()
        
        aapsLogger.debug(LTag.APS, "[ContextParser] Parsing offline: '$text'")
        
        val intents = mutableListOf<ContextIntent>()
        val now = System.currentTimeMillis()
        
        // Try to match each category
        matchActivity(text, now)?.let { intents.add(it) }
        matchIllness(text, now)?.let { intents.add(it) }
        matchStress(text, now)?.let { intents.add(it) }
        matchMealRisk(text, now)?.let { intents.add(it) }
        matchAlcohol(text, now)?.let { intents.add(it) }
        matchTravel(text, now)?.let { intents.add(it) }
        
        if (intents.isEmpty()) {
            aapsLogger.warn(LTag.APS, "[ContextParser] No pattern matched: '$text'")
        } else {
            aapsLogger.info(LTag.APS, "[ContextParser] Parsed ${intents.size} intent(s) offline")
        }
        
        return intents
    }
    
    /**
     * Parse from preset (UI button).
     * 
     * @param preset Preset definition
     * @return Single intent
     */
    fun parsePreset(preset: ContextPreset): ContextIntent {
        val now = System.currentTimeMillis()
        
        return when (preset.type) {
            PresetType.CARDIO -> Activity(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f,
                activityType = Activity.ActivityType.CARDIO
            )
            
            PresetType.STRENGTH -> Activity(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f,
                activityType = Activity.ActivityType.STRENGTH
            )
            
            PresetType.YOGA -> Activity(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f,
                activityType = Activity.ActivityType.YOGA
            )
            
            PresetType.SPORT -> Activity(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f,
                activityType = Activity.ActivityType.SPORT_INTENSE
            )
            
            PresetType.WALKING -> Activity(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f,
                activityType = Activity.ActivityType.WALKING
            )
            
            PresetType.SICK -> Illness(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f,
                symptomType = Illness.SymptomType.GENERAL
            )
            
            PresetType.STRESS -> Stress(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f,
                stressType = Stress.StressType.EMOTIONAL
            )
            
            PresetType.MEAL_RISK -> UnannouncedMealRisk(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f
            )
            
            PresetType.ALCOHOL -> Alcohol(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f,
                units = 2f  // Default 2 units
            )
            
            PresetType.TRAVEL -> Travel(
                startTimeMs = now,
                durationMs = preset.defaultDuration.inWholeMilliseconds,
                intensity = preset.defaultIntensity,
                confidence = 1.0f,
                timezoneShiftHours = 0
            )
        }
    }
    
    // Private matchers
    
    private fun matchActivity(text: String, baseTimeMs: Long): Activity? {
        val activityType = when {
            CARDIO_PATTERNS.any { it.containsMatchIn(text) } -> Activity.ActivityType.CARDIO
            STRENGTH_PATTERNS.any { it.containsMatchIn(text) } -> Activity.ActivityType.STRENGTH
            YOGA_PATTERNS.any { it.containsMatchIn(text) } -> Activity.ActivityType.YOGA
            SPORT_PATTERNS.any { it.containsMatchIn(text) } -> Activity.ActivityType.SPORT_INTENSE
            WALKING_PATTERNS.any { it.containsMatchIn(text) } -> Activity.ActivityType.WALKING
            else -> return null
        }
        
        val duration = extractDuration(text) ?: 60.minutes
        val intensity = extractIntensity(text) ?: when (activityType) {
            Activity.ActivityType.CARDIO, Activity.ActivityType.SPORT_INTENSE -> Intensity.HIGH
            Activity.ActivityType.STRENGTH -> Intensity.MEDIUM
            Activity.ActivityType.YOGA, Activity.ActivityType.WALKING -> Intensity.LOW
        }
        
        return Activity(
            startTimeMs = baseTimeMs,
            durationMs = duration.inWholeMilliseconds,
            intensity = intensity,
            confidence = 0.85f,  // Lower confidence for offline parsing
            activityType = activityType
        )
    }
    
    private fun matchIllness(text: String, baseTimeMs: Long): Illness? {
        val symptomType = when {
            GASTRO_PATTERNS.any { it.containsMatchIn(text) } -> Illness.SymptomType.GASTRO
            ILLNESS_PATTERNS.any { it.containsMatchIn(text) } -> Illness.SymptomType.GENERAL
            else -> return null
        }
        
        val duration = extractDuration(text) ?: 12.hours  // Default 12h for illness
        val intensity = extractIntensity(text) ?: Intensity.MEDIUM
        
        return Illness(
            startTimeMs = baseTimeMs,
            durationMs = duration.inWholeMilliseconds,
            intensity = intensity,
            confidence = 0.80f,
            symptomType = symptomType
        )
    }
    
    private fun matchStress(text: String, baseTimeMs: Long): Stress? {
        val stressType = when {
            EXAM_PATTERNS.any { it.containsMatchIn(text) } -> Stress.StressType.EXAM
            WORK_PATTERNS.any { it.containsMatchIn(text) } -> Stress.StressType.WORK
            STRESS_PATTERNS.any { it.containsMatchIn(text) } -> Stress.StressType.EMOTIONAL
            else -> return null
        }
        
        val duration = extractDuration(text) ?: 8.hours  // Default 8h for stress
        val intensity = extractIntensity(text) ?: Intensity.MEDIUM
        
        return Stress(
            startTimeMs = baseTimeMs,
            durationMs = duration.inWholeMilliseconds,
            intensity = intensity,
            confidence = 0.80f,
            stressType = stressType
        )
    }
    
    private fun matchMealRisk(text: String, baseTimeMs: Long): UnannouncedMealRisk? {
        if (!MEAL_PATTERNS.any { it.containsMatchIn(text) }) return null
        
        val duration = extractDuration(text) ?: 6.hours  // Default 6h window
        val intensity = extractIntensity(text) ?: Intensity.MEDIUM
        
        return UnannouncedMealRisk(
            startTimeMs = baseTimeMs,
            durationMs = duration.inWholeMilliseconds,
            intensity = intensity,
            confidence = 0.75f,
            riskWindow = duration
        )
    }
    
    private fun matchAlcohol(text: String, baseTimeMs: Long): Alcohol? {
        if (!ALCOHOL_PATTERNS.any { it.containsMatchIn(text) }) return null
        
        val units = ALCOHOL_UNITS_PATTERN.find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 2f
        val intensity = when {
            units > 3f -> Intensity.HIGH
            units > 1.5f -> Intensity.MEDIUM
            else -> Intensity.LOW
        }
        
        return Alcohol(
            startTimeMs = baseTimeMs,
            durationMs = 12.hours.inWholeMilliseconds,  // Fixed 12h for alcohol
            intensity = intensity,
            confidence = 0.90f,
            units = units
        )
    }
    
    private fun matchTravel(text: String, baseTimeMs: Long): Travel? {
        if (!TRAVEL_PATTERNS.any { it.containsMatchIn(text) }) return null
        
        val duration = extractDuration(text) ?: 24.hours  // Default 1 day
        val intensity = extractIntensity(text) ?: Intensity.MEDIUM
        
        return Travel(
            startTimeMs = baseTimeMs,
            durationMs = duration.inWholeMilliseconds,
            intensity = intensity,
            confidence = 0.70f,
            timezoneShiftHours = 0  // Can't extract timezone from text easily
        )
    }
    
    // Helpers
    
    private fun extractDuration(text: String): kotlin.time.Duration? {
        val hoursMatch = DURATION_HOUR_PATTERN.find(text)
        val minsMatch = DURATION_MIN_PATTERN.find(text)
        
        val hours = hoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = minsMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        if (hours == 0 && mins == 0) return null
        
        return (hours.hours + mins.minutes)
    }
    
    private fun extractIntensity(text: String): Intensity? {
        return when {
            INTENSITY_HIGH_PATTERNS.any { it.containsMatchIn(text) } -> Intensity.HIGH
            INTENSITY_LOW_PATTERNS.any { it.containsMatchIn(text) } -> Intensity.LOW
            else -> null
        }
    }
}

/**
 * Enum for UI presets.
 */
enum class PresetType {
    CARDIO,
    STRENGTH,
    YOGA,
    SPORT,
    WALKING,
    SICK,
    STRESS,
    MEAL_RISK,
    ALCOHOL,
    TRAVEL
}

/**
 * Preset definition for UI buttons.
 */
data class ContextPreset(
    val type: PresetType,
    val displayName: String,
    val icon: String,  // Emoji
    val defaultDuration: kotlin.time.Duration,
    val defaultIntensity: Intensity
) {
    companion object {
        val ALL_PRESETS = listOf(
            ContextPreset(PresetType.CARDIO, "Cardio", "🏃", 60.minutes, Intensity.HIGH),
            ContextPreset(PresetType.STRENGTH, "Musculation", "💪", 60.minutes, Intensity.MEDIUM),
            ContextPreset(PresetType.YOGA, "Yoga", "🧘", 45.minutes, Intensity.LOW),
            ContextPreset(PresetType.SPORT, "Sport", "⚽", 90.minutes, Intensity.HIGH),
            ContextPreset(PresetType.WALKING, "Marche", "🚶", 30.minutes, Intensity.LOW),
            ContextPreset(PresetType.SICK, "Malade", "🤒", 12.hours, Intensity.MEDIUM),
            ContextPreset(PresetType.STRESS, "Stress", "😰", 8.hours, Intensity.MEDIUM),
            ContextPreset(PresetType.MEAL_RISK, "Repas non annoncé", "🍕", 6.hours, Intensity.MEDIUM),
            ContextPreset(PresetType.ALCOHOL, "Alcool", "🍷", 12.hours, Intensity.MEDIUM),
            ContextPreset(PresetType.TRAVEL, "Voyage", "✈️", 24.hours, Intensity.MEDIUM)
        )
    }
}
