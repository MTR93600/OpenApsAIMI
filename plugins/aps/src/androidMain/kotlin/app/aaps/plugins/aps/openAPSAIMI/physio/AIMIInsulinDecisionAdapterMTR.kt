package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiHealthContext
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPhysioSource
import android.os.Looper
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.data.model.TE
import app.aaps.plugins.aps.openAPSAIMI.physio.gate.CosineTrajectoryGate
import app.aaps.plugins.aps.openAPSAIMI.physio.GateInput
import app.aaps.plugins.aps.openAPSAIMI.physio.SleepLiveDetector
import app.aaps.plugins.aps.openAPSAIMI.physio.KernelType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
@SingleIn(AppScope::class)
class AIMIInsulinDecisionAdapterMTR @Inject constructor(
    private val repo: AimiHealthContext,
    private val persistenceLayer: PersistenceLayer,
    private val dataRepository: AimiPhysioSource,
    private val contextStore: AIMIPhysioContextStoreMTR,
    private val relevanceGate: CosineTrajectoryGate, // 🌀 Relevance Gate (Trajectory Filter)
    private val aapsLogger: AAPSLogger
) {
    private val inflammationEstimator = InflammationLatentEstimatorMTR()
    private val decisionOrchestratorShadow = AIMIDecisionOrchestratorShadowMTR()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hypoEventsRef = AtomicReference<List<TE>>(emptyList())
    private val hypoEventsRefreshInFlight = AtomicBoolean(false)
    private val activityRef = AtomicReference(RealTimeActivity(0, 0))
    private val activityRefreshInFlight = AtomicBoolean(false)
    @Volatile
    private var lastDecisionTrace: PhysioDecisionTraceMTR? = null
    
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

    /** Semantic physio context (sleep/HRV/state) for RBT pattern catalog and meta leaves. */
    fun getEffectiveContext(minConfidence: Double = 0.3): PhysioContextMTR =
        contextStore.getEffectiveContext(minConfidence) ?: PhysioContextMTR.NEUTRAL

    fun getLastDecisionTrace(): PhysioDecisionTraceMTR? = lastDecisionTrace

    fun setFinalLoopDecisionType(decisionType: String) {
        val current = lastDecisionTrace ?: return
        lastDecisionTrace = current.copy(finalLoopDecisionType = decisionType)
    }

    fun setSmbActionType(actionType: String) {
        val current = lastDecisionTrace ?: return
        val updated = current.copy(smbActionType = actionType)
        lastDecisionTrace = updated.copy(decisionConflictFlags = buildDecisionConflictFlags(updated))
    }

    fun setBasalActionType(actionType: String) {
        val current = lastDecisionTrace ?: return
        val updated = current.copy(basalActionType = actionType)
        lastDecisionTrace = updated.copy(decisionConflictFlags = buildDecisionConflictFlags(updated))
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
        recentHypoTimestamp: Long? = null,
        iob: Double = 0.0,
        cob: Double = 0.0
    ): PhysioMultipliersMTR {
        
        // Safety Check 1: BG too low
        if (currentBG < MIN_BG_FOR_MODULATION) {
            aapsLogger.warn(
                LTag.APS,
                "[$TAG] ⚠️ BG too low (${currentBG.toInt()} mg/dL) - skipping physio modulation"
            )
            logDecisionTrace(
                state = "UNKNOWN",
                confidence = 0.0,
                dataQuality = 0.0,
                sleepQualityScore = null,
                multipliers = PhysioMultipliersMTR.NEUTRAL,
                vetoReason = "bg_below_min_threshold"
            )
            return PhysioMultipliersMTR.NEUTRAL
        }
        
        // Safety Check 2: Recent hypoglycemia
        if (hasRecentHypoglycemia(recentHypoTimestamp)) {
            aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ Recent hypoglycemia detected - skipping modulation")
            logDecisionTrace(
                state = "UNKNOWN",
                confidence = 0.0,
                dataQuality = 0.0,
                sleepQualityScore = null,
                multipliers = PhysioMultipliersMTR.NEUTRAL,
                vetoReason = "recent_hypoglycemia"
            )
            return PhysioMultipliersMTR.NEUTRAL
        }

        ensurePhysioSnapshotRefreshed()

        // Get current snapshot (still needed for Real-Time Activity Brake)
        val snapshot = repo.getLastSnapshot() 
        
        // Get formulated Physio Context (Semantic Brain)
        // We accept lower confidence (0.3) because we default to 1.0 (Neutral) anyway
        // This ensures availability even during "building baseline" phase
        val physioContext = contextStore.getEffectiveContext(0.3) ?: PhysioContextMTR.NEUTRAL

        if (!snapshot.isValid) {
            //aapsLogger.debug(LTag.APS, "[$TAG] No valid snapshot - returning NEUTRAL")
            logDecisionTrace(
                state = physioContext.state.name,
                confidence = physioContext.confidence,
                dataQuality = physioContext.features?.dataQuality ?: 0.0,
                sleepQualityScore = physioContext.features?.sleepQualityScore,
                multipliers = PhysioMultipliersMTR.NEUTRAL,
                vetoReason = "invalid_snapshot"
            )
            return PhysioMultipliersMTR.NEUTRAL
        }
        
        // Safety Check 3: Confidence too low
        if (snapshot.confidence < MIN_CONFIDENCE_THRESHOLD) {
             // Silently ignore low confidence
            logDecisionTrace(
                state = physioContext.state.name,
                confidence = snapshot.confidence,
                dataQuality = physioContext.features?.dataQuality ?: 0.0,
                sleepQualityScore = physioContext.features?.sleepQualityScore,
                multipliers = PhysioMultipliersMTR.NEUTRAL,
                vetoReason = "snapshot_confidence_below_threshold"
            )
            return PhysioMultipliersMTR.NEUTRAL
        }
        
        // 1. Calculate raw multipliers (Semantic + Tactical)
        val rawMultipliers = calculateRawMultipliers(snapshot, physioContext, currentBG, currentDelta)
        val inflammationLatent = inflammationEstimator.estimate(physioContext, snapshot)
        
        // 2. 🌀 Physiological Relevance Filter (based on Cosine Similarity)
        // This gate determines if the current physiological state is "relevant" enough
        // to justify trajectory-based modulation. 
        val gateInput = GateInput(
            bgCurrent = currentBG,
            bgDelta = currentDelta ?: 0.0,
            iob = iob,
            cob = cob,
            stepCount15m = snapshot.stepsLast15m,
            hrCurrent = snapshot.hrNow,
            hrvCurrent = snapshot.hrvRmssd,
            sleepState = snapshot.asleepLiveConfidence >= SleepLiveDetector.ASLEEP_THRESHOLD ||
                snapshot.hcSleepSessionActive,
            physioState = physioContext.state,
            dataQuality = snapshot.confidence
        )
        
        val gateOutcome = relevanceGate.compute(gateInput)
        val trajectoryRelevanceScore = gateOutcome.relevanceScore
        
        // Apply Modulation to Raw Multipliers
        // ISF: We multiply by effectiveSensitivityMultiplier.
        val modulatedISF = rawMultipliers.isfFactor * gateOutcome.effectiveSensitivityMultiplier
        
        // Basal/SMB: We typically don't modulate these with Cosine Gate yet (keeping it ISF focused for Phase 2),
        // or we can mirror the sensitivity effect (inverse).
        // For now, let's keep it strictly ISF + PeakShift as per plan ("effectiveSensitivityMultiplier... PeakTimeShift")
        
        // val trajectoryRelevanceScore = gateOutcome.relevanceScore (Already defined above)
        
        // TAP-G RFC G.2: Cosine gate peak shift lives only in [PhysioMultipliersMTR.peakShiftMinutes] and is fed
        // into [TapPeakGovernor] as physioPeakShiftMinutes — it is not re-applied as a second independent peak offset.
        val modulatedMultipliers = rawMultipliers.copy(
            isfFactor = modulatedISF,
            peakShiftMinutes = gateOutcome.peakTimeShiftMinutes,
            trajectoryRelevanceScore = trajectoryRelevanceScore,
            appliedCaps = if (gateOutcome.dominantKernel != KernelType.REST) 
                            "${rawMultipliers.appliedCaps} + CGate:${gateOutcome.dominantKernel}" 
                          else rawMultipliers.appliedCaps,
            source = "Semantic+Tactical+CGate",
            detailedReason = "🌀 CGate: ${gateOutcome.debug}" 
        )
        val shadowOrchestrator = decisionOrchestratorShadow.compute(
            rawMultipliers = rawMultipliers,
            cgateIsfMultiplier = gateOutcome.effectiveSensitivityMultiplier,
            inflammation = inflammationLatent,
        )
        
        // 3. Apply HARD CAPS (Final Safety Net)
        val cappedMultipliers = applyHardCaps(modulatedMultipliers)
        
        // COMPACT LOGING (User Request: "concis, en anglais, écran étroit")
        // "CGate: state=STRESS dq=0.82 sim=[R:0.12 A:0.41 S:0.77] w=[R:0.08 A:0.22 S:0.70] mult=0.92 shift=+8m"
        // "PHYSIO ctx: steps15=, hr=, hrv=, conf= -> brake=, stress= -> smbMult="
        
        if (!cappedMultipliers.isNeutral()) {
            // Log core physio
            val coreLog = "🏥 PHYSIO ctx: State=${physioContext.state} (${(physioContext.confidence*100).toInt()}%) | " + 
                         "steps15=${snapshot.stepsLast15m}, hr=${snapshot.hrNow} " +
                         "-> ${cappedMultipliers.appliedCaps} -> ISF x${cappedMultipliers.isfFactor.format(2)}, SMB x${cappedMultipliers.smbFactor.format(2)}"
            aapsLogger.info(LTag.APS, coreLog)
            
            // Log Relevance details if active
            if (gateOutcome.dominantKernel != KernelType.REST || abs(gateOutcome.effectiveSensitivityMultiplier - 1.0) > 0.05) {
                 aapsLogger.info(LTag.APS, "🌀 CGate: ${gateOutcome.debug}")
            }
        }
        logDecisionTrace(
            state = physioContext.state.name,
            confidence = physioContext.confidence,
            dataQuality = physioContext.features?.dataQuality ?: 0.0,
            sleepQualityScore = physioContext.features?.sleepQualityScore,
            multipliers = cappedMultipliers,
            inflammationLatent = inflammationLatent,
            shadowOrchestrator = shadowOrchestrator,
            vetoReason = null
        )
        
        return cappedMultipliers
    }

    private fun logDecisionTrace(
        state: String,
        confidence: Double,
        dataQuality: Double,
        sleepQualityScore: Double? = null,
        multipliers: PhysioMultipliersMTR,
        inflammationLatent: InflammationLatentStateMTR = InflammationLatentStateMTR(),
        shadowOrchestrator: AIMIDecisionOrchestratorShadowMTR.ShadowResult = AIMIDecisionOrchestratorShadowMTR.ShadowResult(),
        vetoReason: String?
    ) {
        lastDecisionTrace = PhysioDecisionTraceMTR(
            timestamp = System.currentTimeMillis(),
            physioState = state,
            physioConfidence = confidence,
            physioDataQuality = dataQuality,
            sleepQualityScore = sleepQualityScore,
            isfFactor = multipliers.isfFactor,
            basalFactor = multipliers.basalFactor,
            smbFactor = multipliers.smbFactor,
            reactivityFactor = multipliers.reactivityFactor,
            inflammationLatentIndex = inflammationLatent.index,
            inflammationConfidence = inflammationLatent.confidence,
            inflammationTimescale = inflammationLatent.timescale.name,
            inflammationDrivers = inflammationLatent.drivers,
            shadowOrchestratorEnabled = shadowOrchestrator.enabled,
            shadowBudgetedIsfFactor = shadowOrchestrator.budgetedIsfFactor,
            shadowBudgetedBasalFactor = shadowOrchestrator.budgetedBasalFactor,
            shadowBudgetedSmbFactor = shadowOrchestrator.budgetedSmbFactor,
            shadowOverlapPenalty = shadowOrchestrator.overlapPenalty,
            shadowContributions = shadowOrchestrator.contributions,
            shadowNotes = shadowOrchestrator.notes,
            vetoReason = vetoReason,
            finalLoopDecisionType = null,
            smbActionType = null,
            basalActionType = null,
            decisionConflictFlags = emptyList(),
            source = multipliers.source
        )
        aapsLogger.info(
            LTag.APS,
            "PHYSIO_DECISION state=$state conf=${confidence.format(2)} " +
                "quality=${dataQuality.format(2)} isf=${multipliers.isfFactor.format(3)} " +
                "basal=${multipliers.basalFactor.format(3)} smb=${multipliers.smbFactor.format(3)} " +
                "sleepQ=${sleepQualityScore?.format(3) ?: "na"} " +
                "inflam=${inflammationLatent.index.format(3)}(${inflammationLatent.timescale.name}) " +
                "shadowSmb=${shadowOrchestrator.budgetedSmbFactor.format(3)} " +
                "react=${multipliers.reactivityFactor.format(3)} " +
                "veto=${vetoReason ?: "none"} source=${multipliers.source}"
        )
    }

    private fun buildDecisionConflictFlags(trace: PhysioDecisionTraceMTR): List<String> {
        val flags = mutableListOf<String>()
        val hasSmb = trace.smbActionType == "smb"
        val basalAction = trace.basalActionType
        if (hasSmb && basalAction != null && basalAction != "none") {
            flags += "dual_delivery"
            flags += "dual_delivery_$basalAction"
        }
        return flags
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
            source = multipliers.source,
            detailedReason = multipliers.detailedReason
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
            refreshHypoEventsAsync(now)
            val events = hypoEventsRef.get()
            val hypoEvents = events.filter { event ->
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
         refreshActivityAsync()
         return activityRef.get()
    }

    private fun refreshHypoEventsAsync(now: Long) {
        if (!hypoEventsRefreshInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                hypoEventsRef.set(
                    persistenceLayer.getTherapyEventDataFromTime(
                        now - RECENT_HYPO_WINDOW_MS,
                        TE.Type.NOTE,
                        ascending = false
                    )
                )
            } catch (_: Exception) {
                hypoEventsRef.set(emptyList())
            } finally {
                hypoEventsRefreshInFlight.set(false)
            }
        }
    }

    private fun refreshActivityAsync() {
        if (!activityRefreshInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                activityRef.set(
                    RealTimeActivity(
                        stepsToday = dataRepository.fetchStepsData(0),
                        heartRate = dataRepository.fetchLastHeartRate()
                    )
                )
            } catch (_: Exception) {
                activityRef.set(RealTimeActivity(0, 0))
            } finally {
                activityRefreshInFlight.set(false)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)

    /**
     * [HealthContextRepository.fetchSnapshot] reads steps/HR via DB on a non-main thread
     * ([UnifiedActivityProviderMTR] skips reads on the UI looper). Uses [runBlocking] on the
     * main looper only so a brief UI stall is possible; loop/APS should prefer a background thread.
     */
    private fun ensurePhysioSnapshotRefreshed() {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runBlocking(Dispatchers.IO) { repo.fetchSnapshot() }
            } else {
                repo.fetchSnapshot()
            }
        } catch (_: Exception) {
            // Physio must not fail the loop tick if merge/read throws.
        }
    }
    
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
        ensurePhysioSnapshotRefreshed()
        val snapshot = repo.getLastSnapshot()
        
        if (!snapshot.isValid || snapshot.timestamp == 0L) {
            return "🏥 Physio: NO DATA / WAITING | Check Health Connect permissions & Sync"
        }
        
        val ageMin = (System.currentTimeMillis() - snapshot.timestamp) / 60000
        val sb = StringBuilder()
        
        // Header
        sb.append("🏥 Physio Status (${ageMin}m ago) | Conf: ${(snapshot.confidence * 100).toInt()}%")
        
        // Activity: 5m reacts faster to recent walking; 15m matches modulation window. ACTIVE if ≥200 steps/5m
        // (a burst = already on the move) or ≥375 steps/15m (sustained). The effort belief drives the protection.
        sb.append(
            "\n🏃 Activity: ${snapshot.stepsLast5m} steps/5m | ${snapshot.stepsLast15m} steps/15m " +
                "(State: ${snapshot.activityState}, ACTIVE if ≥200/5m or ≥375/15m)"
        )
        
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
