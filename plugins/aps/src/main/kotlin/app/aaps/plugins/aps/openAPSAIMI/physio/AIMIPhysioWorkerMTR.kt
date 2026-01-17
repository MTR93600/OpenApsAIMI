package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 👷 AIMI Physiological Update Worker
 * 
 * Scheduled by AIMIPhysioManagerMTR (every 4 hours).
 * Triggers the physiological analysis pipeline.
 * 
 * @author MTR & Lyra AI
 */
class AIMIPhysioWorkerMTR(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Use static accessor to get the singleton Manager instance
        // This avoids complex Dagger WorkerFactory setup
        val manager = AIMIPhysioManagerMTR.instance
        
        if (manager == null) {
            // App might be initializing or killed.
            // Retry allows us to wait until the Plugin starts and sets the instance.
            return Result.retry()
        }

        return try {
            // performUpdate() returns true if successful, false if failed/no data
            // We return success() in both cases to satisfy the periodic schedule
            // unless an unhandled exception propagates (which performUpdate mostly catches).
            manager.performUpdate()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            // Only retry on unexpected crashes, otherwise respect the catch blocks in Manager
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
