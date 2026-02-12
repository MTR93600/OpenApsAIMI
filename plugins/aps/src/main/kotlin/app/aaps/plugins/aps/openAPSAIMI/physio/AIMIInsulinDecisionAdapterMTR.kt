package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.data.model.TE
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 💉 AIMI Insulin Decision Adapter - MTR Implementation
 * 
 * THE CRITICAL SAFETY GATE between physiological analysis and insulin delivery.
 * 
 * This adapter:
 * 1. Reads physiological context from store
 * 2. Applies deterministic rules to convert context → multipliers
 * 3. Enforces HARD SAFETY CAPS (ISF ±15%, Basal +15%, SMB +10%)
 * 4. Validates against recent hypoglycemia
 * 5. Checks current BG before applying any changes
 * 6. NEVER allows multipliers outside safe bounds
 * 
 * CRITICAL RULE: If ANY safety check fails → return NEUTRAL (all 1.0)
 * 
 * Integration Point: Called by determineBasalAIMI2 early in execution
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMIInsulinDecisionAdapterMTR @Inject constructor(
    private val repo: HealthContextRepository,
    private val persistenceLayer: PersistenceLayer,
    private val dataRepository: AIMIPhysioDataRepositoryMTR,
    private val contextStore: AIMIPhysioContextStoreMTR, // 🚀 NEW INJECTION
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "InsulinDecisionAdapter"
        
        // HARD SAFETY CAPS (non-negotiable)
        private const val ISF_MIN_FACTOR = 0.85 // Max 15% increase in sensitivity
        private const val ISF_MAX_FACTOR = 1.15 // Max 15% decrease in sensitivity
        private const val BASAL_MIN_FACTOR = 0.85 // Max 15% reduction
        private const val BASAL_MAX_FACTOR = 1.15 // Max 15% increase
        private const val SMB_MIN_FACTOR = 0.90 // Max 10% reduction
        private const val SMB_MAX_FACTOR = 1.10 // Max 10% increase
        private const val REACTIVITY_MIN_FACTOR = 0.90
        private const val REACTIVITY_MAX_FACTOR = 1.10
        
        // Safety thresholds
        private const val MIN_BG_FOR_MODULATION = 80.0 // mg/dL
        private const val RECENT_HYPO_WINDOW_MS = 2 * 60 * 60 * 1000L // 2 hours
        private const val HYPO_THRESHOLD_MG_DL = 70.0
        private const val MIN_CONFIDENCE_THRESHOLD = 0.5
    }
    
    /**
     * Returns the current physiological snapshot for external use (e.g. PKPD, Auditor)
     */
    fun getLatestSnapshot(): HealthContextSnapshot {
        return repo.getLastSnapshot()
    }
    
    /**
     * Gets insulin multipliers based on physiological context
     * 
     * INTEGRATION POINT: Called by determineBasalAIMI2
     * 
     * @param currentBG Current blood glucose (mg/dL)
     * @param currentDelta Current BG delta (mg/dL/5min)
     * @param recentHypoTimestamp Timestamp of most recent hypoglycemia (optional)
     * @return PhysioMultipliersMTR (NEUTRAL if any safety check fails)
     */
    /**
     * Returns the current physiological context for external use (e.g. PKPD)
     */


    /**
     * Gets insulin multipliers based on physiological context
     * 
     * INTEGRATION POINT: Called by determineBasalAIMI2
     * 
     * @param currentBG Current blood glucose (mg/dL)
     * @param currentDelta Current BG delta (mg/dL/5min)
     * @param recentHypoTimestamp Explicit timestamp of last hypo
     * @return PhysioMultipliersMTR (NEUTRAL if any safety check fails)
     */
    fun getMultipliers(
        currentBG: Double,
        currentDelta: Double? = null,
        recentHypoTimestamp: Long? = null
    ): PhysioMultipliersMTR {
        
        // Safety Check 1: BG too low
        if (currentBG < MIN_BG_FOR_MODULATION) {
            aapsLogger.warn(
                LTag.APS,
                "[$TAG] ⚠️ BG too low (${currentBG.toInt()} mg/dL) - skipping physio modulation"
            )
            return PhysioMultipliersMTR.NEUTRAL
        }
        
        // Safety Check 2: Recent hypoglycemia
        if (hasRecentHypoglycemia(recentHypoTimestamp)) {
            aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ Recent hypoglycemia detected - skipping modulation")
            return PhysioMultipliersMTR.NEUTRAL
        }
        
        // Get current snapshot (still needed for Real-Time Activity Brake)
        val snapshot = repo.getLastSnapshot() 
        
        // Get formulated Physio Context (Semantic Brain)
        // We accept lower confidence (0.3) because we default to 1.0 (Neutral) anyway
        // This ensures availability even during "building baseline" phase
        val physioContext = contextStore.getEffectiveContext(0.3) ?: PhysioContextMTR.NEUTRAL

        if (!snapshot.isValid) {
            //aapsLogger.debug(LTag.APS, "[$TAG] No valid snapshot - returning NEUTRAL")
            return PhysioMultipliersMTR.NEUTRAL
        }
        
        // Safety Check 3: Confidence too low
        if (snapshot.confidence < MIN_CONFIDENCE_THRESHOLD) {
             // Silently ignore low confidence
            return PhysioMultipliersMTR.NEUTRAL
        }
        
        // Calculate raw multipliers
        // Calculate raw multipliers combining Semantic + Tactical
        val rawMultipliers = calculateRawMultipliers(snapshot, physioContext, currentBG, currentDelta)
        
        // Apply HARD CAPS
        val cappedMultipliers = applyHardCaps(rawMultipliers)
        
        // COMPACT LOGING (User Request: "concis, en anglais, écran étroit")
        // "PHYSIO ctx: steps15=, hr=, hrv=, conf= -> brake=, stress= -> smbMult="
        if (!cappedMultipliers.isNeutral()) {
        if (!cappedMultipliers.isNeutral()) {
            val logMsg = "🏥 PHYSIO ctx: State=${physioContext.state} (${(physioContext.confidence*100).toInt()}%) | " + 
                         "steps15=${snapshot.stepsLast15m}, hr=${snapshot.hrNow} " +
                         "-> ${cappedMultipliers.appliedCaps} -> ISF x${cappedMultipliers.isfFactor.format(2)}, SMB x${cappedMultipliers.smbFactor.format(2)}"
            aapsLogger.info(LTag.APS, logMsg)
        }
        
        return cappedMultipliers
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // MULTIPLIER CALCULATION (Deterministic Logic)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Calculates raw multipliers based on physiological state
     * These are BEFORE safety caps
     */
    /**
     * Calculates raw multipliers based on Physiological Snapshot
     * Uses explicit conservative modulators: Brake, Penalty, Debt.
     */
    private fun calculateRawMultipliers(
        snapshot: HealthContextSnapshot,
        context: PhysioContextMTR,
        currentBG: Double,
        currentDelta: Double?
    ): PhysioMultipliersMTR {
        
        // 1. Calculate Factors
        
        // A. Semantic Risk Aversion (The "Brain")
        // "In times of uncertainty or stress, play it safe."
        val riskAversionFactor = context.toRiskAversionFactor()
        
        // B. Tactical Activity Brake (The "Reflex")
        // Activity requires immediate braking regardless of stress state
        val activityBrakeFactor = when {
            snapshot.stepsLast15m > 1000 -> 0.7 // Heavy braking (Sport)
            snapshot.stepsLast15m > 500 -> 0.85 // Moderate braking (Walk)
            else -> 1.0
        }

        // C. Sleep Debt (Auxiliary)
        // If deep sleep debt, slightly reduce aggression
        val sleepDebtFactor = if (snapshot.sleepDebtMinutes > 90) 0.95 else 1.0
        
        // 2. Combine Factors (Safety First Strategy)
        // We take the STRONGEST brake (Minimum factor)
        // Example: Stress(0.8) vs Sport(0.7) -> Result 0.7
        val totalAggression = minOf(riskAversionFactor, activityBrakeFactor, sleepDebtFactor)
        
        // 3. Map to Components
        
        // Basal & SMB: Direct scaling (Lower aggression = Lower delivery)
        val newBasalFactor = 1.0 * totalAggression
        val newSmbFactor = 1.0 * totalAggression
        
        // ISF: Inverse scaling (Lower aggression = Higher ISF value/Weaker sensitivity)
        // Logic: specific resistance (Stress) might want lower ISF (stronger), 
        // BUT our Risk Aversion strategy says "play it safe". 
        // So we WEAKEN the ISF (increase the value) to avoid over-correction.
        val newIsfFactor = if (totalAggression < 1.0) (1.0 / totalAggression) else 1.0

        val appliedCapsList = mutableListOf<String>()
        if (riskAversionFactor < 1.0) appliedCapsList.add("RiskAv:${riskAversionFactor.format(2)}")
        if (activityBrakeFactor < 1.0) appliedCapsList.add("ActBrake:${activityBrakeFactor.format(2)}")
        if (sleepDebtFactor < 1.0) appliedCapsList.add("SleepDebt:${sleepDebtFactor.format(2)}")

        return PhysioMultipliersMTR(
            isfFactor = newIsfFactor,
            basalFactor = newBasalFactor,
            smbFactor = newSmbFactor,
            reactivityFactor = totalAggression,
            confidence = context.confidence, // Use Semantic confidence
            appliedCaps = appliedCapsList.joinToString(", "),
            source = "Semantic+Tactical"
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SAFETY CAPS (HARD ENFORCEMENT)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Applies HARD SAFETY CAPS to all multipliers
     * CRITICAL: This is the final safety gate
     */
    private fun applyHardCaps(multipliers: PhysioMultipliersMTR): PhysioMultipliersMTR {
        
        val cappedISF = multipliers.isfFactor.coerceIn(ISF_MIN_FACTOR, ISF_MAX_FACTOR)
        val cappedBasal = multipliers.basalFactor.coerceIn(BASAL_MIN_FACTOR, BASAL_MAX_FACTOR)
        val cappedSMB = multipliers.smbFactor.coerceIn(SMB_MIN_FACTOR, SMB_MAX_FACTOR)
        val cappedReactivity = multipliers.reactivityFactor.coerceIn(REACTIVITY_MIN_FACTOR, REACTIVITY_MAX_FACTOR)
        
        // Check if any capping occurred
        val wasCapped = (cappedISF != multipliers.isfFactor) ||
                       (cappedBasal != multipliers.basalFactor) ||
                       (cappedSMB != multipliers.smbFactor) ||
                       (cappedReactivity != multipliers.reactivityFactor)
        
        if (wasCapped) {
            aapsLogger.warn(
                LTag.APS,
                "[$TAG] ⚠️ Safety caps applied | " +
                "ISF: ${multipliers.isfFactor.format(3)}→${cappedISF.format(3)}, " +
                "Basal: ${multipliers.basalFactor.format(3)}→${cappedBasal.format(3)}, " +
                "SMB: ${multipliers.smbFactor.format(3)}→${cappedSMB.format(3)}"
            )
        }
        
        return PhysioMultipliersMTR(
            isfFactor = cappedISF,
            basalFactor = cappedBasal,
            smbFactor = cappedSMB,
            reactivityFactor = cappedReactivity,
            confidence = multipliers.confidence,
            appliedCaps = if (wasCapped) "${multipliers.appliedCaps} + CAPPED" else multipliers.appliedCaps,
            source = multipliers.source
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SAFETY VALIDATORS
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Checks for recent hypoglycemia
     * 
     * @param explicitTimestamp Explicitly provided hypo timestamp (optional)
     * @return true if hypo occurred in last 2 hours
     */
    private fun hasRecentHypoglycemia(explicitTimestamp: Long?): Boolean {
        val now = System.currentTimeMillis()
        
        // Check explicit timestamp first
        if (explicitTimestamp != null && (now - explicitTimestamp) < RECENT_HYPO_WINDOW_MS) {
            return true
        }
        
        // Check therapy events for hypo treatments
        try {
            val hypoEvents = persistenceLayer.getTherapyEventDataFromTime(
                now - RECENT_HYPO_WINDOW_MS,
                TE.Type.NOTE,
                false
            ).filter { event ->
                event.note?.contains("hypo", ignoreCase = true) == true ||
                event.note?.contains("hypoglycemia", ignoreCase = true) == true
            }
            
            if (hypoEvents.isNotEmpty()) {
                val latestHypo = hypoEvents.maxByOrNull { it.timestamp }
                val age = (now - (latestHypo?.timestamp ?: 0)) / (60 * 1000)
                aapsLogger.debug(LTag.APS, "[$TAG] Hypo event found ${age} min ago")
                return true
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[$TAG] Error checking hypo events", e)
        }
        
        return false
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // REAL-TIME ACTIVITY (Direct Pass-through)
    // ═══════════════════════════════════════════════════════════════════════
    
    data class RealTimeActivity(
        val stepsToday: Int,
        val heartRate: Int
    )

    /**
     * Fetches current interaction data (Steps, HR) directly from repository
     * This bypasses the 15-min cache to allow real-time reactivity in the loop
     */
    fun getRealTimeActivity(): RealTimeActivity {
         // Graceful fallback if repo fail (returns 0)
         val steps = dataRepository.fetchStepsData(0) 
         val hr = dataRepository.fetchLastHeartRate()
         return RealTimeActivity(steps, hr)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
    
    /**
     * Gets current adapter status for debugging
     */
    fun getStatus(): Map<String, String> {
        val snapshot = repo.getLastSnapshot()
        
        return mapOf(
            "source" to snapshot.source,
            "confidence" to "${(snapshot.confidence * 100).toInt()}%",
            "age" to "${(System.currentTimeMillis() - snapshot.timestamp) / 1000}s",
            "isValid" to snapshot.isValid.toString()
        )
    }

    /**
     * Returns a detailed formatted log string for user visibility (UI Status)
     * Replaces the old diagnostic log with a Snapshot summary
     */
    fun getDetailedLogString(): String {
        val snapshot = repo.getLastSnapshot()
        
        if (!snapshot.isValid || snapshot.timestamp == 0L) {
            return "🏥 Physio: NO DATA / WAITING | Check Health Connect permissions & Sync"
        }
        
        val ageMin = (System.currentTimeMillis() - snapshot.timestamp) / 60000
        val sb = StringBuilder()
        
        // Header
        sb.append("🏥 Physio Status (${ageMin}m ago) | Conf: ${(snapshot.confidence * 100).toInt()}%")
        
        // Activity
        sb.append("\n🏃 Activity: ${snapshot.stepsLast15m} steps/15m (State: ${snapshot.activityState})")
        
        // Heart
        val hrvStr = if (snapshot.hrvRmssd > 0) "${snapshot.hrvRmssd.toInt()}ms" else "--"
        sb.append("\n❤️ Heart: HR ${snapshot.hrNow} | RHR ${snapshot.rhrResting} | HRV $hrvStr")
        
        // Sleep
        if (snapshot.sleepDebtMinutes > 0) {
             sb.append("\n😴 Sleep Debt: ${snapshot.sleepDebtMinutes} min")
        } else {
             sb.append("\n😴 Sleep: OK")
        }
        
        // BP
        if (snapshot.bpSys > 0) {
            sb.append("\n🩸 BP: ${snapshot.bpSys}/${snapshot.bpDia}")
        }
        
        return sb.toString()
    }
}
