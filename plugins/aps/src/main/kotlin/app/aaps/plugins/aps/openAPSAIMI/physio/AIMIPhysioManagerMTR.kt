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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🎛️ AIMI Physiological Manager - MTR Implementation
 * 
 * Central orchestrator for physiological analysis pipeline.
 * Uses WorkManager for reliable periodic execution (every 4 hours).
 * 
 * Responsibilities:
 * 1. Schedule data collection every 4 hours via WorkManager
 * 2. Coordinate all pipeline components
 * 3. Handle errors gracefully
 * 
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@Singleton
class AIMIPhysioManagerMTR @Inject constructor(
    private val context: Context,
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
        const val WORK_TAG = "AIMI_PHYSIO_4H"
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
        if (!isEnabled()) {
            aapsLogger.info(LTag.APS, "[$TAG] Physiological Assistant disabled in preferences")
            return
        }
        
        if (isRunning.get()) {
            return
        }
        
        aapsLogger.info(LTag.APS, "[$TAG] 🚀 Starting AIMI Physiological Manager (WorkManager)")
        
        // Restore last update time
        lastUpdateTime = sp.getLong(PREF_KEY_LAST_UPDATE, 0)
        
        // Schedule periodic updates
        schedulePeriodicWork()
        
        isRunning.set(true)
        
        // Log current context status
        contextStore.logStatus()
        
        // 🚀 BOOTSTRAP: Schedule immediate one-shot if stale or never synced
        val stale = (System.currentTimeMillis() - lastUpdateTime) > (4 * 60 * 60 * 1000)
        if (stale || lastUpdateTime == 0L) {
             aapsLogger.info(LTag.APS, "[$TAG] Data is stale/never synced - triggering bootstrap")
             scheduleBootstrapUpdate()
        }
    }
    
    /**
     * Schedules periodic measurement using WorkManager
     */
    private fun schedulePeriodicWork() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // Health Connect is local
                .setRequiresBatteryNotLow(true)
                .build()

            val workRequest = PeriodicWorkRequestBuilder<AIMIPhysioWorkerMTR>(4, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(WORK_TAG)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            
            aapsLogger.info(LTag.APS, "[$TAG] ✅ Periodic work scheduled (4h interval)")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] Failed to schedule work", e)
        }
    }
    
    /**
     * Schedules an immediate one-shot bootstrap update
     */
    private fun scheduleBootstrapUpdate() {
        try {
            val bootstrapRequest = androidx.work.OneTimeWorkRequestBuilder<AIMIPhysioWorkerMTR>()
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
     * Stops the manager
     * Called by OpenAPSAIMIPlugin.onStop()
     */
    fun stop() {
        // We generally don't stop WorkManager tasks on simple stop, 
        // they should persist. But flag checks prevent execution if plugin disabled.
        isRunning.set(false)
        aapsLogger.info(LTag.APS, "[$TAG] Manager stopped (WorkManager implementation)")
    }
    
    /**
     * Manually triggers an update
     */
    fun triggerManualUpdate(): Boolean {
        if (!isEnabled()) return false
        
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
    
    /**
     * Performs complete physiological analysis pipeline
     * Public for Worker access
     */
    fun performUpdate(): Boolean {
        val startTime = System.currentTimeMillis()
        var fetchMs = 0L
        var extractMs = 0L
        var analyzeMs = 0L
        
        aapsLogger.info(LTag.APS, "[$TAG] 🔄 Pipeline Start (Window: 7 days)")
        
        try {
            // Check Health Connect availability first
            val isHCAvailable = dataRepository.isAvailable()
            if (!isHCAvailable) {
                aapsLogger.error(LTag.APS, "[$TAG] ❌ Health Connect client unavailable")
                return false
            }
            
            // Step 1: Fetch raw data
            val t0 = System.currentTimeMillis()
            val rawData = try {
                dataRepository.fetchAllData(daysBack = 7)
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
            
            // Step 5: Store
            contextStore.updateContext(context, baseline)
            
            lastUpdateTime = System.currentTimeMillis()
            sp.putLong(PREF_KEY_LAST_UPDATE, lastUpdateTime)
            
            val totalMs = System.currentTimeMillis() - startTime
            
            // STRUCTURED LOG (Production Level)
            aapsLogger.info(
                LTag.APS,
                "[$TAG] ✅ RUN COMPLETE | State: ${context.state} | Conf: ${(context.confidence*100).toInt()}% | " +
                "Qual: ${(features.dataQuality*100).toInt()}% | " +
                "Counts: Sleep=${if (rawData.sleep?.hasValidData() == true) "Yes" else "No"}, HRV=${rawData.hrv.size}, RHR=${rawData.rhr.size}, Steps=${if (rawData.steps > 0) "Yes" else "No"} | " +
                "Timings: Fetch=${fetchMs}ms, Extr=${extractMs}ms, Analysis=${analyzeMs}ms (Total: ${totalMs}ms)"
            )
            
            return true
            
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "[$TAG] ❌ Pipeline CRASH", e)
            return false
        }
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
