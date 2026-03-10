package app.aaps.plugins.aps.openAPSAIMI.context

import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Représente une déclaration de contexte de l'utilisateur.
 * 
 * Les intents sont structurés, typés, et ont une durée de vie.
 * Ils sont créés soit par parsing LLM, soit par UI presets, soit parsing offline.
 * 
 * Design Pattern: Value Object immutable avec validation
 */
sealed class ContextIntent {
    abstract val startTimeMs: Long
    abstract val durationMs: Long
    abstract val intensity: Intensity
    abstract val confidence: Float  // 0.0..1.0 (confiance du parsing)
    
    val endTimeMs: Long get() = startTimeMs + durationMs
    
    fun isActiveAt(timestampMs: Long): Boolean {
        return timestampMs in startTimeMs..endTimeMs
    }
    
    enum class Intensity {
        LOW,      // Impact faible
        MEDIUM,   // Impact modéré
        HIGH,     // Impact fort
        EXTREME   // Impact très fort (rare)
    }
    
    /**
     * Activité physique (cardio, musculation, yoga...)
     * 
     * Impact: Augmente sensibilité à l'insuline, risque hypo post-exercice
     * Stratégie: Préférer basal, limiter SMB, augmenter interval
     */
    data class Activity(
        override val startTimeMs: Long,
        override val durationMs: Long,
        override val intensity: Intensity,
        override val confidence: Float = 1.0f,
        val activityType: ActivityType = ActivityType.CARDIO,
        val expectedDurationPostEffect: Duration = 4.hours  // Effet résiduel
    ) : ContextIntent() {
        
        enum class ActivityType {
            CARDIO,        // Course, vélo, natation
            STRENGTH,      // Musculation
            YOGA,          // Yoga, stretching
            SPORT_INTENSE, // Football, tennis
            WALKING        // Marche légère
        }
    }
    
    /**
     * Maladie (rhume, grippe, infection...)
     * 
     * Impact: Résistance à l'insuline, inflammation, stress
     * Stratégie: Accepter TBR plus élevés, limiter agressivité si BG monte rapidement
     */
    data class Illness(
        override val startTimeMs: Long,
        override val durationMs: Long,
        override val intensity: Intensity,
        override val confidence: Float = 1.0f,
        val symptomType: SymptomType = SymptomType.GENERAL
    ) : ContextIntent() {
        
        enum class SymptomType {
            GENERAL,          // Rhume, grippe
            GASTRO,           // Troubles digestifs
            INFECTION,        // Infection (antibiotiques)
            STRESS_CHRONIC   // Stress chronique
        }
    }
    
    /**
     * Repas non annoncé ou risque de snacking
     * 
     * Impact: Pics glycémiques imprévisibles
     * Stratégie: Rester réactif (SMB OK), mais avec safety margins
     */
    data class UnannouncedMealRisk(
        override val startTimeMs: Long,
        override val durationMs: Long,
        override val intensity: Intensity,
        override val confidence: Float = 1.0f,
        val riskWindow: Duration = 6.hours
    ) : ContextIntent()
    
    /**
     * Stress émotionnel ou mental
     * 
     * Impact: Augmente résistance, cortisol élevé
     * Stratégie: Similaire à Illness mais moins marqué
     */
    data class Stress(
        override val startTimeMs: Long,
        override val durationMs: Long,
        override val intensity: Intensity,
        override val confidence: Float = 1.0f,
        val stressType: StressType = StressType.EMOTIONAL
    ) : ContextIntent() {
        
        enum class StressType {
            EMOTIONAL,  // Stress émotionnel
            WORK,       // Travail intense
            EXAM        // Examen, pression
        }
    }
    
    /**
     * Cycle menstruel (pour les femmes)
     * 
     * Impact: Résistance variable selon phase
     * Stratégie: Ajuster selon phase (lutéale = plus résistance)
     */
    data class MenstrualCycle(
        override val startTimeMs: Long,
        override val durationMs: Long,
        override val intensity: Intensity,
        override val confidence: Float = 1.0f,
        val phase: CyclePhase
    ) : ContextIntent() {
        
        enum class CyclePhase {
            FOLLICULAR,   // Jours 1-14 (sensibilité normale)
            OVULATION,    // Jour 14 (pic sensibilité)
            LUTEAL,       // Jours 15-28 (résistance augmente)
            MENSTRUATION  // Jours 1-5 (variable)
        }
    }
    
