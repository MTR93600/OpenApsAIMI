package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import java.util.Locale

/**
 * RT Instrumentation Helpers
 * 
 * Purpose: Build concise, production-ready debug lines for finalResult.reason
 * 
 * Constraints:
 * - Max 80 chars per line
 * - Null/NaN safe
 * - English only
 * - No crashes
 */
object RtInstrumentationHelpers {
    
    /**
     * Build learners debug line (concise, 1 line)
     * 
     * Format: "Learners: UR×1.12 ISF 46→51(×1.11) PKPD DIA 350m Pk 76m Tail 91%"
     * 
     * @return Concise learners line, max 80 chars
     */
    fun buildLearnersLine(
        unifiedReactivityFactor: Double?,
        profileIsf: Double?,
        fusedIsf: Double?,
        pkpdDiaMin: Int?,
        pkpdPeakMin: Int?,
        pkpdTailPct: Int?
    ): String {
        val parts = mutableListOf<String>()
        
        // Unified Reactivity
        unifiedReactivityFactor?.let { ur ->
            if (!ur.isNaN() && !ur.isInfinite() && kotlin.math.abs(ur - 1.0) > 0.01) {
                parts.add("UR×${safeFmt(ur, "%.2f")}")
            }
        }
        
        // ISF fusion
        if (profileIsf != null && fusedIsf != null && 
            !profileIsf.isNaN() && !fusedIsf.isNaN() && 
            kotlin.math.abs(fusedIsf - profileIsf) > 0.5) {
            val scale = fusedIsf / profileIsf
            parts.add("ISF ${profileIsf.toInt()}→${fusedIsf.toInt()}(×${safeFmt(scale, "%.2f")})")
        }
        
        // PKPD
        if (pkpdDiaMin != null || pkpdPeakMin != null || pkpdTailPct != null) {
            val pkpdParts = mutableListOf<String>()
            pkpdDiaMin?.let { pkpdParts.add("DIA ${it}m") }
            pkpdPeakMin?.let { pkpdParts.add("Pk ${it}m") }
            pkpdTailPct?.let { pkpdParts.add("Tail ${it}%") }
            if (pkpdParts.isNotEmpty()) {
                parts.add("PKPD " + pkpdParts.joinToString(" "))
            }
        }
        
        if (parts.isEmpty()) {
            return "Learners: n/a"
        }
        
        val line = "Learners: " + parts.joinToString(" | ")
        return if (line.length > 80) line.substring(0, 77) + "..." else line
    }
    
    /**
     * Build WCycle debug line (optional, 1 line)
     * 
     * Format: "Wcycle: Luteal ×1.08"
     * 
     * @return WCycle line or null if not active
     */
    fun buildWCycleLine(
        enabled: Boolean,
        phase: String?,
        factor: Double?
    ): String? {
        if (!enabled || phase == null) return null
        
        val factorStr = factor?.let { 
            if (!it.isNaN() && !it.isInfinite()) " ×${safeFmt(it, "%.2f")}" else ""
        } ?: ""
        
        val line = "Wcycle: $phase$factorStr"
        return if (line.length > 60) line.substring(0, 57) + "..." else line
    }
    
    /**
     * Build auditor debug line (1 line)
     * 
     * Formats (NEW - explicit status):
     * - OFF: "Auditor: OFF"
     * - OFFLINE_NO_APIKEY: "Auditor: OFFLINE_NO_APIKEY"
     * - SKIPPED_RATE_LIMITED: "Auditor: SKIPPED_RATE_LIMITED"  
     * - ERROR_TIMEOUT: "Auditor: ERROR_TIMEOUT"
     * - STALE: "Auditor: STALE (5m old, last=OK_SOFTEN)"
     * - OK (active): "Auditor: SOFTEN conf=0.78 smb×0.65 +3m preferTBR [stacking]"
     * 
     * @return Auditor line (never null)
     */
    fun buildAuditorLine(
        enabled: Boolean
    ): String {
        if (!enabled) return "Auditor: OFF"
        
        // Get detailed status from tracker (FIX 2025-12-31)
        val (status, ageMs) = AuditorStatusTracker.getStatus(maxAgeMs = 300_000)
        
        return when {
            // Disabled
            status == AuditorStatusTracker.Status.OFF -> 
                "Auditor: OFF"
            
            // Offline (can't reach AI - explicit reason)
            status.isOffline() -> 
                "Auditor: ${status.message}"
            
            // Error (attempted but failed - explicit reason)
            status.isError() -> 
                "Auditor: ${status.message}"
            
            // Skipped (eligible but deliberately not calling - explicit reason)
            status.isSkipped() -> 
                "Auditor: ${status.message}"
            
            // Stale (verdict too old)
            status == AuditorStatusTracker.Status.STALE && ageMs != null -> {
                val ageMin = (ageMs / 60_000).toInt()
                "Auditor: STALE (${ageMin}m old)"
            }
            
            // Active (verdict received and applied)
            status.isActive() -> {
                val cached = AuditorVerdictCache.get(maxAgeMs = 300_000)
                    ?: return "Auditor: ${status.message}"  // Fallback if cache empty
                
                val verdict = cached.verdict
                val modulation = cached.modulation
                val parts = mutableListOf<String>()
                
                // Verdict type
                parts.add(verdict.verdict.name)
                
                // Confidence
                parts.add("conf=${safeFmt(verdict.confidence, "%.2f")}")
                
                // SMB factor (if modulation applied)
                if (modulation.appliedModulation) {
                    val smbFactor = verdict.boundedAdjustments.smbFactorClamp
                    if (smbFactor < 1.0) {
                        parts.add("smb×${safeFmt(smbFactor, "%.2f")}")
                    }
                    
                    val intervalAdd = verdict.boundedAdjustments.intervalAddMin
                    if (intervalAdd > 0) {
                        parts.add("+${intervalAdd}m")
                    }
                }
                
                // Prefer TBR
                if (modulation.preferTbr) {
                    parts.add("preferTBR")
                }
                
                // Risk flags (max 2)
                if (verdict.riskFlags.isNotEmpty()) {
                    val flags = verdict.riskFlags.take(2).joinToString(",")
                    parts.add("[$flags]")
                }
                
                val line = "Auditor: " + parts.joinToString(" ")
                if (line.length > 80) line.substring(0, 77) + "..." else line
            }
            
            // Unknown status (shouldn't happen)
            else -> "Auditor: UNKNOWN"
        }
    }
    
    /**
     * Safe format double to string
     * @param value Value to format
     * @param format Format string (e.g. "%.2f")
     * @param fallback Fallback if null/NaN/Infinite
     * @return Formatted string
     */
    private fun safeFmt(value: Double?, format: String, fallback: String = "n/a"): String {
        if (value == null || value.isNaN() || value.isInfinite()) return fallback
        return format.format(Locale.US, value)
    }
}
