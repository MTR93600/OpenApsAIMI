package app.aaps.plugins.aps.openAPSAIMI.learning

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.BackgroundTasks.BGProcessingTask
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * iOS training scheduler for the AIMI ML learners.
 *
 * Mirrors Android's `BasalMlTrainerWorker` (WorkManager, every 6h, idle + charging) using
 * BGTaskScheduler with a `BGProcessingTask` that requires external power.
 *
 * The task identifier `app.aaps.aimi.ml-training` must be listed in the app's Info.plist under
 * `BGTaskSchedulerPermittedIdentifiers`, and the `processing` background mode must be enabled.
 * Without both, the system will never launch the task.
 *
 * Training itself reuses the commonMain pipeline (`NeuralModelTrainer`, dataset parsers). It
 * needs an [app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage] implementation, which
 * deliberately does not exist on iOS yet (see its KDoc): until one is provided, [runTraining]
 * is a no-op that reports [TrainingResult.STORAGE_UNAVAILABLE]. This keeps the feature visibly
 * absent instead of silently doing nothing.
 *
 * Call [register] once at app launch (before the app finishes launching), then [schedule] to
 * enqueue the next run. Each completed run should call [schedule] again to keep the cadence.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
object IosMlTrainingScheduler {

    /** BGTaskScheduler identifier. Must match Info.plist `BGTaskSchedulerPermittedIdentifiers`. */
    const val TASK_IDENTIFIER = "app.aaps.aimi.ml-training"

    /** Training cadence, mirroring Android's 6h worker. */
    const val TRAIN_INTERVAL_SECONDS = 6.0 * 60.0 * 60.0

    /** Minimum delay before the system may run the task. */
    const val EARLIEST_BEGIN_SECONDS = 15.0 * 60.0

    enum class TrainingResult {
        /** Training ran (or was correctly skipped by the coordinator gates). */
        DONE,

        /** No iOS `AimiStorage` implementation is available yet. */
        STORAGE_UNAVAILABLE,

        /** The task could not be scheduled (e.g. identifier not in Info.plist). */
        SCHEDULE_FAILED,
    }

    /**
     * Registers the task handler. Must be called before the app finishes launching,
     * otherwise the system may terminate the app when a task is due.
     *
     * Typically called from the SwiftUI App's `init` or an `AppDelegate` adaptor via
     * the Kotlin entry point.
     */
    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = TASK_IDENTIFIER,
            usingQueue = null,
        ) { task ->
            val processingTask = task as? BGProcessingTask
            if (processingTask != null) {
                // Re-schedule first: if training crashes, the next run is still enqueued.
                schedule()
                val result = runTraining()
                processingTask.setTaskCompletedWithSuccess(result != TrainingResult.SCHEDULE_FAILED)
            } else {
                task.setTaskCompletedWithSuccess(false)
            }
        }
    }

    /**
     * Enqueues the next training run.
     *
     * Uses `requiresExternalPower = true` to mirror Android's "idle + charging" constraint.
     * `requiresNetworkConnectivity` stays false: training is fully on-device.
     */
    fun schedule(): Boolean {
        val request = BGProcessingTaskRequest(TASK_IDENTIFIER).apply {
            setEarliestBeginDate(NSDate.dateWithTimeIntervalSinceNow(EARLIEST_BEGIN_SECONDS))
            setRequiresExternalPower(true)
            setRequiresNetworkConnectivity(false)
        }
        return try {
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error = null)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Runs one training pass through the commonMain pipeline.
     *
     * Currently a no-op returning [TrainingResult.STORAGE_UNAVAILABLE]: there is deliberately
     * no iOS `AimiStorage` implementation yet, and training without persistence would silently
     * do nothing. Once an iOS storage binding exists, wire it here and call the coordinator
     * equivalent of `BasalMlTrainingCoordinator.runScheduledTraining()`.
     */
    fun runTraining(): TrainingResult {
        // No iOS AimiStorage implementation exists (deliberate, see AimiStorage KDoc).
        // Training without a place to read the CSV and write the weights would be a silent no-op.
        return TrainingResult.STORAGE_UNAVAILABLE
    }
}
