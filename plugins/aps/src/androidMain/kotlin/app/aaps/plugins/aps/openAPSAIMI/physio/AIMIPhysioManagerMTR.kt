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
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 🎛️ AIMI Physiological Manager - MTR Implementation
 *
 * The Android schedule for the physiological analysis run. The run itself is
 * [PhysioPipeline], in shared code, so an iPhone goes through the same lines.
 *
 * Responsibilities:
 * 1. Schedule data collection via WorkManager (realtime 15m, metabolic 30m, daily 24h)
 * 2. Start [PhysioPipeline] from those jobs, and refresh the merged health snapshot after it
 * 3. Handle errors gracefully
 *
 * This class is the **only** thing that starts the run on Android. The shared
 * [PhysioPipelineFeed] stands aside here (see [AndroidPhysioPipelineFeed]), because feeding the
 * rolling baseline twice from the same readings would move the mean and the spread the dose path
 * reads.
 *
 * @author MTR & Lyra AI - AIMI Physiological Intelligence
 */
@SingleIn(AppScope::class)
class AIMIPhysioManagerMTR @Inject constructor(
    private val context: Context,
    val repo: HealthContextRepository, // Public for Workers
    private val pipeline: PhysioPipeline,
    private val contextStore: AIMIPhysioContextStoreMTR,
    private val pipelineWatchdog: AIMIPhysioPipelineWatchdogMTR,
    private val aapsLogger: AAPSLogger
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "PhysioManager"
        const val WORK_TAG = "AIMI_PHYSIO_15M" // Frequency increased for near real-time context

        // Static accessor for Worker
        var instance: AIMIPhysioManagerMTR? = null
            private set
    }

    private val isRunning = AtomicBoolean(false)

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
        val lastUpdateTime = pipeline.restoreLastUpdateTime()

        // Schedule periodic updates
        schedulePeriodicWork()

        ioScope.launch {
            try {
                pipelineWatchdog.runCheckAndRecover()
            } catch (e: Exception) {
                aapsLogger.warn(LTag.APS, "[$TAG] Initial pipeline watchdog failed: ${e.message}")
            }
        }

        isRunning.set(true)
        
        // Log current context status
        contextStore.logStatus()
        
        // 🚀 BOOTSTRAP LOGIC
        // Trigger if:
        // 1. Data is stale (> 4h)
        // 2. Never synced (lastUpdateTime == 0)
        // 3. Data is invalid/empty (confidence low)
        
        val timeSinceUpdate = aimiWallClockMs() - lastUpdateTime
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

    /** The one reader of the physio preference now lives with the run. See [PhysioPipeline]. */
    private fun isEnabled(): Boolean = pipeline.isPhysioAssistantEnabled()

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
        
        ioScope.launch {
            try {
                performUpdate(daysBack = 7, runLLM = true)
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "[$TAG] Manual update failed", e)
            }
        }
        
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
    suspend fun performUpdate(daysBack: Int = 7, runLLM: Boolean = false): Boolean {
        return try {
            pipeline.run(daysBack, runLLM)
        } finally {
            // Restores pre–Mar 23 behavior: workers had fetchSnapshot(); pipeline alone never updated getLastSnapshot().
            try {
                repo.fetchSnapshot()
            } catch (e: Exception) {
                aapsLogger.warn(LTag.APS, "[$TAG] Merged health snapshot refresh failed: ${e.message}")
            }
        }
    }

    
    // ═══════════════════════════════════════════════════════════════════════
    // STATUS & DEBUGGING
    // ═══════════════════════════════════════════════════════════════════════
    
    fun getStatus(): Map<String, String> {
        val now = aimiWallClockMs()
        val timeSinceUpdate = now - pipeline.lastUpdateTimeMs

        return mapOf(
            "isRunning" to isRunning.get().toString(),
            "isEnabled" to isEnabled().toString(),
            "lastUpdate" to "${timeSinceUpdate / (60 * 60 * 1000)}h ago",
            "contextValid" to contextStore.isValid().toString()
        )
    }

    /** Forgets every learned baseline and stored context. See [PhysioPipeline.reset]. */
    fun reset() = pipeline.reset()
}
