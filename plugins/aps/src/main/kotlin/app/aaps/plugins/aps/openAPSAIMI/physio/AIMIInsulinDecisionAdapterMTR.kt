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
    private val contextStore: AIMIPhysioContextStoreMTR,
    private val persistenceLayer: PersistenceLayer,
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
     * Gets insulin multipliers based on physiological context
     * 
     * INTEGRATION POINT: Called by determineBasalAIMI2
     * 
     * @param currentBG Current blood glucose (mg/dL)
     * @param currentDelta Current BG delta (mg/dL/5min)
     * @param recentHypoTimestamp Timestamp of most recent hypoglycemia (optional)
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
        
        // Get current context
        val context = contextStore.getCurrentContext()
        
        if (context == null) {
            aapsLogger.debug(LTag.APS, "[$TAG] No valid context - returning NEUTRAL")
            return PhysioMultipliersMTR.NEUTRAL
        }
        
        // Safety Check 3: Confidence too low
        if (context.confidence < MIN_CONFIDENCE_THRESHOLD) {
            aapsLogger.warn(
                LTag.APS,
                "[$TAG] ⚠️ Low confidence (${(context.confidence * 100).toInt()}%) - skipping modulation"
            )
            return PhysioMultipliersMTR.NEUTRAL
        }
        
        // Calculate raw multipliers based on context
        val rawMultipliers = calculateRawMultipliers(context, currentBG, currentDelta)
        
        // Apply HARD CAPS
        val cappedMultipliers = applyHardCaps(rawMultipliers)
        
        // Log application
        if (!cappedMultipliers.isNeutral()) {
            aapsLogger.info(
                LTag.APS,
                "[$TAG] 💉 Applying Physio Modulation | " +
                "State: ${context.state} | " +
                "ISF: ${cappedMultipliers.isfFactor.format(3)} | " +
                "Basal: ${cappedMultipliers.basalFactor.format(3)} | " +
                "SMB: ${cappedMultipliers.smbFactor.format(3)} | " +
                "Confidence: ${(cappedMultipliers.confidence * 100).toInt()}%"
            )
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
    private fun calculateRawMultipliers(
        context: PhysioContextMTR,
        currentBG: Double,
        currentDelta: Double?
    ): PhysioMultipliersMTR {
        
        return when (context.state) {
            PhysioStateMTR.OPTIMAL -> {
                // No changes - everything nominal
                PhysioMultipliersMTR(
                    isfFactor = 1.0,
                    basalFactor = 1.0,
                    smbFactor = 1.0,
                    reactivityFactor = 1.0,
                    confidence = context.confidence,
                    source = "Deterministic"
                )
            }
            
            PhysioStateMTR.RECOVERY_NEEDED -> {
                // Slightly protective:
                // - Increase ISF by ~5-10% (less aggressive corrections)
                // - Reduce SMB by ~5% if poor sleep
                PhysioMultipliersMTR(
                    isfFactor = if (context.recommendIncreaseISF) 1.08 else 1.0,
                    basalFactor = 1.0, // No basal change
                    smbFactor = if (context.recommendReduceSMB) 0.95 else 1.0,
                    reactivityFactor = 0.95, // Slightly less reactive
                    confidence = context.confidence,
                    appliedCaps = "Recovery mode: ↑ISF, ↓SMB",
                    source = "Deterministic"
                )
            }
            
            PhysioStateMTR.STRESS_DETECTED -> {
                // Moderately protective:
                // - Increase ISF by ~10%
                // - Reduce basal slightly (~5%)
                // - Reduce SMB by ~8%
                PhysioMultipliersMTR(
                    isfFactor = 1.10,
                    basalFactor = 0.95,
                    smbFactor = 0.92,
                    reactivityFactor = 0.92,
                    confidence = context.confidence,
                    appliedCaps = "Stress detected: ↑ISF, ↓Basal, ↓SMB",
                    source = "Deterministic"
                )
            }
            
            PhysioStateMTR.INFECTION_RISK -> {
                // Very protective (max allowed):
                // - Increase ISF by 15% (max allowed)
                // - Reduce basal by 10%
                // - Reduce SMB by 10% (max allowed)
                PhysioMultipliersMTR(
                    isfFactor = 1.15, // Will be capped
                    basalFactor = 0.90,
                    smbFactor = 0.90, // Will be capped
                    reactivityFactor = 0.90,
                    confidence = context.confidence,
                    appliedCaps = "Infection risk: MAX protective",
                    source = "Deterministic"
                )
            }
            
            PhysioStateMTR.UNKNOWN -> {
                // No changes when uncertain
                PhysioMultipliersMTR.NEUTRAL
            }
        }
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
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)
    
    /**
     * Gets current adapter status for debugging
     */
    fun getStatus(): Map<String, String> {
        val context = contextStore.getCurrentContext()
        
        return mapOf(
            "hasContext" to (context != null).toString(),
            "state" to (context?.state?.name ?: "NONE"),
            "confidence" to "${((context?.confidence ?: 0.0) * 100).toInt()}%",
            "contextAge" to "${context?.ageSeconds() ?: 0}s",
            "isValid" to (context?.isValid() ?: false).toString()
        )
    }

    /**
     * Returns a detailed formatted log string for user visibility
     * Shows raw metrics and state regardless of whether multipliers are applied
     */
    fun getDetailedLogString(): String? {
        val context = contextStore.getCurrentContext() ?: return null
        
        // Only return log if data is relatively fresh (24h)
        if (context.ageSeconds() > 24 * 3600) return null

        val features = context.features
        val sb = StringBuilder()
        
        sb.append("🏥 Physio Status: ${context.state} (Conf: ${(context.confidence * 100).toInt()}%)")
        
        if (features != null && features.hasValidData) {
            sb.append("\n    • Sleep: %.1fh (Eff: %.0f%%)".format(features.sleepDurationHours, features.sleepEfficiency * 100))
            if (features.sleepDurationHours > 0) sb.append(" Z=%.1f".format(context.sleepDeviationZ))
            
            sb.append("\n    • HRV: %.0fms".format(features.hrvMeanRMSSD))
            if (features.hrvMeanRMSSD > 0) sb.append(" Z=%.1f".format(context.hrvDeviationZ))
            
            sb.append(" | RHR: %dbpm".format(features.rhrMorning))
            if (features.rhrMorning > 0) sb.append(" Z=%.1f".format(context.rhrDeviationZ))
        }
        
        if (context.narrative.isNotBlank()) {
            sb.append("\n    • AI Insight: ${context.narrative.take(60)}...")
        }
        
        return sb.toString()
    }
}
