package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import kotlin.math.max
import kotlin.math.min

/**
 * ============================================================================
 * AIMI AI Decision Auditor - Decision Modulator
 * ============================================================================
 * 
 * Applies bounded modulation to AIMI decisions based on AI auditor verdict.
 * 
 * CRITICAL: This NEVER creates new doses - only modulates existing decisions
 * with strictly bounded factors.
 */
object DecisionModulator {
    
    /**
     * Modulated decision output
     */
    data class ModulatedDecision(
        val smbU: Double,
        val tbrRate: Double?,
        val tbrMin: Int?,
        val intervalMin: Double,
        val preferTbr: Boolean,
        val appliedModulation: Boolean,
        val modulationReason: String
    )
    
    /**
     * Apply modulation to AIMI decision based on auditor verdict
     * 
     * @param originalSmb Original SMB proposed by AIMI (U)
     * @param originalTbrRate Original TBR rate (U/h, null if none)
     * @param originalTbrMin Original TBR duration (min, null if none)
     * @param originalIntervalMin Original interval (min)
     * @param verdict Auditor verdict
     * @param confidence Confidence threshold (only apply if verdict.confidence >= this)
     * @param mode Modulation mode (AUDIT_ONLY, SOFT_MODULATION, HIGH_RISK_ONLY)
     * @return Modulated decision
     */
    fun applyModulation(
        originalSmb: Double,
        originalTbrRate: Double?,
        originalTbrMin: Int?,
        originalIntervalMin: Double,
        verdict: AuditorVerdict,
        confidence: Double = 0.65,
        mode: ModulationMode = ModulationMode.AUDIT_ONLY
    ): ModulatedDecision {
        
        // If audit-only mode, return original decision
        if (mode == ModulationMode.AUDIT_ONLY) {
            return ModulatedDecision(
                smbU = originalSmb,
                tbrRate = originalTbrRate,
                tbrMin = originalTbrMin,
                intervalMin = originalIntervalMin,
                preferTbr = false,
                appliedModulation = false,
                modulationReason = "Audit only mode - no modulation applied"
            )
        }
        
        // If confidence too low, return original decision
        if (verdict.confidence < confidence) {
            return ModulatedDecision(
                smbU = originalSmb,
                tbrRate = originalTbrRate,
                tbrMin = originalTbrMin,
                intervalMin = originalIntervalMin,
                preferTbr = false,
                appliedModulation = false,
                modulationReason = "Confidence too low (${String.format("%.2f", verdict.confidence)} < $confidence)"
            )
        }
        
        // If high-risk-only mode, only apply if risk flags present
        if (mode == ModulationMode.HIGH_RISK_ONLY && verdict.riskFlags.isEmpty()) {
            return ModulatedDecision(
                smbU = originalSmb,
                tbrRate = originalTbrRate,
                tbrMin = originalTbrMin,
                intervalMin = originalIntervalMin,
                preferTbr = false,
                appliedModulation = false,
                modulationReason = "High-risk only mode - no risk flags detected"
            )
        }
        
        // Apply modulation based on verdict
        val adj = verdict.boundedAdjustments
        
        when (verdict.verdict) {
            VerdictType.CONFIRM -> {
                return ModulatedDecision(
                    smbU = originalSmb,
                    tbrRate = originalTbrRate,
                    tbrMin = originalTbrMin,
                    intervalMin = originalIntervalMin,
                    preferTbr = false,
                    appliedModulation = false,
                    modulationReason = "Verdict: CONFIRM - decision approved as-is"
                )
            }
            
            VerdictType.SOFTEN -> {
                // Apply SMB factor clamp (bounded 0.0-1.0)
                val smbFactor = adj.smbFactorClamp.coerceIn(0.0, 1.0)
                val modulatedSmb = originalSmb * smbFactor
                
                // Apply interval increase (bounded 0-6 min)
                val intervalAdd = adj.intervalAddMin.coerceIn(0, 6)
                val modulatedInterval = originalIntervalMin + intervalAdd
                
                val reason = buildString {
                    append("Verdict: SOFTEN")
                    if (smbFactor < 1.0) {
                        append(" - SMB reduced by ${String.format("%.0f", (1.0 - smbFactor) * 100)}%")
                    }
                    if (intervalAdd > 0) {
                        append(" - Interval increased by ${intervalAdd}min")
                    }
                    if (adj.preferTbr) {
                        append(" - Prefer TBR enabled")
                    }
                }
                
                return ModulatedDecision(
                    smbU = modulatedSmb,
                    tbrRate = originalTbrRate,
                    tbrMin = originalTbrMin,
                    intervalMin = modulatedInterval,
                    preferTbr = adj.preferTbr,
                    appliedModulation = true,
                    modulationReason = reason
                )
            }
            
            VerdictType.SHIFT_TO_TBR -> {
                // Very low SMB factor (0.0-0.3)
                val smbFactor = adj.smbFactorClamp.coerceIn(0.0, 0.3)
                val modulatedSmb = originalSmb * smbFactor
                
                // Moderate TBR factor (0.8-1.2) if TBR exists
                val tbrFactor = adj.tbrFactorClamp.coerceIn(0.8, 1.2)
                val modulatedTbrRate = originalTbrRate?.let { it * tbrFactor }
                
                // Always prefer TBR in this mode
                val reason = buildString {
                    append("Verdict: SHIFT_TO_TBR")
                    append(" - SMB heavily reduced (factor ${String.format("%.1f", smbFactor)})")
                    if (modulatedTbrRate != null) {
                        append(" - TBR adjusted by ${String.format("%.0f", (tbrFactor - 1.0) * 100)}%")
                    }
                    append(" - Prefer TBR enabled")
                }
                
                return ModulatedDecision(
                    smbU = modulatedSmb,
                    tbrRate = modulatedTbrRate,
                    tbrMin = originalTbrMin,
                    intervalMin = originalIntervalMin,
                    preferTbr = true,
                    appliedModulation = true,
                    modulationReason = reason
                )
            }
        }
    }
    
