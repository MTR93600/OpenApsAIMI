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
            
            val hcRepo = manager.repo.getHcRepo() // We need to expose this or add method in Repo
            // Ideally Repo handles everything.
            
            // Trigger Snapshot Update (The Repo handles step/hr fetch internally if we moved logic there)
            // But verify: HealthContextRepository.fetchSnapshot() currently calls hcRepo internally.
            
            manager.repo.fetchSnapshot()
            
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

            manager.repo.fetchSnapshot()
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
            
            // We need access to underlying HC repo to force heavy fetch
            // Or add a "forceRefresh" method to HealthContextRepository
            // For now, let's just fetchSnapshot, which does 1 day lookback.
            // If we want 7 days history updated, we need access to hcRepo.
            
            manager.repo.forceHeavyRefresh()
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}

