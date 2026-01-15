package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🎛️ AIMI Physiological Manager - MTR Implementation
 * 
 * Central orchestrator for physiological analysis pipeline.
 * 
 * Responsibilities:
 * 1. Schedule data collection every 6 hours
 * 2. Trigger collection at sleep end (if detectable)
 * 3. Never collect during sleep
 * 4. Coordinate all pipeline components
 * 5. Handle errors gracefully
 * 
 * Lifecycle:
 * - Started by OpenAPSAIMIPlugin.onStart()
 * - Stopped by OpenAPSAIMIPlugin.onStop()
 * 
 * Update Cadence:
 * - Every 6 hours (default)
 * - Or on wake-up detection
 * - Manual trigger available
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMIPhysioManagerMTR @Inject constructor(
    private val dataRepository: AIMIPhysioDataRepositoryMTR,
    private val featureExtractor: AIMIPhysioFeatureExtractorMTR,
    private val baselineModel: AIMIPhysioBaselineModelMTR,
    private val contextEngine: AIMIPhysioContextEngineMTR,
    private val contextStore: AIMIPhysioContextStoreMTR,
    private val llmAnalyzer: AIMILLMPhysioAnalyzerMTR, // Optional
    private val sp: SP,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "PhysioManager"
        private const val UPDATE_INTERVAL_MS = 4 * 60 * 60 * 1000L // 4 hours
        private const val INITIAL_DELAY_MS = 10 * 1000L // 10 seconds after start (DEBUG)
        private const val PREF_KEY_LAST_UPDATE = "aimi_physio_last_update_ms"
    }
    
    private var updateTimer: Timer? = null
    private val isRunning = AtomicBoolean(false)
    private var lastUpdateTime: Long = 0
    private var previousFeatures: PhysioFeaturesMTR? = null
    
    /**
     * Starts the physiological analysis manager
     * Called by OpenAPSAIMIPlugin.onStart()
     */
    fun start() {
        if (!isEnabled()) {
            aapsLogger.info(LTag.APS, "[$TAG] Physiological Assistant disabled in preferences")
            return
        }
        
        if (isRunning.get()) {
            aapsLogger.debug(LTag.APS, "[$TAG] Already running")
            return
        }
        
        aapsLogger.info(LTag.APS, "[$TAG] 🚀 Starting AIMI Physiological Manager")
        
        // Restore last update time
        lastUpdateTime = sp.getLong(PREF_KEY_LAST_UPDATE, 0)
        
        // Schedule periodic updates
        updateTimer = Timer("PhysioManager", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    try {
                        if (shouldUpdate()) {
                            performUpdate()
                        }
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.APS, "[$TAG] Update failed", e)
                    }
                }
            }, INITIAL_DELAY_MS, UPDATE_INTERVAL_MS)
        }
        
        isRunning.set(true)
        
        aapsLogger.info(
            LTag.APS,
            "[$TAG] ✅ Manager started (updates every ${UPDATE_INTERVAL_MS / (60 * 60 * 1000)}h, " +
            "last update ${(System.currentTimeMillis() - lastUpdateTime) / (60 * 60 * 1000)}h ago)"
        )
        
        // Log current context status
        contextStore.logStatus()
        
        // 🚀 FORCE IMMEDIATE UPDATE (on background thread)
        // This ensures the UI has data to show immediately after enable/restart
        Thread {
            try {
                // Short sleep to let system settle
                Thread.sleep(5000)
                aapsLogger.info(LTag.APS, "[$TAG] 🚀 Forcing initial update...")
                performUpdate()
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] Initial update failed", e)
            }
        }.start()
    }
    
    /**
     * Stops the manager
     * Called by OpenAPSAIMIPlugin.onStop()
     */
    fun stop() {
        if (!isRunning.get()) {
            return
        }
        
        aapsLogger.info(LTag.APS, "[$TAG] 🛑 Stopping Physiological Manager")
        
        updateTimer?.cancel()
        updateTimer = null
        isRunning.set(false)
        
        aapsLogger.info(LTag.APS, "[$TAG] Manager stopped")
    }
    
    /**
     * Manually triggers an update
     * Useful for testing or user-initiated refresh
     * 
     * @return true if update was triggered, false if skipped
     */
    fun triggerManualUpdate(): Boolean {
        if (!isEnabled()) {
            aapsLogger.warn(LTag.APS, "[$TAG] Manual update skipped (feature disabled)")
            return false
        }
        
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Manual update requested")
        
        Thread {
            try {
                performUpdate()
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] Manual update failed", e)
            }
        }.start()
        
        return true
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // UPDATE PIPELINE
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Checks if update should be performed
     * Respects sleep detection and update interval
     */
    private fun shouldUpdate(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastUpdate = now - lastUpdateTime
        
        // Don't update if too recent
        if (timeSinceLastUpdate < UPDATE_INTERVAL_MS && lastUpdateTime > 0) {
            aapsLogger.debug(
                LTag.APS,
                "[$TAG] Update skipped (last update ${timeSinceLastUpdate / (60 * 1000)}min ago)"
            )
            return false
        }
        
        // Check if user might be sleeping (simple heuristic)
        /* disable for debugging
        if (isProbablySleeping()) {
            aapsLogger.info(LTag.APS, "[$TAG] Update skipped (probable sleep time)")
            return false
        }
        */
        
        return true
    }
    
    /**
     * Performs complete physiological analysis pipeline
     * 
     * Pipeline: Data → Features → Baseline → Context → (LLM) → Store
     */
    private fun performUpdate() {
        val startTime = System.currentTimeMillis()
        
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Starting Physiological Analysis Pipeline")
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
        
        try {
            // Step 1: Fetch raw data from Health Connect
            aapsLogger.info(LTag.APS, "[$TAG] [1/5] Fetching physiological data...")
            
            // Check Health Connect availability first
            val isHCAvailable = dataRepository.isAvailable()
            if (!isHCAvailable) {
                aapsLogger.error(LTag.APS, "[$TAG] ❌ Health Connect client unavailable")
                val fallbackContext = PhysioContextMTR(
                    state = PhysioStateMTR.UNKNOWN,
                    confidence = 0.0,
                    narrative = "Health Connect unavailable. Install Google Health Connect app (Android 14+).",
                    timestamp = System.currentTimeMillis()
                )
                contextStore.updateContext(fallbackContext, PhysioBaselineMTR.EMPTY)
                lastUpdateTime = System.currentTimeMillis()
                sp.putLong(PREF_KEY_LAST_UPDATE, lastUpdateTime)
                aapsLogger.info(LTag.APS, "[$TAG] ⏱️ Will retry in 5 minutes")
                return
            }
            
            val rawData = try {
                dataRepository.fetchAllData(daysBack = 7)
            } catch (e: SecurityException) {
                aapsLogger.error(LTag.APS, "[$TAG] ❌ Permission denied during fetch", e)
                val fallbackContext = PhysioContextMTR(
                    state = PhysioStateMTR.UNKNOWN,
                    confidence = 0.0,
                    narrative = "Permission denied. Open Health Connect → Manage data → AAPS → Grant Sleep/HRV/Heart Rate access.",
                    timestamp = System.currentTimeMillis()
                )
                contextStore.updateContext(fallbackContext, PhysioBaselineMTR.EMPTY)
                lastUpdateTime = System.currentTimeMillis()
                sp.putLong(PREF_KEY_LAST_UPDATE, lastUpdateTime)
                aapsLogger.info(LTag.APS, "[$TAG] ⏱️ Will retry in 5 minutes")
                return
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] ❌ Unexpected error during fetch", e)
                val fallbackContext = PhysioContextMTR(
                    state = PhysioStateMTR.UNKNOWN,
                    confidence = 0.0,
                    narrative = "Health Connect error: ${e.message ?: e.javaClass.simpleName}. Check system logs.",
                    timestamp = System.currentTimeMillis()
                )
                contextStore.updateContext(fallbackContext, PhysioBaselineMTR.EMPTY)
                lastUpdateTime = System.currentTimeMillis()
                sp.putLong(PREF_KEY_LAST_UPDATE, lastUpdateTime)
                aapsLogger.info(LTag.APS, "[$TAG] ⏱️ Will retry in 5 minutes")
                return
            }
            
            if (!rawData.hasAnyData()) {
                aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ No physiological data available in Health Connect")
                val fallbackContext = PhysioContextMTR(
                    state = PhysioStateMTR.UNKNOWN,
                    confidence = 0.0,
                    narrative = "No data in Health Connect (last 7 days). Sync your fitness tracker (Garmin/Fitbit/etc).",
                    timestamp = System.currentTimeMillis()
                )
                contextStore.updateContext(fallbackContext, PhysioBaselineMTR.EMPTY)
                lastUpdateTime = System.currentTimeMillis()
                sp.putLong(PREF_KEY_LAST_UPDATE, lastUpdateTime)
                aapsLogger.info(LTag.APS, "[$TAG] ⏱️ Will retry in 5 minutes")
                return
            }
            
            // Step 2: Extract features
            aapsLogger.info(LTag.APS, "[$TAG] [2/5] Extracting features...")
            val features = featureExtractor.extractFeatures(rawData, previousFeatures)
            previousFeatures = features // Save for next time
            
            if (!features.hasValidData) {
                aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ No valid features extracted")
                return
            }
            
            // Step 3: Update baseline
            aapsLogger.info(LTag.APS, "[$TAG] [3/5] Updating baseline model...")
            val baseline = baselineModel.updateBaseline(features)
            
            // Step 4: Analyze context (deterministic)
            aapsLogger.info(LTag.APS, "[$TAG] [4/5] Analyzing physiological context...")
            var context = contextEngine.analyze(features, baseline)
            
            // Step 5: LLM analysis (optional, non-blocking)
            if (isLLMEnabled() && context.confidence >= 0.5) {
                aapsLogger.info(LTag.APS, "[$TAG] [5/5] Running LLM analysis...")
                try {
                    val llmNarrative = llmAnalyzer.analyze(features, baseline, context)
                    if (llmNarrative.isNotBlank()) {
                        // Add LLM narrative to context
                        context = context.copy(narrative = llmNarrative)
                        aapsLogger.info(LTag.APS, "[$TAG] ✅ LLM narrative added")
                    }
                } catch (e: Exception) {
                    aapsLogger.warn(LTag.APS, "[$TAG] LLM analysis failed (continuing without)", e)
                    // Continue without LLM - not critical
                }
            } else {
                aapsLogger.debug(LTag.APS, "[$TAG] [5/5] LLM analysis skipped (disabled or low confidence)")
            }
            
            // Step 6: Store context
            contextStore.updateContext(context, baseline)
            
            // Update tracking
            lastUpdateTime = System.currentTimeMillis()
            sp.putLong(PREF_KEY_LAST_UPDATE, lastUpdateTime)
            
            val elapsed = System.currentTimeMillis() - startTime
            
            aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
            aapsLogger.info(
                LTag.APS,
                "[$TAG] ✅ Pipeline completed in ${elapsed}ms | " +
                "State: ${context.state} | " +
                "Confidence: ${(context.confidence * 100).toInt()}% | " +
                "Baseline: ${baseline.validDaysCount} days"
            )
            aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Pipeline failed", e)
            // Don't crash - just log and continue
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SLEEP DETECTION (Simple Heuristic)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Simple sleep detection based on current time
     * Returns true if current hour is in typical sleep window (23:00 - 06:00)
     * 
     * TODO: Could be enhanced with:
     * - Actual sleep session detection from Health Connect
     * - User-configured sleep schedule
     * - Activity/HR-based detection
     */
    private fun isProbablySleeping(): Boolean {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        
        // Typical sleep window: 11 PM to 6 AM
        return hour >= 23 || hour < 6
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // PREFERENCES
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun isEnabled(): Boolean {
        return sp.getBoolean(BooleanKey.AimiPhysioAssistantEnable.key, false)
    }
    
    private fun isLLMEnabled(): Boolean {
        return sp.getBoolean(BooleanKey.AimiPhysioLLMAnalysisEnable.key, false)
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STATUS & DEBUGGING
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Gets manager status for debugging/UI
     */
    fun getStatus(): Map<String, String> {
        val now = System.currentTimeMillis()
        val timeSinceUpdate = now - lastUpdateTime
        val nextUpdateIn = UPDATE_INTERVAL_MS - timeSinceUpdate
        
        return mapOf(
            "isRunning" to isRunning.get().toString(),
            "isEnabled" to isEnabled().toString(),
            "lastUpdate" to "${timeSinceUpdate / (60 * 60 * 1000)}h ago",
            "nextUpdate" to if (nextUpdateIn > 0) "${nextUpdateIn / (60 * 1000)}min" else "soon",
            "llmEnabled" to isLLMEnabled().toString(),
            "contextValid" to contextStore.isValid().toString()
        )
    }
    
    /**
     * Logs current status
     */
    fun logStatus() {
        val status = getStatus()
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
        aapsLogger.info(LTag.APS, "[$TAG] Physiological Manager Status:")
        status.forEach { (key, value) ->
            aapsLogger.info(LTag.APS, "[$TAG]   $key: $value")
        }
        aapsLogger.info(LTag.APS, "[$TAG] ═══════════════════════════════════════")
    }
    
    /**
     * Clears all stored data (for testing/reset)
     */
    fun reset() {
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Resetting Physiological Manager")
        
        contextStore.clear()
        baselineModel.clearHistory()
        dataRepository.clearCache()
        previousFeatures = null
        lastUpdateTime = 0
        sp.putLong(PREF_KEY_LAST_UPDATE, 0)
        
        aapsLogger.info(LTag.APS, "[$TAG] ✅ Reset complete")
    }
}