    /**
     * Modulation modes
     */
    enum class ModulationMode {
        AUDIT_ONLY,         // No modulation, only logging
        SOFT_MODULATION,    // Apply modulation for all verdicts above confidence threshold
        HIGH_RISK_ONLY      // Only apply modulation if risk flags present
    }
    
    /**
     * Check if modulation should be triggered based on current state
     * 
     * This implements the "intelligent trigger" logic to avoid unnecessary API calls
     * 
     * @param bg Current BG (mg/dL)
     * @param delta Delta (mg/dL/5min)
     * @param shortAvgDelta Short average delta
     * @param smbProposed SMB proposed (U)
     * @param iob Current IOB (U)
     * @param smb30min SMB delivered in last 30min (U)
     * @param predictionAvailable Is prediction available
     * @param inMealMode Is meal mode active
     * @param inPrebolusWindow Is in prebolus window (P1/P2)
     * @return true if audit should be triggered
     */
    fun shouldTriggerAudit(
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        smbProposed: Double,
        iob: Double,
        smb30min: Double,
        predictionAvailable: Boolean,
        inMealMode: Boolean,
        inPrebolusWindow: Boolean
    ): Boolean {
        
        // NEVER trigger during prebolus window (P1/P2)
        if (inPrebolusWindow) {
            return false
        }
        
        // Trigger conditions (any of these):
        
        // 1. High delta or shortAvgDelta
        if (delta > 2.0 || shortAvgDelta > 1.5) {
            return true
        }
        
        // 2. Low BG + SMB proposed
        if (bg < 120.0 && smbProposed > 0.0) {
            return true
        }
        
        // 3. High cumulative SMB in 30min
        val smbThreshold = if (inMealMode) 2.5 else 1.5
        if (smb30min > smbThreshold) {
            return true
        }
        
        // 4. Prediction absent but SMB proposed
        if (!predictionAvailable && smbProposed > 0.0) {
            return true
        }
        
        // 5. Very high IOB + more SMB proposed
        if (iob > 3.0 && smbProposed > 0.3) {
            return true
        }
        
        return false
    }
}
