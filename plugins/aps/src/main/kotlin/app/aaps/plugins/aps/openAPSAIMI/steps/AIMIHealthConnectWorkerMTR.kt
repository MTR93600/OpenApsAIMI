package app.aaps.plugins.aps.openAPSAIMI.steps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.aaps.plugins.aps.openAPSAIMI.steps.AIMIHealthConnectSyncServiceMTR

/**
 * 👷 AIMI Health Connect Sync Worker
 * 
 * WorkManager implementation that delegates to the singleton SyncService.
 * Uses static accessor to avoid Dagger WorkerFactory complexity.
 * 
 * @author MTR & Lyra AI
 */
class AIMIHealthConnectWorkerMTR(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val service = AIMIHealthConnectSyncServiceMTR.instance
            
            if (service == null) {
                // Service not initialized yet (App pending start?)
                // Retry later
                return Result.retry()
            }
            
            // Perform the sync using the existing robust service
            service.syncDataToDatabase()
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
    
    companion object {
        const val WORK_NAME = "AIMI_HEALTH_CONNECT_SYNC"
        const val WORK_NAME_ONCE = "AIMI_HEALTH_CONNECT_SYNC_NOW"
    }
}
