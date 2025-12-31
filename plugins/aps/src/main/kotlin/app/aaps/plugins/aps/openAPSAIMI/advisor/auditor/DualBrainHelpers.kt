package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal

/**
 * Helper functions for Dual-Brain Auditor
 * 
 * Provides utility functions to extract historical data needed by Local Sentinel
 * 
 * Phase 1: Stub implementations that compile
 * Phase 2: Proper integration with IOB history, glucose history, etc.
 */
object DualBrainHelpers {
    
    /**
     * Calculate number of SMBs delivered in last 30 minutes
     * 
     * Phase 1: Stub using IOB as proxy
     * Phase 2: Access actual bolus history
     */
    fun calculateSmbCount30min(
        iobData: IobTotal,
        currentTime: Long
    ): Int {
        // Stub: Estimate from IOB level
        return when {
            iobData.iob > 2.0 -> 3
            iobData.iob > 1.0 -> 2
            iobData.iob > 0.5 -> 1
            else -> 0
        }
    }
    
    /**
     * Calculate total SMB insulin delivered in last 60 minutes
     * 
     * Phase 1: Stub using current IOB
     * Phase 2: Access actual bolus history
     */
    fun calculateSmbTotal60min(
        iobData: IobTotal,
        currentTime: Long
    ): Double {
        // Stub: Use current IOB as conservative proxy
        return iobData.iob.coerceAtLeast(0.0)
    }
    
    /**
     * Extract BG history for last 30-60 minutes
     * 
     * Phase 1: Returns null (Sentinel handles null gracefully)
     * Phase 2: Access proper glucose history
     */
    fun extractBgHistory(
        glucoseStatus: GlucoseStatusAIMI?
    ): List<Double>? {
        // Stub: Return null, Sentinel will skip variability checks
        return null
    }
    
    /**
     * Combine Sentinel advice and External verdict
     * 
     * Rule: Most conservative wins
     */
    fun combineAdvice(
        sentinel: LocalSentinel.SentinelAdvice,
        external: AuditorVerdict?
    ): CombinedAdvice {
        
        // If no external verdict or low confidence, use Sentinel only
        if (external == null || external.confidence < 0.6) {
            return CombinedAdvice(
                smbFactor = sentinel.smbFactor,
                extraIntervalMin = sentinel.extraIntervalMin,
                preferBasal = sentinel.preferBasal,
                appliedSentinel = true,
                appliedExternal = false,
                reason = "Sentinel: ${sentinel.reason}",
                sentinelScore = sentinel.score,
                sentinelTier = sentinel.tier.name,
                externalConfidence = null,
                externalRecommendation = null
            )
        }
        
        // Extract external recommendations
        val externalSmbFactor = external.boundedAdjustments.smbFactorClamp
        val externalIntervalAdd = external.boundedAdjustments.intervalAddMin
        val externalPreferTbr = external.boundedAdjustments.preferTbr
        
        // Combine: Most conservative wins
        val finalSmbFactor = minOf(sentinel.smbFactor, externalSmbFactor)
        val finalExtraInterval = maxOf(sentinel.extraIntervalMin, externalIntervalAdd)
        val finalPreferBasal = sentinel.preferBasal || externalPreferTbr
        
        return CombinedAdvice(
            smbFactor = finalSmbFactor,
            extraIntervalMin = finalExtraInterval,
            preferBasal = finalPreferBasal,
            appliedSentinel = true,
            appliedExternal = true,
            reason = "Sentinel(${sentinel.reason}) + External(${external.verdict})",
            sentinelScore = sentinel.score,
            sentinelTier = sentinel.tier.name,
            externalConfidence = external.confidence,
            externalRecommendation = external.verdict.name
        )
    }
}

/**
 * Combined advice from Sentinel + External Auditor
 */
data class CombinedAdvice(
    val smbFactor: Double,           // 0.0-1.0 (most conservative)
    val extraIntervalMin: Int,       // 0-20 (most conservative)
    val preferBasal: Boolean,        // true if either recommends it
    val appliedSentinel: Boolean,    // Always true
    val appliedExternal: Boolean,    // True if external was used
    val reason: String,              // Combined reason
    val sentinelScore: Int,          // For logging
    val sentinelTier: String,        // For logging
    val externalConfidence: Double?, // For logging (null if not applied)
    val externalRecommendation: String? // For logging (null if not applied)
) {
    /**
     * Convert to ModulatedDecision for backward compatibility
     */
    fun toModulatedDecision(
        originalSmb: Double,
        originalTbrRate: Double?,
        originalTbrMin: Int?,
        originalIntervalMin: Double
    ): DecisionModulator.ModulatedDecision {
        return DecisionModulator.ModulatedDecision(
            smbU = originalSmb * smbFactor,
            tbrRate = originalTbrRate,
            tbrMin = originalTbrMin,
            intervalMin = originalIntervalMin + extraIntervalMin,
            preferTbr = preferBasal,
            appliedModulation = smbFactor < 1.0 || extraIntervalMin > 0 || preferBasal,
            modulationReason = reason
        )
    }
    
    /**
     * Build detailed log string
     */
    fun toLogString(): String {
        val parts = mutableListOf<String>()
        
        parts.add("Sentinel: tier=$sentinelTier score=$sentinelScore")
        
        if (appliedExternal && externalConfidence != null) {
            parts.add("External: ${externalRecommendation ?: "N/A"} conf=${String.format("%.2f", externalConfidence)}")
        }
        
        parts.add("Final: smb×${String.format("%.2f", smbFactor)} +${extraIntervalMin}m preferBasal=$preferBasal")
        
        return parts.joinToString(" | ")
    }
}
