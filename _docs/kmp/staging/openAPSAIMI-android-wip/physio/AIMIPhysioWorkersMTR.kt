package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/**
 * ⚡ Realtime Worker (Freq: 15min / Best effort)
 * Updates high-frequency metrics: HR, Steps, HRV(Spot).
 */
class PhysioRealtimeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val manager = AIMIPhysioManagerMTR.instance
            if (manager == null) return@withContext Result.retry()
            if (!manager.isPhysioAssistantEnabled()) return@withContext Result.success()

            // performUpdate() ends with HealthContextRepository.fetchSnapshot() (FC/steps from DB + HC merge)
            manager.performUpdate(daysBack = 1, runLLM = false)
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}

/**
 * 🧪 Metabolic Worker (Freq: 30-60min)
 */
class PhysioMetabolicWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val manager = AIMIPhysioManagerMTR.instance
            if (manager == null) return@withContext Result.retry()
            if (!manager.isPhysioAssistantEnabled()) return@withContext Result.success()

            manager.performUpdate(daysBack = 3, runLLM = false)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}

/**
 * 🌙 Daily Sleep Worker (Freq: 24h)
 */
class PhysioDailyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val manager = AIMIPhysioManagerMTR.instance
            if (manager == null) return@withContext Result.retry()
            if (!manager.isPhysioAssistantEnabled()) return@withContext Result.success()

            manager.performUpdate(daysBack = 7, runLLM = true)
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}

/**
 * Verifies DB + HC + merged snapshot; triggers HC sync and physio refresh when degraded.
 */
class PhysioPipelineWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val manager = AIMIPhysioManagerMTR.instance
            if (manager == null) return@withContext Result.retry()
            if (!manager.isPhysioAssistantEnabled()) return@withContext Result.success()

            val watchdog = AIMIPhysioPipelineWatchdogMTR.instance
            if (watchdog == null) return@withContext Result.retry()
            watchdog.runCheckAndRecover()
            Result.success()
        } catch (e: Exception) {
            return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "AIMI_PHYSIO_PIPELINE_WATCHDOG"
    }
}

