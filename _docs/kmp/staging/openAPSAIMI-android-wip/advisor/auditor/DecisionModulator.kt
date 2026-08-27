package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import kotlin.math.max
import kotlin.math.min
import app.aaps.plugins.aps.openAPSAIMI.model.VerdictType
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.model.AdvisorSeverity

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
    
    // Legacy data class replaced by DecisionResult sealed class
    
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
    ): DecisionResult {
        
        // If audit-only mode, return skipped
        if (mode == ModulationMode.AUDIT_ONLY) {
            return DecisionResult.Skipped(
                source = "AuditorModulator",
                reason = "Audit only mode - no action taken"
            )
        }
        
        // If confidence too low, return rejected
        if (verdict.confidence < confidence) {
            return DecisionResult.Rejected(
                source = "AuditorModulator",
                reason = "Low confidence (${String.format("%.2f", verdict.confidence)})",
                severity = AdvisorSeverity.Warning("Audit Confidence Threshold")
            )
        }
        
        // If high-risk-only mode, only apply if risk flags present
        if (mode == ModulationMode.HIGH_RISK_ONLY && verdict.riskFlags.isEmpty()) {
            return DecisionResult.Skipped(
                source = "AuditorModulator",
                reason = "High-risk only mode - no risks detected"
            )
        }
        
        // Apply modulation based on verdict
        val adj = verdict.boundedAdjustments
        
        val result = when (verdict.verdict) {
            VerdictType.Confirm -> {
                DecisionResult.Applied(
                    source = "AuditorModulator",
                    bolusU = originalSmb,
                    tbrUph = originalTbrRate,
                    tbrMin = originalTbrMin,
                    reason = "Verdict: CONFIRM - approved"
                )
            }
            
            VerdictType.Soften -> {
                val smbFactor = adj.smbFactorClamp.coerceIn(0.0, 1.0)
                val modulatedSmb = originalSmb * smbFactor
                
                val intervalAdd = adj.intervalAddMin.coerceIn(0, 6)
                // Note: DecisionResult.Applied doesn't strictly track intervalMin, 
                // in this architecture, we let the loop handle interval via separate metadata if needed.
                
                DecisionResult.Applied(
                    source = "AuditorModulator",
                    bolusU = modulatedSmb,
                    tbrUph = originalTbrRate,
                    tbrMin = originalTbrMin,
                    reason = "Verdict: SOFTEN - SMB reduced by ${String.format("%.0f", (1.0 - smbFactor) * 100)}%"
                )
            }
            
            VerdictType.ShiftToTbr -> {
                val smbFactor = adj.smbFactorClamp.coerceIn(0.0, 0.3)
                val modulatedSmb = originalSmb * smbFactor
                val tbrFactor = adj.tbrFactorClamp.coerceIn(0.8, 1.2)
                val modulatedTbrRate = originalTbrRate?.let { it * tbrFactor }
                
                DecisionResult.Applied(
                    source = "AuditorModulator",
                    bolusU = modulatedSmb,
                    tbrUph = modulatedTbrRate,
                    tbrMin = originalTbrMin,
                    reason = "Verdict: SHIFT_TO_TBR - Prefer Basal Control"
                )
            }
        }
        
        // Final Clinical Validation (Expert Requirement)
        return validateDecision(result)
    }

    private fun validateDecision(result: DecisionResult): DecisionResult {
        if (result is DecisionResult.Applied) {
            val bolus = result.bolusU ?: 0.0
            val tbr = result.tbrUph ?: 0.0
            
            // 🛡️ Expert Safety Guard: No single SMB > 30U from Auditor
            if (bolus > 30.0) {
                return DecisionResult.Rejected(
                    source = "SafetyGuard",
                    reason = "Extreme SMB Request Blocked ($bolus U)",
                    severity = AdvisorSeverity.Critical("Clinical Safety Cap Violation")
                )
            }
            
            // 🛡️ Expert Safety Guard: TBR normalization
            if (tbr < 0.0) {
                 return DecisionResult.Rejected(
                    source = "SafetyGuard",
                    reason = "Negative TBR rate requested",
                    severity = AdvisorSeverity.Warning("Protocol Invalidation")
                )
            }
        }
        return result
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
     * Philosophy: When the Second Brain is enabled, it should audit MOST decisions,
     * not just extreme cases. We only skip:
     * - Prebolus windows (P1/P2) where AIMI has special logic
     * - Completely flat/stable scenarios with no action
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
        // AIMI has specific logic there that shouldn't be challenged
        if (inPrebolusWindow) {
            return false
        }
        
        // ================================================================
        // NEW PHILOSOPHY: Audit MOST decisions by default
        // ================================================================
        
        // Only SKIP audit if ALL of these are true:
        // 1. BG is stable (delta close to 0)
        // 2. No SMB proposed
        // 3. IOB is low
        // 4. No recent SMB activity
        
        val isStable = kotlin.math.abs(delta) < 0.5 && kotlin.math.abs(shortAvgDelta) < 0.5
        val noAction = smbProposed < 0.05
        val lowIob = iob < 0.5
        val noRecentSmb = smb30min < 0.1
        
        // If completely flat and no action, skip audit (save API calls)
        if (isStable && noAction && lowIob && noRecentSmb) {
            return false
        }
        
        // Otherwise: AUDIT!
        // This means the Second Brain will see:
        // - Any significant BG movement
        // - Any SMB being proposed
        // - Any existing IOB
        // - Any meal mode
        // - Any unstable glucose
        
        return true
    }
}
