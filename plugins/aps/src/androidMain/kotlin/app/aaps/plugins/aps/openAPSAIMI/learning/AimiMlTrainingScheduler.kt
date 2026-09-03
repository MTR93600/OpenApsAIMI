package app.aaps.plugins.aps.openAPSAIMI.learning

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveBackfillWorker
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataBackfiller
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveNeuralTrainer
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveNeuralTrainerWorker
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import java.util.concurrent.TimeUnit
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

/**
 * Central WorkManager scheduling for AIMI on-device ML trainers.
 *
 * - Basal / T3C: every 1h, no constraints (runs regardless of charging / device-idle / battery) + one-time bootstrap
 * - Autodrive attention: 24h via [app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveNeuralTrainer]
 */
@SingleIn(AppScope::class)
class AimiMlTrainingScheduler @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper,
    // Injected so their `instance` companions exist before the workers look them up. Both used to
    // schedule themselves from their `init` block; the trainer was never instantiated, so its worker
    // was never enqueued and the attention weights were never produced.
    @Suppress("unused") private val autodriveNeuralTrainer: AutodriveNeuralTrainer,
    @Suppress("unused") private val autodriveDataBackfiller: AutodriveDataBackfiller,
) {

    fun schedule() {
        // Runs every 1h with NO constraints — must execute regardless of charging / device-idle / battery. The old
        // setRequiresCharging + setRequiresDeviceIdle combo almost never coincided on real phones, so the worker never
        // fired and the weights were never produced. WorkManager may still defer during deep Doze to a maintenance
        // window, but there is no longer any charging/idle *requirement*. Actual retrains stay bounded by the
        // coordinator's rate limit + min-new-rows gate, so a 1h cadence is not wasteful.
        val basalRequest = PeriodicWorkRequestBuilder<BasalMlTrainerWorker>(1, TimeUnit.HOURS)
            .build()

        try {
            val wm = WorkManager.getInstance(context)
            // Legacy duplicate Autodrive 6h work (superseded by 24h AutodriveNeuralTrainer schedule)
            wm.cancelUniqueWork(LEGACY_AUTODRIVE_6H_WORK)
            // The backfiller used to enqueue itself from its `init`, and it *was* instantiated
            // (AutodriveEngine injects it), so these names are live in WorkManager on existing
            // installs. Leaving them would run two backfills over autodrive_dataset.csv at once —
            // one appending, one doing read-modify-rename — and rows written in that window are lost.
            wm.cancelUniqueWork(LEGACY_AUTODRIVE_BACKFILLER)
            wm.cancelUniqueWork(LEGACY_AUTODRIVE_NEURAL_TRAINER)
            // UPDATE (not KEEP): existing installs already enqueued the old charging+idle work; UPDATE re-applies the
            // relaxed constraints while preserving the periodic schedule, so the fix reaches devices that scheduled it.
            wm.enqueueUniquePeriodicWork(
                WORK_BASAL_ML,
                ExistingPeriodicWorkPolicy.UPDATE,
                basalRequest,
            )
            // Bootstrap: one immediate pass so the FIRST model is created ASAP when enough CSV data already exists.
            // REPLACE (not KEEP) while weights are still missing — a prior failed bootstrap must not block retries.
            val bootstrapNeeded =
                !storageHelper.getAimiFile(BASAL_WEIGHTS).exists() ||
                    !storageHelper.getAimiFile(T3C_WEIGHTS).exists()
            val bootstrapPolicy = if (bootstrapNeeded) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            val bootstrapRequest = OneTimeWorkRequestBuilder<BasalMlTrainerWorker>()
                .build()
            wm.enqueueUniqueWork(WORK_BASAL_ML_BOOTSTRAP, bootstrapPolicy, bootstrapRequest)
            // Autodrive attention gate: label backfill then training.
            //
            // Both used to carry setRequiresCharging + setRequiresDeviceIdle, the exact combination the
            // basal trainer had to drop because it almost never coincides on a real phone. Relaxed here
            // for the same reason, with UPDATE so installs that already enqueued the constrained work
            // pick up the change. Volume stays bounded upstream: the worker refuses to train until the
            // backfiller reports 2880 labelled rows, and the label itself is the observed outcome —
            // whether a hypo followed within 60 minutes.
            wm.enqueueUniquePeriodicWork(
                WORK_AUTODRIVE_BACKFILL,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AutodriveBackfillWorker>(6, TimeUnit.HOURS).build(),
            )
            wm.enqueueUniquePeriodicWork(
                WORK_AUTODRIVE_ATTENTION,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AutodriveNeuralTrainerWorker>(24, TimeUnit.HOURS).build(),
            )
            aapsLogger.info(
                LTag.APS,
                "AimiMlTrainingScheduler: basal/T3C trainer scheduled (1h, no constraints) + bootstrap " +
                    "enqueued (policy=$bootstrapPolicy, needed=$bootstrapNeeded); " +
                    "autodrive backfill (6h) + attention trainer (24h) scheduled",
            )
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "AimiMlTrainingScheduler: schedule failed", e)
        }
    }

    fun cancel() {
        try {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(WORK_BASAL_ML)
            wm.cancelUniqueWork(WORK_AUTODRIVE_BACKFILL)
            wm.cancelUniqueWork(WORK_AUTODRIVE_ATTENTION)
            aapsLogger.info(LTag.APS, "AimiMlTrainingScheduler: basal/T3C + autodrive trainers cancelled")
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "AimiMlTrainingScheduler: cancel failed", e)
        }
    }

    companion object {
        const val WORK_BASAL_ML = "AIMI_BASAL_ML_TRAINER"
        const val WORK_BASAL_ML_BOOTSTRAP = "AIMI_BASAL_ML_TRAINER_BOOTSTRAP"
        const val WORK_AUTODRIVE_BACKFILL = "AIMI_AUTODRIVE_BACKFILL"
        const val WORK_AUTODRIVE_ATTENTION = "AIMI_AUTODRIVE_ATTENTION_TRAINER"
        private const val LEGACY_AUTODRIVE_6H_WORK = "AIMINeuralTrainer"
        private const val LEGACY_AUTODRIVE_BACKFILLER = "AIMI_AUTODRIVE_BACKFILLER"
        private const val LEGACY_AUTODRIVE_NEURAL_TRAINER = "AIMI_AUTODRIVE_NEURAL_TRAINER"
        private const val BASAL_WEIGHTS = "basal_adaptive_weights.json"
        private const val T3C_WEIGHTS = "t3c_brain_weights.json"
    }
}
