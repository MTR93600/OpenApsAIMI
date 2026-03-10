package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Context Influence Engine - Transform contexte utilisateur en modulations sûres.
 * 
 * **Responsabilité** :
 * - Analyser ContextSnapshot
 * - Produire modulations bornées (smbFactor, interval, preferBasal)
 * - Composer influences de multiples intents
 * - Garantir sécurité (jamais au-delà des limites)
 * 
 * **Design Principles** :
 * - Conservative by default
 * - Conflicts → Choose safer option
 * - Medical safety > User convenience
 * - Transparent reasoning
 * 
 * **Usage** :
 * ```kotlin
 * val snapshot = contextManager.getSnapshot(now)
 * val influence = engine.computeInfluence(snapshot, currentBG, iob, cob)
 * 
 * // Use influence
 * finalSmb = coreSmb * influence.smbFactorClamp
 * ```
 */
@Singleton
class ContextInfluenceEngine @Inject constructor(
    private val aapsLogger: AAPSLogger
) {
    
    /**
     * Influence de contexte sur les décisions insulin.
     * 
     * **Bounds** :
     * - smbFactorClamp: [0.50, 1.10] (max ±50% down, +10% up)
     * - extraIntervalMin: [0, 10] minutes
     * - preferBasal: Boolean (guide stratégie)
     */
    data class ContextInfluence(
        val smbFactorClamp: Float,          // Multiplicateur SMB (0.50..1.10)
        val extraIntervalMin: Int,          // Minutes à ajouter à l'interval (0..10)
        val preferBasal: Boolean,           // Préférer TBR vs SMB
        val reasoningSteps: List<String>    // Explications pour logs
    ) {
        init {
            require(smbFactorClamp in 0.50f..1.10f) {
                "smbFactorClamp must be [0.50, 1.10], got $smbFactorClamp"
            }
            require(extraIntervalMin in 0..10) {
                "extraIntervalMin must be [0, 10], got $extraIntervalMin"
            }
        }
        
        companion object {
            fun neutral() = ContextInfluence(
                smbFactorClamp = 1.0f,
                extraIntervalMin = 0,
                preferBasal = false,
                reasoningSteps = listOf("No active context")
            )
        }
    }
    
    /**
     * Compute influence from context snapshot.
     * 
     * @param snapshot Active intents at current time
     * @param currentBG Current blood glucose (mg/dL)
     * @param iob Insulin on board (U)
     * @param cob Carbs on board (g)
     * @param mode Context mode (CONSERVATIVE/BALANCED/AGGRESSIVE)
     * @return Computed influence with reasoning
     */
    fun computeInfluence(
        snapshot: ContextSnapshot,
        currentBG: Double,
        iob: Double,
        cob: Double,
        mode: ContextMode = ContextMode.BALANCED
    ): ContextInfluence {
        
        if (snapshot.intentCount == 0) {
            return ContextInfluence.neutral()
        }
        
        aapsLogger.debug(LTag.APS, "[ContextInfluence] Computing for ${snapshot.intentCount} intent(s)")
        
        val reasoning = mutableListOf<String>()
        var smbFactor = 1.0f
        var extraInterval = 0
        var preferBasal = false
        
        // Process each intent type
        
        // Activity (highest priority for safety - hypo risk)
        if (snapshot.hasActivity) {
            val activityInfluence = processActivity(
                snapshot.activeIntents.filterIsInstance<Activity>(),
                currentBG,
                iob,
                mode,
                reasoning
            )
            smbFactor *= activityInfluence.smbFactor
            extraInterval = maxOf(extraInterval, activityInfluence.extraInterval)
            preferBasal = preferBasal || activityInfluence.preferBasal
        }
        
        // Illness (resistance - may need more insulin)
        if (snapshot.hasIllness) {
            val illnessInfluence = processIllness(
                snapshot.activeIntents.filterIsInstance<Illness>(),
                currentBG,
                iob,
                mode,
                reasoning
            )
            smbFactor *= illnessInfluence.smbFactor
            extraInterval = maxOf(extraInterval, illnessInfluence.extraInterval)
        }
        
        // Stress (similar to illness but less pronounced)
        if (snapshot.hasStress) {
            val stressInfluence = processStress(
                snapshot.activeIntents.filterIsInstance<Stress>(),
                currentBG,
                mode,
                reasoning
            )
            smbFactor *= stressInfluence.smbFactor
            extraInterval = maxOf(extraInterval, stressInfluence.extraInterval)
        }
        
        // Alcohol (hypo risk - very conservative)
        if (snapshot.hasAlcohol) {
            val alcoholInfluence = processAlcohol(
                snapshot.activeIntents.filterIsInstance<Alcohol>(),
                currentBG,
                iob,
                mode,
                reasoning
            )
            smbFactor *= alcoholInfluence.smbFactor
            extraInterval = maxOf(extraInterval, alcoholInfluence.extraInterval)
            preferBasal = preferBasal || alcoholInfluence.preferBasal
        }
        
        // Meal Risk (be reactive but careful)
        if (snapshot.hasMealRisk) {
            val mealInfluence = processMealRisk(
                snapshot.activeIntents.filterIsInstance<UnannouncedMealRisk>(),
                currentBG,
                cob,
                mode,
                reasoning
            )
            // Meal risk: allow SMB but with margins
            // Don't reduce smbFactor, just adjust interval
            extraInterval = maxOf(extraInterval, mealInfluence.extraInterval)
        }
        
        // Clamp to safe bounds
        val clampedSmb = smbFactor.coerceIn(0.50f, 1.10f)
        val clampedInterval = extraInterval.coerceIn(0, 10)
        
        if (clampedSmb != smbFactor) {
            reasoning.add("SMB factor clamped: $smbFactor → $clampedSmb (safety bounds)")
        }
        
        aapsLogger.info(LTag.APS, "[ContextInfluence] Result: smb=$clampedSmb interval=+${clampedInterval}min preferBasal=$preferBasal")
        
        return ContextInfluence(
            smbFactorClamp = clampedSmb,
            extraIntervalMin = clampedInterval,
            preferBasal = preferBasal,
            reasoningSteps = reasoning
        )
    }
    
    // Private processors for each intent type
    
    private data class IntentInfluence(
        val smbFactor: Float,
        val extraInterval: Int,
        val preferBasal: Boolean
    )
    
    private fun processActivity(
        activities: List<Activity>,
        currentBG: Double,
        iob: Double,
        mode: ContextMode,
        reasoning: MutableList<String>
    ): IntentInfluence {
        val maxIntensity = activities.maxByOrNull { it.intensity }?.intensity ?: return IntentInfluence(1.0f, 0, false)
        
        // Activity → High insulin sensitivity → Risk hypo
        // Strategy: Reduce SMB, increase interval, prefer basal
        
        val (smbFactor, intervalAdd, preferBasal) = when (maxIntensity) {
            Intensity.EXTREME -> Triple(0.60f, 8, true)   // -40% SMB, +8min
            Intensity.HIGH -> Triple(0.75f, 5, true)       // -25% SMB, +5min
            Intensity.MEDIUM -> Triple(0.85f, 3, true)     // -15% SMB, +3min
            Intensity.LOW -> Triple(0.92f, 1, false)       // -8% SMB, +1min
        }
        
        // Extra caution if BG already low or high IOB
        val adjustedSmb = when {
            currentBG < 90 -> (smbFactor * 0.85f).coerceAtLeast(0.50f)  // Extra -15%
            currentBG < 110 && iob > 2.0 -> (smbFactor * 0.90f).coerceAtLeast(0.50f)  // Extra -10%
            else -> smbFactor
        }
        
        // Mode adjustment
        val modeSmb = when (mode) {
            ContextMode.CONSERVATIVE -> (adjustedSmb * 0.95f).coerceAtLeast(0.50f)
            ContextMode.BALANCED -> adjustedSmb
            ContextMode.AGGRESSIVE -> (adjustedSmb / 0.95f).coerceAtMost(1.10f)
        }
        
        reasoning.add("Activity ${maxIntensity.name} → SMB×${modeSmb.format(2)} +${intervalAdd}min preferBasal=$preferBasal")
        
        return IntentInfluence(modeSmb, intervalAdd, preferBasal)
    }
    
    private fun processIllness(
        illnesses: List<Illness>,
        currentBG: Double,
        iob: Double,
mode: ContextMode,
        reasoning: MutableList<String>
    ): IntentInfluence {
        val maxIntensity = illnesses.maxByOrNull { it.intensity }?.intensity ?: return IntentInfluence(1.0f, 0, false)
        
        // Illness → Insulin resistance
        // Strategy: Allow more aggressive if BG rising, but careful if BG stable/low
        
        val (smbFactor, intervalAdd) = when {
            currentBG > 160 && maxIntensity >= Intensity.MEDIUM -> {
                // High BG + illness → Allow normal/slightly more
                Pair(1.05f, 1)  // +5% SMB OK, slight interval increase for safety
            }
            currentBG > 130 -> {
                // Medium BG → Neutral
                Pair(1.0f, 2)
            }
            else -> {
                // Low/normal BG → Conservative (illness can also cause unpredictable lows)
                Pair(0.95f, 3)  // -5% SMB, +3min
            }
        }
        
        reasoning.add("Illness ${maxIntensity.name} BG=${currentBG.toInt()} → SMB×${smbFactor.format(2)} +${intervalAdd}min")
        
        return IntentInfluence(smbFactor, intervalAdd, false)
    }
    
    private fun processStress(
        stresses: List<Stress>,
        currentBG: Double,
        mode: ContextMode,
        reasoning: MutableList<String>
    ): IntentInfluence {
        val maxIntensity = stresses.maxByOrNull { it.intensity }?.intensity ?: return IntentInfluence(1.0f, 0, false)
        
        // Stress → Mild resistance (cortisol)
        // Less pronounced than illness
        
        val (smbFactor, intervalAdd) = when (maxIntensity) {
            Intensity.EXTREME, Intensity.HIGH -> {
                if (currentBG > 150) Pair(1.03f, 1) else Pair(0.98f, 2)
            }
            Intensity.MEDIUM -> Pair(0.98f, 1)
            Intensity.LOW -> Pair(0.99f, 0)
        }
        
        reasoning.add("Stress ${maxIntensity.name} → SMB×${smbFactor.format(2)} +${intervalAdd}min")
        
        return IntentInfluence(smbFactor, intervalAdd, false)
    }
    
    private fun processAlcohol(
        alcohols: List<Alcohol>,
        currentBG: Double,
        iob: Double,
        mode: ContextMode,
        reasoning: MutableList<String>
    ): IntentInfluence {
        val maxIntensity = alcohols.maxByOrNull { it.intensity }?.intensity ?: return IntentInfluence(1.0f, 0, false)
        
        // Alcohol → High hypo risk (especially delayed)
        // Strategy: VERY conservative, prefer basal
        
        val (smbFactor, intervalAdd, preferBasal) = when (maxIntensity) {
            Intensity.EXTREME -> Triple(0.50f, 10, true)   // -50% SMB, +10min (max)
            Intensity.HIGH -> Triple(0.65f, 7, true)       // -35% SMB, +7min
            Intensity.MEDIUM -> Triple(0.75f, 5, true)     // -25% SMB, +5min
            Intensity.LOW -> Triple(0.85f, 3, true)        // -15% SMB, +3min
        }
        
        // Extra caution if IOB high or BG lowish
        val adjustedSmb = when {
            iob > 3.0 -> (smbFactor * 0.90f).coerceAtLeast(0.50f)
            currentBG < 110 -> (smbFactor * 0.85f).coerceAtLeast(0.50f)
            else -> smbFactor
        }
        
        reasoning.add("Alcohol ${maxIntensity.name} IOB=${iob.format(1)}U → SMB×${adjustedSmb.format(2)} +${intervalAdd}min preferBasal=$preferBasal")
        
        return IntentInfluence(adjustedSmb, intervalAdd, preferBasal)
    }
    
    private fun processMealRisk(
        mealRisks: List<UnannouncedMealRisk>,
        currentBG: Double,
        cob: Double,
        mode: ContextMode,
        reasoning: MutableList<String>
    ): IntentInfluence {
        val maxIntensity = mealRisks.maxByOrNull { it.intensity }?.intensity ?: return IntentInfluence(1.0f, 0, false)
        
        // Meal Risk → Stay reactive but careful
        // Don't reduce SMB (need to catch spikes)
        // But increase interval for safety margin
        
        val intervalAdd = when (maxIntensity) {
            Intensity.EXTREME, Intensity.HIGH -> 4  // +4min
            Intensity.MEDIUM -> 2                    // +2min
            Intensity.LOW -> 1                       // +1min
        }
        
        reasoning.add("MealRisk ${maxIntensity.name} → interval +${intervalAdd}min (stay reactive)")
        
        return IntentInfluence(1.0f, intervalAdd, false)  // Neutral SMB
    }
    
    // Helpers
    
    private fun Float.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
    
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }
}

/**
 * Context mode (user preference).
 */
enum class ContextMode {
    CONSERVATIVE,  // Extra prudent (reduce SMB more, increase intervals more)
    BALANCED,      // Default (as designed)
    AGGRESSIVE     // Less reduction (trust context less, rely more on core algorithm)
}
