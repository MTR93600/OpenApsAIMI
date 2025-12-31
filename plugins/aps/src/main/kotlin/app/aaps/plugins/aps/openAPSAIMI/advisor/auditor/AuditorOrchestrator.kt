package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ============================================================================
 * AIMI AI Decision Auditor - Main Orchestrator
 * ============================================================================
 * 
 * The "Second Brain" for AIMI - challenges decisions and provides bounded
 * modulation recommendations.
 * 
 * Architecture:
 * 1. Cognitive Audit: AI analyzes decision in context
 * 2. Bounded Modulator: Applies safe, controlled adjustments
 * 
 * KEY PRINCIPLES:
 * - NEVER direct command
 * - NEVER free dosing
 * - Only bounded modulation (factors 0.0-1.0, interval +0-6min)
 * - Offline mode = zero impact
 * - Respects prebolus windows (P1/P2)
 * - Intelligent triggering (not every 5 min)
 */
@Singleton
class AuditorOrchestrator @Inject constructor(
    private val preferences: Preferences,
    private val dataCollector: AuditorDataCollector,
    private val aiService: AuditorAIService,
    private val aapsLogger: AAPSLogger
) {
    
    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Cache for last verdict (to avoid redundant calls)
    private var lastVerdict: AuditorVerdict? = null
    private var lastVerdictTime: Long = 0L
    private val VERDICT_CACHE_MS = 5 * 60 * 1000L // 5 minutes
    
    // Rate limiting
    private var lastAuditTime: Long = 0L
    private val MIN_AUDIT_INTERVAL_MS = 3 * 60 * 1000L // 3 minutes minimum (was 5, reduced for better reactivity)
    
    // Audit count tracking
    private var auditsThisHour: Int = 0
    private var hourWindowStart: Long = 0L
    
    /**
     * Main entry point: Audit AIMI decision and optionally apply modulation
     * 
     * This is called from DetermineBasalAIMI2 after the decision is made but before
     * it's finalized.
     * 
     * @param bg Current BG (mg/dL)
     * @param delta Delta over 5min
     * @param shortAvgDelta Short average delta
     * @param longAvgDelta Long average delta
     * @param glucoseStatus Glucose status object
     * @param iob IOB object
     * @param cob COB value (g)
     * @param profile Current profile
     * @param pkpdRuntime PKPD runtime if available
     * @param isfUsed ISF actually used (after fusion)
     * @param smbProposed SMB proposed by AIMI (U)
     * @param tbrRate TBR rate proposed (U/h)
     * @param tbrDuration TBR duration (min)
     * @param intervalMin Interval proposed (min)
     * @param maxSMB Max SMB limit
     * @param maxSMBHB Max SMB High BG limit
     * @param maxIOB Max IOB limit
     * @param maxBasal Max basal limit
     * @param reasonTags Decision reason tags
     * @param modeType Current meal mode type (null if none)
     * @param modeRuntimeMin Meal mode runtime (minutes)
     * @param autodriveState Autodrive state string
     * @param wcyclePhase WCycle phase (null if not active)
     * @param wcycleFactor WCycle factor (null if not active)
     * @param tbrMaxMode TBR max for mode (if active)
     * @param tbrMaxAutoDrive TBR max for autodrive (if active)
     * @param smb30min SMB delivered in last 30min (for trigger logic)
     * @param predictionAvailable Is prediction available
     * @param inPrebolusWindow Is in prebolus window (P1/P2)
     * @param callback Optional callback with verdict and modulated decision
     */
    fun auditDecision(
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        glucoseStatus: GlucoseStatusAIMI?,
        iob: IobTotal,
        cob: Double?,
        profile: OapsProfileAimi,
        pkpdRuntime: PkPdRuntime?,
        isfUsed: Double,
        smbProposed: Double,
        tbrRate: Double?,
        tbrDuration: Int?,
        intervalMin: Double,
        maxSMB: Double,
        maxSMBHB: Double,
        maxIOB: Double,
        maxBasal: Double,
        reasonTags: List<String>,
        modeType: String?,
        modeRuntimeMin: Int?,
        autodriveState: String,
        wcyclePhase: String?,
        wcycleFactor: Double?,
        tbrMaxMode: Double?,
        tbrMaxAutoDrive: Double?,
        smb30min: Double,
        predictionAvailable: Boolean,
        inPrebolusWindow: Boolean,
        callback: ((AuditorVerdict?, DecisionModulator.ModulatedDecision) -> Unit)? = null
    ) {
        
        // Check if auditor is enabled
        if (!isAuditorEnabled()) {
            aapsLogger.debug(LTag.APS, "AI Auditor: Disabled")
            AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFF)
            callback?.invoke(null, createUnmodulatedDecision(smbProposed, tbrRate, tbrDuration, intervalMin, "Auditor disabled"))
            return
        }
        
        // Check if should trigger (intelligent gating)
        val shouldTrigger = DecisionModulator.shouldTriggerAudit(
            bg = bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            smbProposed = smbProposed,
            iob = iob.iob,
            smb30min = smb30min,
            predictionAvailable = predictionAvailable,
            inMealMode = modeType != null,
            inPrebolusWindow = inPrebolusWindow
        )
        
        if (!shouldTrigger) {
            aapsLogger.debug(LTag.APS, "AI Auditor: No trigger conditions met")
            AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.SKIPPED_NO_TRIGGER)
            callback?.invoke(null, createUnmodulatedDecision(smbProposed, tbrRate, tbrDuration, intervalMin, "No trigger"))
            return
        }
        
        // Check rate limiting
        val now = System.currentTimeMillis()
        if (!checkRateLimit(now)) {
            aapsLogger.debug(LTag.APS, "AI Auditor: Rate limited")
            AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.SKIPPED_RATE_LIMITED)
            callback?.invoke(null, createUnmodulatedDecision(smbProposed, tbrRate, tbrDuration, intervalMin, "Rate limited"))
            return
        }
        
        // Launch async audit
        scope.launch {
            try {
                // Build input
                val input = dataCollector.buildAuditorInput(
                    bg = bg,
                    delta = delta,
                    shortAvgDelta = shortAvgDelta,
                    longAvgDelta = longAvgDelta,
                    glucoseStatus = glucoseStatus,
                    iob = iob,
                    cob = cob,
                    profile = profile,
                    pkpdRuntime = pkpdRuntime,
                    isfUsed = isfUsed,
                    smbProposed = smbProposed,
                    tbrRate = tbrRate,
                    tbrDuration = tbrDuration,
                    intervalMin = intervalMin,
                    maxSMB = maxSMB,
                    maxSMBHB = maxSMBHB,
                    maxIOB = maxIOB,
                    maxBasal = maxBasal,
                    reasonTags = reasonTags,
                    modeType = modeType,
                    modeRuntimeMin = modeRuntimeMin,
                    autodriveState = autodriveState,
                    wcyclePhase = wcyclePhase,
                    wcycleFactor = wcycleFactor,
                    tbrMaxMode = tbrMaxMode,
                    tbrMaxAutoDrive = tbrMaxAutoDrive
                )
                
                // Get provider
                val provider = getProvider()
                
                // Get timeout from preferences
                val timeoutMs = preferences.get(IntKey.AimiAuditorTimeoutSeconds) * 1000L
                
                // Call AI
                val verdict = aiService.getVerdict(input, provider, timeoutMs)
                
                // Update rate limiting
                updateRateLimit(now)
                
                if (verdict != null) {
                    aapsLogger.info(LTag.APS, "AI Auditor: Verdict=${verdict.verdict}, Confidence=${String.format("%.2f", verdict.confidence)}")
                    aapsLogger.info(LTag.APS, "AI Auditor: Evidence=${verdict.evidence}")
                    aapsLogger.info(LTag.APS, "AI Auditor: RiskFlags=${verdict.riskFlags}")
                    
                    // Update status based on verdict type
                    val status = when (verdict.verdict) {
                        VerdictType.CONFIRM -> AuditorStatusTracker.Status.OK_CONFIRM
                        VerdictType.SOFTEN -> AuditorStatusTracker.Status.OK_SOFTEN
                        VerdictType.SHIFT_TO_TBR -> AuditorStatusTracker.Status.OK_PREFER_TBR
                    }
                    AuditorStatusTracker.updateStatus(status)
                    
                    // Apply modulation
                    val modulated = DecisionModulator.applyModulation(
                        originalSmb = smbProposed,
                        originalTbrRate = tbrRate,
                        originalTbrMin = tbrDuration,
                        originalIntervalMin = intervalMin,
                        verdict = verdict,
                        confidence = preferences.get(IntKey.AimiAuditorMinConfidence) / 100.0,
                        mode = getModulationMode()
                    )
                    
                    aapsLogger.info(LTag.APS, "AI Auditor: Modulation=${modulated.modulationReason}")
                    
                    // Cache verdict (internal)
                    lastVerdict = verdict
                    lastVerdictTime = now
                    
                    // Update global cache for RT instrumentation
                    AuditorVerdictCache.update(verdict, modulated)
                    
                    callback?.invoke(verdict, modulated)
                } else {
                    aapsLogger.warn(LTag.APS, "AI Auditor: No verdict received (timeout or error)")
                    AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_TIMEOUT)
                    callback?.invoke(null, createUnmodulatedDecision(smbProposed, tbrRate, tbrDuration, intervalMin, "No verdict"))
                }
                
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "AI Auditor: Exception", e)
                AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_EXCEPTION)
                callback?.invoke(null, createUnmodulatedDecision(smbProposed, tbrRate, tbrDuration, intervalMin, "Exception: ${e.message}"))
            }
        }
    }
    
    /**
     * Check if auditor is enabled in preferences
     */
    private fun isAuditorEnabled(): Boolean {
        return preferences.get(BooleanKey.AimiAuditorEnabled)
    }
    
    /**
     * Get selected AI provider from preferences
     */
    private fun getProvider(): AuditorAIService.Provider {
        val providerName = preferences.get(StringKey.AimiAdvisorProvider)
        return when (providerName.uppercase()) {
            "OPENAI" -> AuditorAIService.Provider.OPENAI
            "GEMINI" -> AuditorAIService.Provider.GEMINI
            "DEEPSEEK" -> AuditorAIService.Provider.DEEPSEEK
            "CLAUDE" -> AuditorAIService.Provider.CLAUDE
            else -> AuditorAIService.Provider.GEMINI  // Default
        }
    }
    
    /**
     * Get modulation mode from preferences
     */
    private fun getModulationMode(): DecisionModulator.ModulationMode {
        val modeStr = preferences.get(StringKey.AimiAuditorMode)
        return when (modeStr) {
            "AUDIT_ONLY" -> DecisionModulator.ModulationMode.AUDIT_ONLY
            "SOFT_MODULATION" -> DecisionModulator.ModulationMode.SOFT_MODULATION
            "HIGH_RISK_ONLY" -> DecisionModulator.ModulationMode.HIGH_RISK_ONLY
            else -> DecisionModulator.ModulationMode.AUDIT_ONLY  // Default
        }
    }
    
    /**
     * Check rate limit (per-hour and minimum interval)
     */
    private fun checkRateLimit(now: Long): Boolean {
        val maxAuditsPerHour = preferences.get(IntKey.AimiAuditorMaxPerHour)
        
        // Reset hour window if needed
        if (now - hourWindowStart > 60 * 60 * 1000L) {
            hourWindowStart = now
            auditsThisHour = 0
        }
        
        // Check per-hour limit
        if (auditsThisHour >= maxAuditsPerHour) {
            return false
        }
        
        // Check minimum interval
        if (now - lastAuditTime < MIN_AUDIT_INTERVAL_MS) {
            return false
        }
        
        return true
    }
    
    /**
     * Update rate limiting counters
     */
    private fun updateRateLimit(now: Long) {
        lastAuditTime = now
        auditsThisHour++
    }
    
    /**
     * Create unmodulated decision (original decision unchanged)
     */
    private fun createUnmodulatedDecision(
        smb: Double,
        tbrRate: Double?,
        tbrMin: Int?,
        intervalMin: Double,
        reason: String
    ): DecisionModulator.ModulatedDecision {
        return DecisionModulator.ModulatedDecision(
            smbU = smb,
            tbrRate = tbrRate,
            tbrMin = tbrMin,
            intervalMin = intervalMin,
            preferTbr = false,
            appliedModulation = false,
            modulationReason = reason
        )
    }
}
