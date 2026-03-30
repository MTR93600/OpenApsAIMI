package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.steps.UnifiedActivityProviderMTR
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🎛️ AIMI Physiological Manager - MTR Implementation
 * 
 * Central orchestrator for physiological analysis pipeline.
 * Uses WorkManager for reliable periodic execution (every 15 minutes).
 * 
 * Responsibilities:
 * 1. Schedule data collection every 15 minutes via WorkManager
 * 2. Coordinate all pipeline components
 * 3. Handle errors gracefully
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMIPhysioManagerMTR @Inject constructor(
    private val context: Context,
    val repo: HealthContextRepository, // Public for Workers
    private val dataRepository: AIMIPhysioDataRepositoryMTR,
    private val featureExtractor: AIMIPhysioFeatureExtractorMTR,
    private val baselineModel: AIMIPhysioBaselineModelMTR,
    private val contextEngine: AIMIPhysioContextEngineMTR,
    private val contextStore: AIMIPhysioContextStoreMTR,
    private val llmAnalyzer: AIMILLMPhysioAnalyzerMTR, // Optional
    private val unifiedActivityProvider: UnifiedActivityProviderMTR,
    private val pipelineWatchdog: AIMIPhysioPipelineWatchdogMTR,
    private val sp: SP,
    private val aapsLogger: AAPSLogger
) {
    
    companion object {
        private const val TAG = "PhysioManager"
        const val WORK_TAG = "AIMI_PHYSIO_15M" // Frequency increased for near real-time context
        private const val PREF_KEY_LAST_UPDATE = "aimi_physio_last_update_ms"
        
        // Static accessor for Worker
        var instance: AIMIPhysioManagerMTR? = null
            private set
    }
    
    private val isRunning = AtomicBoolean(false)
    private var lastUpdateTime: Long = 0
    private var previousFeatures: PhysioFeaturesMTR? = null
    
    init {
        instance = this
    }
    
    /**
     * Starts the physiological analysis manager
     * Called by OpenAPSAIMIPlugin.onStart()
     */
    fun start() {
        val enabled = isEnabled()
        aapsLogger.info(LTag.APS, "[$TAG] 🚀 Starting AIMI Physiological Manager (Enabled: $enabled)")

        if (!enabled) {
            cancelScheduledWork()
            isRunning.set(false)
            return
        }
        
        if (isRunning.get()) {
            return
        }
        
        // Restore last update time
        lastUpdateTime = sp.getLong(PREF_KEY_LAST_UPDATE, 0)
        
        // Schedule periodic updates
        schedulePeriodicWork()

        Thread {
            try {
                pipelineWatchdog.runCheckAndRecover()
            } catch (e: Exception) {
                aapsLogger.warn(LTag.APS, "[$TAG] Initial pipeline watchdog failed: ${e.message}")
            }
        }.start()

        isRunning.set(true)
        
        // Log current context status
        contextStore.logStatus()
        
        // 🚀 BOOTSTRAP LOGIC
        // Trigger if:
        // 1. Data is stale (> 4h)
        // 2. Never synced (lastUpdateTime == 0)
        // 3. Data is invalid/empty (confidence low)
        
        val timeSinceUpdate = System.currentTimeMillis() - lastUpdateTime
        val stale = timeSinceUpdate > (4 * 60 * 60 * 1000)
        
        // Check current confidence to spot empty/failed states
        val currentContext = contextStore.getLastContextUnsafe()
        val lowConfidence = currentContext == null || currentContext.confidence < 0.1
        
        if (stale || lastUpdateTime == 0L || lowConfidence) {
             val reason = when {
                 stale -> "Data stale (${timeSinceUpdate/60000}min old)"
                 lowConfidence -> "Low confidence/No data"
                 else -> "First run"
             }
             aapsLogger.info(LTag.APS, "[$TAG] 🔄 Triggering bootstrap update: $reason")
             scheduleBootstrapUpdate()
        } else {
            aapsLogger.info(LTag.APS, "[$TAG] ✅ Data is fresh (${timeSinceUpdate/60000}min old) and valid. No immediate update needed.")
        }
    }
    
    /**
     * Schedules periodic measurement using WorkManager
     * Implements 3-tier strategy: Realtime(15m), Metabolic(30m), Daily(24h)
     */
    private fun schedulePeriodicWork() {
        try {
            // Cleanup old workers
            WorkManager.getInstance(context).cancelAllWorkByTag("AIMI_PHYSIO_4H")
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .build()

            // 1. Realtime Worker (15m - Best effort)
            val realtimeRequest = PeriodicWorkRequestBuilder<PhysioRealtimeWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag("AIMI_PHYSIO_REALTIME")
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "AIMI_PHYSIO_REALTIME",
                ExistingPeriodicWorkPolicy.UPDATE,
                realtimeRequest
            )
            
            // 2. Metabolic Worker (30m)
            val metabolicRequest = PeriodicWorkRequestBuilder<PhysioMetabolicWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag("AIMI_PHYSIO_METABOLIC")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "AIMI_PHYSIO_METABOLIC",
                ExistingPeriodicWorkPolicy.UPDATE,
                metabolicRequest
            )
            
            // 3. Daily Worker (24h)
            val dailyRequest = PeriodicWorkRequestBuilder<PhysioDailyWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag("AIMI_PHYSIO_DAILY")
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "AIMI_PHYSIO_DAILY",
                ExistingPeriodicWorkPolicy.KEEP, // Don't replace if existing, to maintain schedule
                dailyRequest
            )

            val watchdogRequest = PeriodicWorkRequestBuilder<PhysioPipelineWatchdogWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag("AIMI_PHYSIO_WATCHDOG")
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PhysioPipelineWatchdogWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                watchdogRequest
            )

            aapsLogger.info(LTag.APS, "[$TAG] ✅ Periodic work scheduled (Realtime 15m, Metabolic 30m, Daily 24h, Watchdog 6h)")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Failed to schedule work", e)
        }
    }
    
    /**
     * Schedules an immediate one-shot bootstrap update
     */
    private fun scheduleBootstrapUpdate() {
        try {
            val bootstrapRequest = androidx.work.OneTimeWorkRequestBuilder<PhysioRealtimeWorker>()
                .addTag("AIMI_PHYSIO_BOOTSTRAP")
                .setInitialDelay(5, TimeUnit.SECONDS) // Small delay to let system settle
                .build()
                
            WorkManager.getInstance(context).enqueue(bootstrapRequest)
            
            aapsLogger.info(LTag.APS, "[$TAG] 🚀 Bootstrap update scheduled (5s delay)")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Failed to schedule bootstrap", e)
        }
    }
    
    /**
     * Stops the manager and cancels periodic physio WorkManager jobs.
     * Called by OpenAPSAIMIPlugin.onStop() or when the user disables Physio in preferences.
     */
    fun stop() {
        cancelScheduledWork()
        isRunning.set(false)
        aapsLogger.info(LTag.APS, "[$TAG] Manager stopped; WorkManager physio jobs cancelled")
    }

    /** Used by workers to no-op when Physio is off (avoids work after stop/cancel races). */
    fun isPhysioAssistantEnabled(): Boolean = isEnabled()

    private fun cancelScheduledWork() {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("AIMI_PHYSIO_REALTIME")
            wm.cancelUniqueWork("AIMI_PHYSIO_METABOLIC")
            wm.cancelUniqueWork("AIMI_PHYSIO_DAILY")
            wm.cancelUniqueWork(PhysioPipelineWatchdogWorker.WORK_NAME)
            wm.cancelAllWorkByTag("AIMI_PHYSIO_BOOTSTRAP")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] cancelScheduledWork failed", e)
        }
    }
    
    /**
     * Manually triggers an update
     */
    fun triggerManualUpdate(): Boolean {
        if (!isEnabled()) return false
        
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Manual update requested")
        
        Thread {
            try {
                performUpdate(daysBack = 7, runLLM = true)
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] Manual update failed", e)
            }
        }.start()
        
        return true
    }
    
    /**
     * Performs complete physiological analysis pipeline
     * Public for Worker access
     */
    /**
     * Performs complete physiological analysis pipeline
     * Public for Worker access
     * 
     * @param daysBack Window for historical data fetch (default 7)
     * @param runLLM Whether to run the optional LLM analysis (default skip to save battery/API)
     */
    fun performUpdate(daysBack: Int = 7, runLLM: Boolean = false): Boolean {
        return try {
            runPhysioPipeline(daysBack, runLLM)
        } finally {
            // Restores pre–Mar 23 behavior: workers had fetchSnapshot(); pipeline alone never updated getLastSnapshot().
            try {
                repo.fetchSnapshot()
            } catch (e: Exception) {
                aapsLogger.warn(LTag.APS, "[$TAG] Merged health snapshot refresh failed: ${e.message}")
            }
        }
    }

    private fun runPhysioPipeline(daysBack: Int, runLLM: Boolean): Boolean {
        val startTime = System.currentTimeMillis()
        var fetchMs = 0L
        var extractMs = 0L
        var analyzeMs = 0L

        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Pipeline Start (daysBack=$daysBack runLLM=$runLLM)")

        try {
            val hcReady = dataRepository.isAvailable()
            if (!hcReady) {
                aapsLogger.warn(LTag.APS, "[$TAG] Health Connect SDK/client not fully available — HC reads may be empty; Wear/phone DB can still feed the pipeline")
            }

            // Step 1: Fetch raw data (HC) + optional enrichment from unified DB (Wear / HC sync rows)
            val t0 = System.currentTimeMillis()
            val rawData = try {
                enrichRawFromUnifiedIfNeeded(dataRepository.fetchAllData(daysBack = daysBack))
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] ❌ Fetch error", e)
                return false
            }
            fetchMs = System.currentTimeMillis() - t0

            if (!rawData.hasAnyData()) {
                aapsLogger.warn(LTag.APS, "[$TAG] ⚠️ No physiological data available")
                // Don't wipe context immediately, maybe transient
                return false
            }

            // Step 2: Extract features
            val t1 = System.currentTimeMillis()
            val features = featureExtractor.extractFeatures(rawData, previousFeatures)
            previousFeatures = features
            extractMs = System.currentTimeMillis() - t1

            // Step 3: Update baseline
            val baseline = baselineModel.updateBaseline(features)

            // Step 4: Analyze context
            val t2 = System.currentTimeMillis()
            var context = contextEngine.analyze(features, baseline)
            analyzeMs = System.currentTimeMillis() - t2

            // 🤖 Step 4b: Cognitive Analysis (LLM - Optional)
            if (runLLM && isLLMEnabled()) {
                // Only run LLM if Data is valid to avoid hallucination on empty data
                if (features.hasValidData) {
                    val narrative = llmAnalyzer.analyze(features, baseline, context)
                    if (narrative.isNotBlank()) {
                        context = context.copy(narrative = narrative)
                        aapsLogger.info(LTag.APS, "[$TAG] 🤖 LLM Insight: $narrative")
                    }
                }
            }

            // Step 5: Store
            contextStore.updateContext(context, baseline)

            lastUpdateTime = System.currentTimeMillis()
            sp.putLong(PREF_KEY_LAST_UPDATE, lastUpdateTime)

            val totalMs = System.currentTimeMillis() - startTime

            // STRUCTURED LOG (Production Level)
            aapsLogger.info(
                LTag.APS,
                "[$TAG] ✅ RUN COMPLETE | State: ${context.state} | Conf: ${(context.confidence * 100).toInt()}% | " +
                    "Qual: ${(features.dataQuality * 100).toInt()}% | " +
                    "Counts: Sleep=${if (rawData.sleep?.hasValidData() == true) "Yes" else "No"}, HRV=${rawData.hrv.size}, RHR=${rawData.rhr.size}, Steps=${if (rawData.steps > 0) "Yes" else "No"} | " +
                    "Timings: Fetch=${fetchMs}ms, Extr=${extractMs}ms, Analysis=${analyzeMs}ms (Total: ${totalMs}ms)"
            )

            return true
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Pipeline CRASH", e)
            return false
        }
    }

    /**
     * When Health Connect returns no usable series but Wear / phone / HC-sync rows exist in the DB,
     * merge those vitals so the pipeline and context store can still update (Mar 23 regression fix).
     */
    private fun enrichRawFromUnifiedIfNeeded(raw: RawPhysioDataMTR): RawPhysioDataMTR {
        if (raw.hasAnyData()) return raw
        val now = System.currentTimeMillis()
        val hr = unifiedActivityProvider.getLatestHeartRate(60 * 60 * 1000L)?.bpm?.toInt() ?: 0
        val stepsWin = unifiedActivityProvider.getStepsTotalSince(now - 24 * 60 * 60 * 1000L)?.steps ?: 0
        if (hr <= 0 && stepsWin <= 0) return raw
        aapsLogger.info(LTag.APS, "[$TAG] Enriched physio input from unified DB: ambientHr=$hr ambientSteps24hWindow=$stepsWin")
        return raw.copy(ambientHeartRateBpm = hr, ambientStepsAggregated = stepsWin)
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
    
    fun getStatus(): Map<String, String> {
        val now = System.currentTimeMillis()
        val timeSinceUpdate = now - lastUpdateTime
        
        return mapOf(
            "isRunning" to isRunning.get().toString(),
            "isEnabled" to isEnabled().toString(),
            "lastUpdate" to "${timeSinceUpdate / (60 * 60 * 1000)}h ago",
            "contextValid" to contextStore.isValid().toString()
        )
    }
    
    fun reset() {
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Resetting Physiological Manager")
        contextStore.clear()
        baselineModel.clearHistory()
        dataRepository.clearCache()
        previousFeatures = null
        lastUpdateTime = 0
        sp.putLong(PREF_KEY_LAST_UPDATE, 0)
    }
}