    /**
     * Voyage / décalage horaire
     * 
     * Impact: Perturbation rythme circadien, stress
     * Stratégie: Mode prudent, observation
     */
    data class Travel(
        override val startTimeMs: Long,
        override val durationMs: Long,
        override val intensity: Intensity,
        override val confidence: Float = 1.0f,
        val timezoneShiftHours: Int = 0
    ) : ContextIntent()
    
    /**
     * Alcool consommé
     * 
     * Impact: Hypoglycémie retardée (4-12h), sensibilité accrue
     * Stratégie: Limiter SMB, surveillance prolongée
     */
    data class Alcohol(
        override val startTimeMs: Long,
        override val durationMs: Long = 12.hours.inWholeMilliseconds,
        override val intensity: Intensity,
        override val confidence: Float = 1.0f,
        val units: Float = 0f  // Unités d'alcool
    ) : ContextIntent()
    
    /**
     * Intent custom / générique
     * 
     * Pour cas non couverts ou parsing LLM incertain
     */
    data class Custom(
        override val startTimeMs: Long,
        override val durationMs: Long,
        override val intensity: Intensity,
        override val confidence: Float,
        val description: String,
        val suggestedStrategy: String = ""
    ) : ContextIntent()
}

/**
 * Snapshot du contexte à un instant T.
 * 
 * Aggrege tous les intents actifs et fournit un état simplifié
 * pour le ContextInfluenceEngine.
 * 
 * Design: Value Object immutable, calculé à chaque tick
 */
data class ContextSnapshot(
    val timestampMs: Long,
    val activeIntents: List<ContextIntent>,
    
    // Flags agrégés (au moins 1 intent actif)
    val hasActivity: Boolean,
    val hasIllness: Boolean,
    val hasMealRisk: Boolean,
    val hasStress: Boolean,
    val hasAlcohol: Boolean,
    
    // Intensités max par catégorie
    val activityIntensity: ContextIntent.Intensity?,
    val illnessIntensity: ContextIntent.Intensity?,
    val stressIntensity: ContextIntent.Intensity?,
    val alcoholIntensity: ContextIntent.Intensity?,
    
    // Metadata
    val intentCount: Int = activeIntents.size,
    val avgConfidence: Float = if (activeIntents.isEmpty()) 1.0f 
                                else activeIntents.map { it.confidence }.average().toFloat()
) {
    companion object {
        fun empty(timestampMs: Long) = ContextSnapshot(
            timestampMs = timestampMs,
            activeIntents = emptyList(),
            hasActivity = false,
            hasIllness = false,
            hasMealRisk = false,
            hasStress = false,
            hasAlcohol = false,
            activityIntensity = null,
            illnessIntensity = null,
            stressIntensity = null,
            alcoholIntensity = null
        )
        
        fun from(timestampMs: Long, allIntents: List<ContextIntent>): ContextSnapshot {
            val active = allIntents.filter { it.isActiveAt(timestampMs) }
            
            val activities = active.filterIsInstance<ContextIntent.Activity>()
            val illnesses = active.filterIsInstance<ContextIntent.Illness>()
            val stresses = active.filterIsInstance<ContextIntent.Stress>()
            val alcohols = active.filterIsInstance<ContextIntent.Alcohol>()
            val mealRisks = active.filterIsInstance<ContextIntent.UnannouncedMealRisk>()
            
            return ContextSnapshot(
                timestampMs = timestampMs,
                activeIntents = active,
                hasActivity = activities.isNotEmpty(),
                hasIllness = illnesses.isNotEmpty(),
                hasStress = stresses.isNotEmpty(),
                hasAlcohol = alcohols.isNotEmpty(),
                hasMealRisk = mealRisks.isNotEmpty(),
                activityIntensity = activities.maxByOrNull { it.intensity }?.intensity,
                illnessIntensity = illnesses.maxByOrNull { it.intensity }?.intensity,
                stressIntensity = stresses.maxByOrNull { it.intensity }?.intensity,
                alcoholIntensity = alcohols.maxByOrNull { it.intensity }?.intensity
            )
        }
    }
}
