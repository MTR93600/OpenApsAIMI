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
    private val aapsLogger: AAPSLogger,
    private val physioAdapter: app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR
) {
    
    // Coroutine scope for async operations
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Cache for last verdict (to avoid redundant calls)
    private var lastVerdict: AuditorVerdict? = null
    private var lastVerdictTime: Long = 0L
    private val VERDICT_CACHE_MS = 5 * 60 * 1000L // 5 minutes
    
    // Rate limiting
    private var lastAuditTime: Long = 0L
    private val MIN_AUDIT_INTERVAL_ROUTINE_MS = 60 * 60 * 1000L // 60 min (Routine)
    private val MIN_AUDIT_INTERVAL_DRIFT_MS = 45 * 60 * 1000L // 45 min (Slow Drift)
    private val MIN_AUDIT_INTERVAL_RISK_MS = 30 * 60 * 1000L // 30 min (High Risk)
    private val MIN_AUDIT_INTERVAL_MEAL_MS = 15 * 60 * 1000L // 15 min (Meal/Bolus)
    
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
        predictedBg: Double?,
        eventualBg: Double?,
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
        
        val now = System.currentTimeMillis()
        
        // ================================================================
        // DUAL-BRAIN TIER 1: LOCAL SENTINEL (Offline, Always Active)
        // ================================================================
        
        // Calculate Sentinel inputs
        val smbCount30 = DualBrainHelpers.calculateSmbCount30min(iob, now)
        val smbTotal60 = DualBrainHelpers.calculateSmbTotal60min(iob, now)
        val lastBolusAge = if (iob.lastBolusTime > 0) (now - iob.lastBolusTime) / 60000.0 else 999.0
        val bgHistory = DualBrainHelpers.extractBgHistory(glucoseStatus)
        
        // Compute Sentinel advice (always runs, even if External disabled/rate-limited)
        val sentinelAdvice = LocalSentinel.computeAdvice(
            bg = bg,
            target = profile.target_bg,
            delta = delta,
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            predictedBg = predictedBg,  // Fixed: Replaced null // TODO Phase 3
            eventualBg = eventualBg,    // Fixed: Replaced null // TODO Phase 3
            predBgsAvailable = predictionAvailable,
            iobTotal = iob.iob,
            iobActivity = iob.activity,
            pkpdStage = pkpdRuntime?.activity?.stage?.name,
            lastBolusAgeMin = lastBolusAge,
            smbCount30min = smbCount30,
            smbTotal60min = smbTotal60,
            smbProposed = smbProposed,
            noise = (glucoseStatus?.noise ?: 0.0).toInt(),
            isStale = false,  // TODO Phase 3
            pumpUnreachable = false,
            autodriveActive = autodriveState.contains("ACTIVE"),
            modeActive = modeType != null,
            bgHistory = bgHistory
        )
        
        aapsLogger.info(LTag.APS, "🔍 Sentinel: tier=${sentinelAdvice.tier} score=${sentinelAdvice.score} reason=${sentinelAdvice.reason}")
        sentinelAdvice.details.take(3).forEach { aapsLogger.debug(LTag.APS, "  └─ $it") }
        
        // ================================================================
        // DUAL-BRAIN TIER 2: EXTERNAL AUDITOR (Conditional, API)
        // ================================================================
        
        // Contextes nécessitant une trajectoire fraîche (Repas actif, COB, ou Autosens instable)
        val isMealContext = (cob ?: 0.0) > 0.0 || modeType != null || inPrebolusWindow
        val isStale = (now - lastVerdictTime) > 15 * 60 * 1000L // 15 minutes
        
        // Force update si contexte repas ET donnée vieille, MÊME si Sentinel dit "Low Risk"
        // Cela garantit que l'IA peut réévaluer situation après 15 min même si Sentinel dort
        val forceExternal = isMealContext && isStale

        // Determine if External should be called (only if Sentinel tier >= HIGH OR Forced Update)
        val shouldCallExternal = sentinelAdvice.tier == LocalSentinel.Tier.HIGH || forceExternal
        
        if (forceExternal && sentinelAdvice.tier != LocalSentinel.Tier.HIGH) {
             aapsLogger.info(LTag.APS, "🌐 External: FORCED UPDATE (MealContext + Stale > 15m)")
        }
        
        if (!shouldCallExternal) {
            // Sentinel tier < HIGH: Apply Sentinel advice only, no External call
            aapsLogger.info(LTag.APS, "🌐 External: Skipped (Sentinel tier=${sentinelAdvice.tier})")
            
            // 🔧 FIX: Update status tracker to reflect Sentinel-only operation
            AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OK_CONFIRM)
            
            val combined = DualBrainHelpers.combineAdvice(sentinelAdvice, null)
            val modulated = combined.toModulatedDecision(smbProposed, tbrRate, tbrDuration, intervalMin)
            aapsLogger.info(LTag.APS, "✅ ${combined.toLogString()}")
            
            callback?.invoke(null, modulated)
            return
        }
        
        // Sentinel tier HIGH: Check Smart Rate Limiting for External Auditor
        
        // Determine Trigger Type & Complexity
        val triggerType = determineTriggerType(bg, delta, shortAvgDelta, modeType != null, inPrebolusWindow, profile.target_bg)
        
        if (triggerType == TriggerType.NONE) {
            aapsLogger.info(LTag.APS, "🌐 External: Skipped (No valid trigger)")
            AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.SKIPPED_NO_TRIGGER)
            callback?.invoke(null, createUnmodulatedDecision(smbProposed, tbrRate, tbrDuration, intervalMin, "No Trigger"))
            return
        }
        
        if (!checkSmartRateLimit(now, triggerType)) {
            aapsLogger.info(LTag.APS, "🌐 External: Rate limited ($triggerType), using Sentinel only")
            AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.SKIPPED_RATE_LIMITED)
            val combined = DualBrainHelpers.combineAdvice(sentinelAdvice, null)
            val modulated = combined.toModulatedDecision(smbProposed, tbrRate, tbrDuration, intervalMin)
            aapsLogger.info(LTag.APS, "✅ ${combined.toLogString()}")
            callback?.invoke(null, modulated)
            return
        }
        
        aapsLogger.info(LTag.APS, "🌐 External: TRIGGERED ($triggerType)")

        // Get physio snapshot (safe call)
        val physioCtx = try { physioAdapter.getLatestSnapshot().toStartSnapshot() } catch (e: Exception) { null }

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
                    tbrMaxAutoDrive = tbrMaxAutoDrive,
                    physio = physioCtx
                )
                
                // Get provider
                val provider = getProvider()
                
                // Get timeout from preferences
                val timeoutMs = preferences.get(IntKey.AimiAuditorTimeoutSeconds) * 1000L
                
                // Call AI (Pass complexity flag)
                val useHighPerf = (triggerType == TriggerType.HIGH_RISK || triggerType == TriggerType.MEAL)
                val verdict = aiService.getVerdict(input, provider, timeoutMs, useHighPerf)
                
                // Update rate limiting
                updateRateLimit(now, triggerType)
                
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
     * Bypass if BG > 160 (Emergency Audit)
     */
    enum class TriggerType { NONE, ROUTINE, SLOW_DRIFT, HIGH_RISK, MEAL }

    private fun determineTriggerType(
        bg: Double, delta: Double, shortAvg: Double, 
        inMeal: Boolean, inPrebolus: Boolean, target: Double
    ): TriggerType {
        // 1. Meal / Prebolus (Highest Priority for responsiveness)
        if (inMeal || inPrebolus) return TriggerType.MEAL
        
        // 2. High Risk (Rapid Rise at High BG)
        if (bg > 180 && delta > 8.0) return TriggerType.HIGH_RISK
        
        // 3. Slow Drift (Gentle Rise above target)
        if (bg > (target + 30) && shortAvg > 2.0) return TriggerType.SLOW_DRIFT
        
        // 4. Routine (Health Check)
        return TriggerType.ROUTINE
    }

    private fun checkSmartRateLimit(now: Long, type: TriggerType): Boolean {
        // Global Hour Limit Check
        val maxAuditsPerHour = preferences.get(IntKey.AimiAuditorMaxPerHour)
        if (now - hourWindowStart > 60 * 60 * 1000L) {
            hourWindowStart = now
            auditsThisHour = 0
        }
        if (auditsThisHour >= maxAuditsPerHour) return false

        // Interval Check based on Type
        val minInterval = when(type) {
            TriggerType.MEAL -> MIN_AUDIT_INTERVAL_MEAL_MS
            TriggerType.HIGH_RISK -> MIN_AUDIT_INTERVAL_RISK_MS
            TriggerType.SLOW_DRIFT -> MIN_AUDIT_INTERVAL_DRIFT_MS
            TriggerType.ROUTINE -> MIN_AUDIT_INTERVAL_ROUTINE_MS
            else -> MIN_AUDIT_INTERVAL_ROUTINE_MS
        }
        
        return (now - lastAuditTime) >= minInterval
    }

    private fun updateRateLimit(now: Long, type: TriggerType) {
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

    private fun app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot.toStartSnapshot(): PhysioSnapshot {
        // Map simplified Snapshot to Auditor's view
        return PhysioSnapshot(
            state = this.activityState, // Mapping ActivityState to State string
            snsDominance = this.toSNSDominance(),
            sleepQualityZ = 0.0, // Detailed Z-scores not available in simple snapshot, using 0 (nominal)
            rhrZ = 0.0, 
            hrvZ = 0.0
        )
    }
}
